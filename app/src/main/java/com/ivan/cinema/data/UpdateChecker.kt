package com.ivan.cinema.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val notes: String
)

/**
 * APP 更新检查（自用，无后端）：
 *   约定文件：APP 外部数据目录的 `update.json`
 *   （手机路径：Android/data/com.ivan.cinema/files/update.json，可从网盘/电脑同步进去）
 *   格式：{"versionCode":2,"versionName":"1.1.0","url":"https://...apk","notes":"更新说明"}
 *   也可通过 [REMOTE_URL] 指定一个远程地址（默认空 = 只查本地文件）。
 * 启动时静默检查一次，不打扰用户；有新版时设置页显示提示。
 */
object UpdateChecker {

    /** 可选的远程更新地址（例如你自己的网盘直链 / Gist raw）。留空则只读本地文件。 */
    private const val REMOTE_URL = ""

    val latest = mutableStateOf<UpdateInfo?>(null)

    fun check(ctx: Context, currentVersionCode: Int) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val info = readLocal(ctx) ?: readRemote()
            if (info != null && info.versionCode > currentVersionCode) {
                latest.value = info
            }
        }
    }

    private fun readLocal(ctx: Context): UpdateInfo? = runCatching {
        val f = File(ctx.getExternalFilesDir(null), "update.json")
        if (!f.isFile) return null
        parse(f.readText())
    }.getOrNull()

    private fun readRemote(): UpdateInfo? = runCatching {
        if (REMOTE_URL.isEmpty()) return null
        val req = okhttp3.Request.Builder().url(REMOTE_URL).get().build()
        MacCmsApi.CLIENT.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            parse(resp.body?.string() ?: return null)
        }
    }.getOrNull()

    private fun parse(text: String): UpdateInfo? = runCatching {
        val o = JSONObject(text)
        UpdateInfo(
            versionCode = o.optInt("versionCode", 0),
            versionName = o.optString("versionName", ""),
            url = o.optString("url", ""),
            notes = o.optString("notes", "")
        )
    }.getOrNull()
}
