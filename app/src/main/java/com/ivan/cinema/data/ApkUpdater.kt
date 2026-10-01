package com.ivan.cinema.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** 自更新状态机：UI 只读 [ApkUpdater.state] 决定按钮长什么样。 */
sealed interface ApkState {
    data object Idle : ApkState

    data class Downloading(
        val progress: Float,
        val received: Long,
        val total: Long,
        val mirror: String
    ) : ApkState

    data class Ready(val file: File) : ApkState

    data class Failed(val message: String) : ApkState
}

/**
 * 应用内自更新：下载 APK → 交给系统安装器，**全程不跳浏览器**。
 *
 *   - 下载：update.json 里的 url 是 github.com 直链，国内多半连不上，
 *     所以自动改写成 ghfast/gh-proxy/ghproxy 三个加速前缀，按顺序重试；
 *     开了 VPN 时官方直链排第一。
 *   - 安装：APK 落在 filesDir/apk/，经 FileProvider 以 content:// 交给
 *     系统包安装器（不需要任何存储权限）。
 *   - Android 8+ 需用户在系统设置里允许「安装未知来源应用」，[canInstall]
 *     返回 false 时 [install] 会自动把用户送到那个开关页。
 */
object ApkUpdater {

    val state = mutableStateOf<ApkState>(ApkState.Idle)

    private var job: Job? = null

    /** GitHub 加速前缀：把完整原始 URL 直接拼在后面即可。 */
    private val PROXIES = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/"
    )

    /**
     * 下载专用客户端：**正常校验证书**。源站接口那边用的是 trust-all，
     * 但安装包绝不能容忍中间人掉包，所以这里单独建一个。
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    private fun apkDir(ctx: Context): File = File(ctx.filesDir, "apk").apply { mkdirs() }

    // ─────────────────────────────── 下载 ───────────────────────────────

    fun download(ctx: Context, url: String, versionName: String) {
        if (job?.isActive == true) return
        if (url.isEmpty()) {
            state.value = ApkState.Failed("更新地址为空")
            return
        }
        val app = ctx.applicationContext
        state.value = ApkState.Downloading(0f, 0L, 0L, "")
        job = CoroutineScope(Dispatchers.IO).launch {
            val target = File(apkDir(app), "ivan-cinema-$versionName.apk")
            target.delete()
            var lastErr = "下载失败"
            for (mirror in mirrors(url, hasVpn(app))) {
                try {
                    fetchTo(mirror, target)
                    clearOld(app, keep = target)
                    state.value = ApkState.Ready(target)
                    return@launch
                } catch (ce: CancellationException) {
                    target.delete()
                    throw ce
                } catch (t: Throwable) {
                    lastErr = t.message ?: "下载失败"
                    target.delete()
                }
            }
            state.value = ApkState.Failed(lastErr)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        state.value = ApkState.Idle
    }

    /** 清掉上一版残留的安装包（保留 [keep]）。 */
    private fun clearOld(ctx: Context, keep: File?) {
        runCatching {
            apkDir(ctx).listFiles()?.forEach { if (it.absolutePath != keep?.absolutePath) it.delete() }
        }
    }

    /** 流式下载到 [target]；边下边把进度写进 [state]（UI 每 ~120ms 刷新一次）。 */
    private suspend fun fetchTo(url: String, target: File): Unit = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "IVAN-CINEMA")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("空响应")
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    var lastEmit = 0L
                    while (isActive) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        got += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 120) {
                            lastEmit = now
                            state.value = ApkState.Downloading(
                                progress = if (total > 0) got.toFloat() / total else 0f,
                                received = got,
                                total = total,
                                mirror = url
                            )
                        }
                    }
                    out.flush()
                }
            }
        }
        if (!isActive) throw CancellationException()
        if (target.length() <= 0L) error("下载内容为空")
    }

    /**
     * 候选下载地址：VPN 时官方直链优先，否则镜像优先（国内镜像比 raw 快得多）。
     * 官方直链始终保留在列表末尾兜底。
     */
    private fun mirrors(url: String, vpn: Boolean): List<String> {
        val proxied = PROXIES.map { it + url }
        return if (vpn) listOf(url) + proxied else proxied + url
    }

    private fun hasVpn(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }.getOrDefault(false)

    // ─────────────────────────────── 安装 ───────────────────────────────

    /** 是否已被允许安装未知来源应用（minSdk 26，API 一定存在）。 */
    fun canInstall(ctx: Context): Boolean =
        runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrDefault(true)

    /** 把用户送到本应用的「安装未知应用」开关页。 */
    fun requestInstallPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 拉起系统安装器。返回 false 表示没装成 —— 要么文件不在，
     * 要么缺「安装未知来源」授权（此时已自动跳设置页）。
     */
    fun install(ctx: Context, file: File): Boolean {
        if (!file.isFile) return false
        if (!canInstall(ctx)) {
            requestInstallPermission(ctx)
            return false
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }
            )
            true
        }.getOrDefault(false)
    }
}
