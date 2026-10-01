package com.ivan.cinema.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/**
 * 源健康：启动时**静默**探测一次，按可信度取前 10 个可用源。
 *
 * 设计约束（用户要求"不影响正常体验"）：
 *   · 探测在后台协程里跑，UI 立刻用内置/上次结果渲染，探测完成后再静默更新；
 *   · 结果落盘，24 小时内不重复探测（避免每次冷启都打 20 个源）；
 *   · 探测只请求列表接口（最轻的请求），5 秒超时，并发 8。
 */
object SourceHealth {

    /** 可信度优先级：老牌大源在前（脏源/赌博片头概率低）。 */
    private val PRIORITY = listOf(
        "量子资源", "暴风资源", "天涯资源", "闪电资源", "光速资源",
        "红牛资源", "华文资源", "极速资源", "蓝泽资源", "索尼资源",
        "茅台资源", "无尽资源", "最大资源", "bdzy资源", "虎牙资源",
        "电影天堂", "新浪资源", "非凡资源", "jcloud", "小胡资源"
    )

    private const val PREF = "ivan_sources"
    private const val KEY_JSON = "verified"
    private const val KEY_TS = "verified_at"
    private const val TTL_MS = 24L * 60 * 60 * 1000

    /** 已核验的源（null = 还没结果，调用方回退全量）。 */
    val verified = mutableStateOf<List<VodSource>?>(null)

    /** 供各屏读取：优先用已核验的前 10 源。 */
    fun sources(ctx: Context): List<VodSource> =
        verified.value ?: SourcePool.load(ctx)

    /** App 启动时调用一次：读缓存 → 必要时后台刷新。 */
    fun init(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val cached = parseSources(prefs.getString(KEY_JSON, null))
        if (cached.isNotEmpty()) verified.value = cached

        val age = System.currentTimeMillis() - prefs.getLong(KEY_TS, 0L)
        if (cached.isEmpty() || age > TTL_MS) {
            refresh(ctx)
        }
    }

    /** 静默刷新：不阻塞、不弹任何 UI。 */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val all = SourcePool.load(app)
            val sem = Semaphore(8)
            val alive = coroutineScope {
                all.map { src ->
                    async {
                        sem.withPermit { probe(src) }
                    }
                }.awaitAll().filterNotNull()
            }
            if (alive.isEmpty()) return@launch
            val ranked = alive.sortedBy { s ->
                PRIORITY.indexOf(s.name).let { if (it < 0) 999 else it }
            }.take(10)
            verified.value = ranked
            app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_JSON, toJson(ranked))
                .putLong(KEY_TS, System.currentTimeMillis())
                .apply()
        }
    }

    /** 最轻的存活探测：列表接口 + code=1。 */
    private suspend fun probe(src: VodSource): VodSource? = runCatching {
        val r = okhttp3.Request.Builder()
            .url(src.api + "?ac=list&pg=1")
            .header("User-Agent", "Mozilla/5.0")
            .get()
            .build()
        MacCmsApi.CLIENT.newBuilder()
            .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
            .newCall(r).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                if (body.contains("\"code\":1")) src else null
            }
    }.getOrNull()

    private fun toJson(list: List<VodSource>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().put("name", s.name).put("api", s.api))
        }
        return arr.toString()
    }

    private fun parseSources(text: String?): List<VodSource> {
        if (text.isNullOrEmpty()) return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val api = o.optString("api").trim().trimEnd('/')
                val name = o.optString("name").trim()
                if (api.isNotEmpty() && name.isNotEmpty()) VodSource(name, api) else null
            }
        }.getOrDefault(emptyList())
    }
}
