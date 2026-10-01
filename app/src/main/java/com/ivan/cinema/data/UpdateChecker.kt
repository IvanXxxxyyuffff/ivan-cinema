package com.ivan.cinema.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
 * APP 更新检查（自用）：
 *   更新清单 = 仓库里的 update.json（版本号 / 下载直链 / 更新说明）。
 *   国内直连 GitHub raw 常被墙 —— 并发请求多个镜像，**谁先回来用谁**。
 *   也支持本地文件 update.json（Android/data/com.ivan.cinema/files/），优先级最高。
 */
object UpdateChecker {

    private const val OWNER = "IvanXxxxyyuffff"
    private const val REPO = "ivan-cinema"
    private const val BRANCH = "main"
    private const val FILE = "update.json"

    /** 镜像列表：官方 + 四个国内可达通道（并发竞速，谁快用谁）。 */
    private val MIRRORS = listOf(
        "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$FILE",
        "https://ghfast.top/https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$FILE",
        "https://gh-proxy.com/https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$FILE",
        "https://cdn.jsdelivr.net/gh/$OWNER/$REPO@$BRANCH/$FILE",
        "https://ghproxy.net/https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$FILE"
    )

    val latest = mutableStateOf<UpdateInfo?>(null)

    fun check(ctx: Context, currentVersionCode: Int) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val info = readLocal(ctx) ?: fetchFastestMirror(hasVpn(ctx))
            if (info != null && info.versionCode > currentVersionCode) {
                latest.value = info
            }
        }
    }

    /**
     * 用户环境检测：开着 VPN 时 GitHub 官方直连最快，没开时走国内镜像。
     * 两条链路都并发竞速，这里只决定「谁排前面、给谁更长超时」。
     */
    private fun hasVpn(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }.getOrDefault(false)

    /** 本地文件优先（可从网盘同步，离线也能更新）。 */
    private fun readLocal(ctx: Context): UpdateInfo? = runCatching {
        val f = File(ctx.getExternalFilesDir(null), FILE)
        if (!f.isFile) null else parse(f.readText())
    }.getOrNull()

    /** 并发打所有镜像，取**最先成功返回**的那个；VPN 时官方直连优先，否则镜像优先。 */
    private suspend fun fetchFastestMirror(vpn: Boolean): UpdateInfo? = coroutineScope {
        val ordered = if (vpn) MIRRORS else MIRRORS.drop(1) + MIRRORS.first()
        val ch = Channel<UpdateInfo?>(ordered.size)
        ordered.forEach { url ->
            launch {
                ch.send(runCatching { fetch(url) }.getOrNull())
            }
        }
        var best: UpdateInfo? = null
        repeat(ordered.size) {
            val r = ch.receive()
            if (best == null && r != null) best = r
        }
        best
    }

    private fun fetch(url: String): UpdateInfo? {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "IVAN-CINEMA")
            .get()
            .build()
        return MacCmsApi.CLIENT.newBuilder()
            .callTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .build()
            .newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null
                else parse(resp.body?.string() ?: return null)
            }
    }

    private fun parse(text: String): UpdateInfo? = runCatching {
        val o = JSONObject(text.trim())
        UpdateInfo(
            versionCode = o.optInt("versionCode", 0),
            versionName = o.optString("versionName", ""),
            url = o.optString("url", ""),
            notes = o.optString("notes", "")
        )
    }.getOrNull()
}
