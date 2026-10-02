package com.ivan.cinema.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 源清单自动更新。
 *
 * 之前源是死的：`SourcePool` 只有读路径，全项目没有一处写 `sources.json`，
 * 所以换源/加源只能改 assets 重新发版 —— 对一个 20 人的自用 App 来说太重了。
 *
 * 现在的链路：
 *   仓库 main 分支的 `sources_manifest.json`（带 version）
 *     → 拉下来比对本地已应用的版本
 *     → 更新就写进 `filesDir/sources.json`
 *     → `SourcePool` 本来就优先读它，**读取逻辑一行没改**
 *     → `SourceHealth.refresh` 重新探测并排序
 *
 * 清单格式（`list` 就是 sources.json 那个裸数组的内容，所以写盘时直接落 list）：
 * ```json
 * { "version": 2, "updated": "2026-10-02", "note": "加了 4K 专线源", "list": [ {"name": "...", "api": "..."} ] }
 * ```
 */
object SourceUpdater {

    private const val MANIFEST =
        "https://raw.githubusercontent.com/IvanXxxxyyuffff/ivan-cinema/main/sources_manifest.json"
    private const val PREF = "ivan_source_update"
    private const val KEY_VER = "applied_version"
    private const val KEY_AT = "last_check"
    private const val THROTTLE_MS = 12L * 60 * 60 * 1000

    /** 远端最新版本号（0 = 还没查过）。设置页显示用。 */
    val remoteVersion = mutableStateOf(0)
    /** 本地已应用的版本号。 */
    val appliedVersion = mutableStateOf(0)
    /** 最近一次检查的结果描述，设置页显示用。 */
    val status = mutableStateOf<String?>(null)

    private val CLIENT by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun readState(ctx: Context) {
        appliedVersion.value = prefs(ctx).getInt(KEY_VER, 0)
    }

    /**
     * 检查并应用。返回 true = 真的换了源清单。
     * [force] 忽略 12 小时节流（设置页手动点）。
     */
    suspend fun check(ctx: Context, force: Boolean = false): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            readState(ctx)
            val p = prefs(ctx)
            val age = System.currentTimeMillis() - p.getLong(KEY_AT, 0L)
            if (!force && age < THROTTLE_MS) {
                status.value = "刚检查过（${appliedVersion.value} 版）"
                return@withContext false
            }

            val manifest = runCatching { fetch() }.getOrNull()
            p.edit().putLong(KEY_AT, System.currentTimeMillis()).apply()
            if (manifest == null) {
                status.value = "拉取失败，沿用本地源清单"
                return@withContext false
            }

            remoteVersion.value = manifest.first
            val applied = appliedVersion.value
            if (manifest.first <= applied) {
                status.value = "已是最新（第 ${manifest.first} 版）"
                return@withContext false
            }

            val ok = runCatching {
                File(ctx.filesDir, "sources.json")
                    .writeText(JSONArray(manifest.second).toString())
            }.isSuccess
            if (!ok) {
                status.value = "写入失败"
                return@withContext false
            }

            p.edit().putInt(KEY_VER, manifest.first).apply()
            appliedVersion.value = manifest.first
            status.value = "已更新到第 ${manifest.first} 版（${manifest.second.length()} 个源）"
            // 读取侧不用动：SourcePool 优先读 filesDir，清缓存即可
            SourcePool.invalidate()
            SourceHealth.refresh(ctx)
            true
        }

    /** @return (version, listJsonArray) */
    private fun fetch(): Pair<Int, JSONArray> {
        val req = okhttp3.Request.Builder().url(MANIFEST)
            .header("User-Agent", "IVAN-CINEMA")
            // raw.githubusercontent 有 CDN，带个随机参数避免吃到旧缓存
            .header("Cache-Control", "no-cache")
            .get().build()
        CLIENT.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("空响应")
            val o = JSONObject(body)
            val ver = o.optInt("version", 0)
            val arr = o.optJSONArray("list") ?: error("manifest 没有 list")
            // 清单里每条都必须有 name + api，坏一条就整份不采用 —— 宁可不上新，也不能把首页搞空
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: error("list[$i] 不是对象")
                if (it.optString("api").trim().isEmpty() || it.optString("name").trim().isEmpty()) {
                    error("list[$i] 缺 name/api")
                }
            }
            if (ver <= 0) error("version 非法")
            return ver to arr
        }
    }
}
