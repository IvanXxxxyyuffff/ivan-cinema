package com.ivan.cinema.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 国漫 / 日漫的**热度榜**。
 *
 * 为什么用 B 站：四个平台的公开榜单接口都探过（2026-10）——
 *   · B 站国创榜 / 番剧榜：`api.bilibili.com/pgc/season/rank/web/list`，直接返回带 rank 的
 *     完整榜单，无需登录。国创榜正是"国漫热度"最对口的信号。
 *   · 腾讯视频 `pbaccess...PageServer/GetPageData`：ret=0 但 module 为空，要登录态。
 *   · 爱奇艺 `pcw-api.../topRanking`：返回 A00000 但 data 只有 `{base:{}}`，同样要登录态。
 *   · 优酷 webrank：整页是 Next.js 内嵌数据，但模块里只有轮播 + 纪录片/科技，**没有动漫榜**。
 * 所以只用 B 站；拿不到就返回空，界面退回源站自己的顺序，不做假数据。
 *
 * 落盘缓存 12 小时：榜单一天一变，没必要每次进页面都打一次外网。
 */
object HeatRank {

    enum class Kind(val seasonType: Int, val label: String) {
        /** 国创（国漫）。 */
        GUOCHUANG(4, "国漫"),
        /** 番剧（日漫）。 */
        FANJU(1, "日漫")
    }

    private const val API = "https://api.bilibili.com/pgc/season/rank/web/list"
    private const val TTL_MS = 12L * 60 * 60 * 1000
    private const val UA =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"

    private val CLIENT by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun file(ctx: Context, kind: Kind) = File(ctx.cacheDir, "heat_${kind.name}.json")

    /**
     * 取榜单（榜单顺序 = 热度从高到低）。
     * 先读缓存，过期或缺失才打网络；网络失败时**仍然返回旧缓存**（有总比没有好）。
     */
    suspend fun load(ctx: Context, kind: Kind): List<String> {
        val f = file(ctx, kind)
        val fresh = f.isFile && System.currentTimeMillis() - f.lastModified() < TTL_MS
        if (fresh) return parse(f.readText())
        val fetched = runCatching { fetch(kind) }.getOrNull()
        if (!fetched.isNullOrEmpty()) {
            runCatching { f.writeText(fetched.joinToString("\n")) }
            return fetched
        }
        return if (f.isFile) parse(f.readText()) else emptyList()
    }

    private fun parse(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    private suspend fun fetch(kind: Kind): List<String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = "$API?season_type=${kind.seasonType}&day=3"
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
     * 源站和 B 站对同一部片的写法差别很大（「牧神记」vs「牧神记 动态漫」、
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
        for (w in listOf("动态漫", "动态漫画", "国语版", "中文版", "日语版", "剧场版", "完结", "全集")) {
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
