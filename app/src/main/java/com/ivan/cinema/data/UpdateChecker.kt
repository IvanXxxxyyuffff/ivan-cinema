package com.ivan.cinema.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * 镜像列表：官方 + 三个国内可达通道。
     *
     * ⚠️ **曾经把 jsdelivr（`cdn.jsdelivr.net/gh/...@main/update.json`）也放进来，这是错的。**
     * jsdelivr 对 `@main` 是强缓存，实测它一直返回 v1.0.9 那份旧清单；
     * 而这里是「并发竞速、谁快用谁」，jsdelivr 恰好最快（0.75s vs 官方 1.36s），
     * 于是每次都被它抢先命中，App 永远判定"已是最新版"——用户就是这么卡住的。
     * 教训：**可变清单不能走会强缓存的 CDN**。速度不是唯一标准，正确性优先。
     */
    private val MIRRORS = listOf(
        "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$FILE",
        "https://ghfast.top/https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$FILE",
        "https://gh-proxy.com/https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$FILE",
        "https://ghproxy.net/https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$FILE"
    )

    /** 首个结果到达后，再宽限这么久去等"版本号更高"的结果。 */
    private const val GRACE_MS = 1_500L

    val latest = mutableStateOf<UpdateInfo?>(null)

    /** 检查结果。界面必须能区分「确实没更新」和「根本没查成」——
     *  原来两者都是 latest==null，一律显示"最新版"，把网络失败伪装成了好消息。 */
    enum class Status { Idle, Checking, UpToDate, Found, Failed }

    val status = mutableStateOf(Status.Idle)
    /** 检查时用的当前版本号，供界面显示。 */
    val checkedFrom = mutableStateOf(0)

    fun check(ctx: Context, currentVersionCode: Int) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            status.value = Status.Checking
            checkedFrom.value = currentVersionCode
            val info = readLocal(ctx) ?: fetchFastestMirror(hasVpn(ctx))
            if (info == null) {
                status.value = Status.Failed
                return@launch
            }
            if (info.versionCode > currentVersionCode) {
                latest.value = info
                status.value = Status.Found
            } else {
                status.value = Status.UpToDate
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

    /**
     * 并发打所有镜像，**在宽限期内取「版本号最高」的那个**。
     *
     * 不是「谁快用谁」——那正是上面 jsdelivr 那个坑：最快的可能是一份被强缓存的旧清单。
     * 现在的策略是：第一个结果到了之后再多等 [GRACE_MS]，把这段时间内到达的结果里
     * 版本号最大的挑出来。代价是最多多等 1.5s（总时长仍受 6s 超时约束），
     * 换来的是对任何"缓存陈旧/代理脏数据"免疫。
     */
    private suspend fun fetchFastestMirror(vpn: Boolean): UpdateInfo? = coroutineScope {
        val ordered = if (vpn) MIRRORS else MIRRORS.drop(1) + MIRRORS.first()
        val ch = Channel<UpdateInfo?>(Channel.UNLIMITED)
        val jobs = ordered.map { url ->
            launch { ch.send(runCatching { fetch(url) }.getOrNull()) }
        }
        var best: UpdateInfo? = null
        var deadline = 0L
        for (i in ordered.indices) {
            val r = if (deadline == 0L) {
                ch.receive()
            } else {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0L) break
                withTimeoutOrNull(left) { ch.receive() } ?: break
            }
            if (r != null) {
                val cur = best
                if (cur == null || r.versionCode > cur.versionCode) best = r
                if (deadline == 0L) deadline = System.currentTimeMillis() + GRACE_MS
            }
        }
        jobs.forEach { it.cancel() }
        best
    }

    private fun fetch(url: String): UpdateInfo? {
        // 加时间戳绕开中间代理的缓存。可变清单必须带这个，
        // 否则一层 HTTP 缓存就能让所有人停在旧版本上（jsdelivr 那次就是）。
        val bust = if (url.contains('?')) "&t=" else "?t="
        val req = okhttp3.Request.Builder()
            .url("$url$bust${System.currentTimeMillis()}")
            .header("User-Agent", "IVAN-CINEMA")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
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
