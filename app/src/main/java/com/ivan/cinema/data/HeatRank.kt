package com.ivan.cinema.data

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 国漫 / 日漫的**综合热度榜**：把 B 站、爱奇艺、腾讯三家的公开榜单合成一张名次表。
 *
 * 为什么是三平台（采集方式各自验证过，2026-10）：
 *   · B 站：`api.bilibili.com/pgc/season/rank/web/list?season_type=4|1`，直接给带名次的
 *     国创榜/番剧榜，无需登录。权威，但只覆盖采集源动漫分类里 5~15% 的条目。
 *   · 爱奇艺：`www.iqiyi.com/ranks1PCW/4/{tagId}` 是**服务端直出**页面，榜单条目内嵌在
 *     `window.__NUXT__` 里（`title:"X",img:"…"` 恰好只命中榜单条目，不会误命中导航）。
 *     动漫频道有独立的国漫榜(tagId 8052642132978633) 与日漫榜(tagId 6234934322090433)，
 *     无需登录。它命中的正是采集源实际在放的动态漫/短动画（开心锤锤、大主宰年番…），
 *     覆盖面最实用 —— 这是单用 B 站时覆盖率低的主因。
 *   · 腾讯：`pbaccess...PageService/getPage` POST。**必须用它页面真实发出的请求体**
 *     （`page_type:"channel"` + `page_id:"100119"`）——参数错一个字接口照样 ret=0，
 *     但 module 是空的，不报错。取 `pc_shelves` 模块的 cards[].params.title，那里才是
 *     干净有序的片库列表（轮播模块混着「片名|一句话」和广告位，只作兜底）。
 *   优酷 webrank 页压根没有动漫榜模块，放弃。
 *
 * 合成：每个平台给一条「名次从高到低」的列表，对每个片名算 score = Σ 权重/(名次+1)，
 * 按 score 降序。权重 B站 1.0 > 爱奇艺 0.6 > 腾讯 0.4（B 站权威但覆盖窄，
 * 爱奇艺/腾讯覆盖宽但秩序偏推荐流）。三家都拿不到的条目由调用方用源站 vod_hits 兜底。
 *
 * 落盘缓存 12 小时；网络全失败时**仍然返回旧缓存**（有总比没有好），不做假数据。
 */
object HeatRank {

    enum class Kind(val seasonType: Int, val label: String, val iqTag: String) {
        /** 国创（国漫）。 */
        GUOCHUANG(4, "国漫", "8052642132978633"),
        /** 番剧（日漫）。 */
        FANJU(1, "日漫", "6234934322090433")
    }

    private const val BILI_API = "https://api.bilibili.com/pgc/season/rank/web/list"
    private const val IQ_BASE = "https://www.iqiyi.com/ranks1PCW/4/"
    private const val TX_API =
        "https://pbaccess.video.qq.com/trpc.vector_layout.page_view.PageService/getPage" +
            "?video_appid=3000010&vversion_platform=2"
    private const val TTL_MS = 12L * 60 * 60 * 1000
    private const val UA =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"

    // 腾讯 getPage 的真实请求体（从页面抓包重放得到）。参数必须保持一致，否则 module 为空。
    private const val TX_BODY =
        """{"page_params":{"page_type":"channel","page_id":"100119","scene":"channel","new_mark_label_enabled":"1","vl_to_mvl":"","skip_privacy_types":"0","support_click_scan":"1"},"page_bypass_params":{"params":{"platform_id":"2","caller_id":"3000010","data_mode":"default","user_mode":"default","specified_strategy":"","page_type":"channel","page_id":"100119","scene":"channel","new_mark_label_enabled":"1"},"scene":"channel","app_version":"","abtest_bypass_id":"a1638d5cdcc8c65b"},"page_context":null}"""

    // 爱奇艺榜单条目：title 后紧跟 img 的才是剧集卡，不会误命中导航/标签名。
    private val IQ_TITLE = Regex("""title:"((?:[^"\\]|\\.)*)",img:""")

    private val CLIENT by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun file(ctx: Context, kind: Kind) = File(ctx.cacheDir, "heat_${kind.name}.json")

    /**
     * 取综合榜（榜单顺序 = 热度从高到低）。
     * 先读缓存，过期或缺失才打网络；网络失败时**仍然返回旧缓存**。
     */
    suspend fun load(ctx: Context, kind: Kind): List<String> {
        val f = file(ctx, kind)
        val fresh = f.isFile && System.currentTimeMillis() - f.lastModified() < TTL_MS
        if (fresh) return parse(f.readText())
        val fetched = merge(
            listOf(
                runCatching { fetchBili(kind) }.getOrDefault(emptyList()) to 1.0,
                runCatching { fetchIqiyi(kind) }.getOrDefault(emptyList()) to 0.6,
                runCatching { fetchTencent() }.getOrDefault(emptyList()) to 0.4
            )
        )
        if (fetched.isNotEmpty()) {
            runCatching { f.writeText(fetched.joinToString("\n")) }
            return fetched
        }
        return if (f.isFile) parse(f.readText()) else emptyList()
    }

    private fun parse(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    /**
     * 合成一张名次表：每个片名 score = Σ 权重/(名次+1)，score 高者在前。
     * 以 [normalize] 作合并键，跨平台不同写法（「斗罗大陆Ⅱ· 更新」/「斗罗大陆Ⅱ」）能归并。
     * 并列时按首次出现的先后稳定排序。
     */
    private fun merge(platforms: List<Pair<List<String>, Double>>): List<String> {
        val score = HashMap<String, Double>()
        val display = HashMap<String, String>()
        val order = ArrayList<String>()
        for ((list, weight) in platforms) {
            list.forEachIndexed { idx, raw ->
                val t = raw.trim()
                if (t.length < 2) return@forEachIndexed
                val key = normalize(t)
                if (key.length < 2) return@forEachIndexed
                if (!display.containsKey(key)) {
                    display[key] = t
                    order.add(key)
                }
                score[key] = (score[key] ?: 0.0) + weight / (idx + 1)
            }
        }
        return order.sortedByDescending { score[it] ?: 0.0 }.map { display.getValue(it) }
    }

    // ---------- B 站 ----------

    private suspend fun fetchBili(kind: Kind): List<String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = "$BILI_API?season_type=${kind.seasonType}&day=3"
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                // B 站接口对空 Referer 会返回 -412 风控，带上站点 Referer 才稳
                .header("Referer", "https://www.bilibili.com/")
                .get()
                .build()
            CLIENT.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val arr = JSONObject(body).optJSONObject("data")?.optJSONArray("list")
                    ?: return@withContext emptyList<String>()
                val out = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i).optString("title").trim()
                    if (t.isNotEmpty()) out.add(t)
                }
                out
            }
        }

    // ---------- 爱奇艺 ----------

    private suspend fun fetchIqiyi(kind: Kind): List<String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val req = okhttp3.Request.Builder()
                .url(IQ_BASE + kind.iqTag)
                .header("User-Agent", UA)
                .header("Referer", "https://www.iqiyi.com/")
                .get()
                .build()
            CLIENT.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val html = resp.body?.string() ?: return@withContext emptyList()
                val start = html.indexOf("window.__NUXT__=")
                if (start < 0) return@withContext emptyList()
                val end = html.indexOf("</script>", start).let { if (it < 0) html.length else it }
                val payload = html.substring(start, end)
                val out = ArrayList<String>()
                for (m in IQ_TITLE.findAll(payload)) {
                    val t = unescapeJs(m.groupValues[1]).trim()
                    if (t.isNotEmpty()) out.add(t)
                }
                out
            }
        }

    /** 反转义 JS 字符串字面量里可能出现的序列（\uXXXX、\" 、\\ 、\/ 等）。 */
    private fun unescapeJs(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { sb.append(c); i++; continue }
            when (val n = s[i + 1]) {
                'u' -> {
                    if (i + 5 < s.length) {
                        val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                        if (code != null) { sb.append(code.toChar()); i += 6; continue }
                    }
                    sb.append(n); i += 2
                }
                'n', 't' -> { sb.append(' '); i += 2 }
                'r' -> { i += 2 }
                else -> { sb.append(n); i += 2 }
            }
        }
        return sb.toString()
    }

    // ---------- 腾讯 ----------

    private suspend fun fetchTencent(): List<String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val body = TX_BODY.toRequestBody("application/json".toMediaType())
            val req = okhttp3.Request.Builder()
                .url(TX_API)
                .header("User-Agent", UA)
                .header("Referer", "https://v.qq.com/channel/cartoon")
                .post(body)
                .build()
            CLIENT.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val text = resp.body?.string() ?: return@withContext emptyList()
                val root = runCatching { JSONObject(text) }.getOrNull()
                    ?: return@withContext emptyList()
                val cards = root.optJSONObject("data")?.optJSONArray("CardList")
                    ?: return@withContext emptyList<String>()
                val shelves = ArrayList<String>()
                val carousel = ArrayList<String>()
                for (i in 0 until cards.length()) {
                    val mod = cards.optJSONObject(i) ?: continue
                    when (mod.optString("type")) {
                        // 片库列表：干净有序，首选
                        "pc_shelves" -> collectCardTitles(mod, shelves)
                        // 轮播：标题常是「片名|一句话」，取竖线前一段兜底
                        "pc_carousel" -> collectCardTitles(mod, carousel)
                    }
                }
                if (shelves.isNotEmpty()) return@withContext shelves
                carousel.map { it.substringBefore('|').trim() }.filter { it.isNotEmpty() }
            }
        }

    /** 递归取模块内所有 card 的 params.title（模块层级不固定，所以走遍历）。 */
    private fun collectCardTitles(node: JSONObject, out: MutableList<String>) {
        when (node.optString("type")) {
            "pc_shelves", "pc_video", "pc_carousel" ->
                node.optJSONObject("params")?.optString("title")?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        for (k in node.keys()) {
            when (val v = node.opt(k)) {
                is JSONObject -> collectCardTitles(v, out)
                is JSONArray -> for (j in 0 until v.length()) {
                    (v.opt(j) as? JSONObject)?.let { collectCardTitles(it, out) }
                }
            }
        }
    }

    /**
     * 榜单名 → 位次（0 起，越小越热）。不在榜返回 [Int.MAX_VALUE]，
     * 这样 `sortedBy` 会把未上榜的排到后面，且因为排序稳定、它们之间的原顺序不变。
     */
    fun rankOf(ranking: List<String>, title: String): Int {
        if (ranking.isEmpty() || title.isBlank()) return Int.MAX_VALUE
        val n = normalize(title)
        if (n.length < 2) return Int.MAX_VALUE
        // 先找完全相等的，再退到互相包含 —— 源站常带「第X季」「动态漫」「（国语版）」这类后缀
        val exact = ranking.indexOfFirst { normalize(it) == n }
        if (exact >= 0) return exact
        val loose = ranking.indexOfFirst {
            val r = normalize(it)
            r.length >= 2 && (r.startsWith(n) || n.startsWith(r) || r.contains(n))
        }
        return if (loose >= 0) loose else Int.MAX_VALUE
    }

    /**
     * 归一化：去掉空白、括号内容、季/集后缀与常见噪音词，只留下可比的片名主干。
     * 源站和平台对同一部片的写法差别很大（「牧神记」vs「牧神记 动态漫」、
     * 「冰之城墙 第2季」vs「冰之城墙」），不归一化几乎匹配不上。
     */
    fun normalize(raw: String): String {
        var s = raw.lowercase()
        // 括号（中英文）整体去掉
        s = s.replace(Regex("[（(【\\[][^）)】\\]]*[）)】\\]]"), "")
        // 季/集/部/期 后缀
        s = s.replace(Regex("第\\s*[0-9一二三四五六七八九十百]+\\s*[季集部期话]"), "")
        s = s.replace(Regex("[0-9]+\\s*[季集部期话]"), "")
        // 常见噪音词
        for (w in listOf("动态漫", "动态漫画", "国语版", "中文版", "日语版", "剧场版", "完结", "全集", "更新", "首播")) {
            s = s.replace(w, "")
        }
        // 去掉所有非字母数字与非中日文字符
        s = s.replace(Regex("[^0-9a-z\\u4e00-\\u9fff]"), "")
        return s.trim()
    }

    /** 供外部（如调试页）读取整张榜。 */
    fun rawList(ranking: List<String>): JSONArray {
        val a = JSONArray()
        ranking.forEachIndexed { i, t -> a.put(JSONObject().put("rank", i + 1).put("title", t)) }
        return a
    }
}
