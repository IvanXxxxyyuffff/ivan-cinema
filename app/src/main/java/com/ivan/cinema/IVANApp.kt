package com.ivan.cinema

import android.app.Application
import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class IVANApp : Application(), ImageLoaderFactory {

    lateinit var downloadCache: SimpleCache
        private set

    override fun onCreate() {
        super.onCreate()
        app = this
        val dir = File(cacheDir, "exoplayer_cache")
        downloadCache = SimpleCache(
            dir,
            LeastRecentlyUsedCacheEvictor(4L * 1024 * 1024 * 1024),
            StandaloneDatabaseProvider(this)
        )
        // 启动时静默核验源（后台，不阻塞首屏；24h 内不重复）
        com.ivan.cinema.data.SourceHealth.init(this)
        // 源清单自动更新：仓库里的 sources_manifest.json 有新版本就写进 filesDir，
        // 而 SourcePool 本来就优先读 filesDir —— 读取侧一行都不用改。
        // 必须放在 SourceHealth.init 之后：更新成功时 check() 内部会再调一次
        // SourceHealth.refresh，用新源重新探测排序。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { com.ivan.cinema.data.SourceUpdater.check(this@IVANApp) }
        }
        // 本地账号 + 静默检查 APP 更新
        com.ivan.cinema.data.Account.init(this)
        // 崩溃兜底：装未捕获异常处理器，本地落盘、下次启动上报，并清理过期记录。
        // 必须放在 Account.init 之后，这样恢复出来的 Supabase 会话能直接用于补报。
        com.ivan.cinema.data.ErrorReporter.install(this)
        com.ivan.cinema.data.UpdateChecker.check(this, BuildConfig.VERSION_CODE)
        // 追剧更新：每天检查一次（KEEP 策略，重复启动不会重置周期）
        com.ivan.cinema.data.FollowWorker.schedule(this)
        // 云端登录态：启动即同步一次，换设备后观看记录自动回到本机
        if (com.ivan.cinema.data.Account.isCloudLoggedIn()) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { com.ivan.cinema.data.Account.syncWatches(this@IVANApp) }
            }
        }
    }

    /** 封面加载全局单例：磁盘缓存 + 150ms 交叉淡入 + 高并发连接调度 */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(150)
            .callFactory(imageClient)
            // 关掉硬件位图：硬件 Bitmap 无法被软件 Canvas 绘制，
            // 会让 captureToImage() 截出来的海报全是空白，同时也影响 App 自身的模糊处理
            .allowHardware(false)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "coil_images"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.30)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()

    /** 图床客户端：大并发 + 每 host 8 路 + 连接池复用（冷 DNS 下封面并行铺开）。 */
    private val imageClient: okhttp3.OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 8
        }
        okhttp3.OkHttpClient.Builder()
            .dispatcher(dispatcher)
            // 图片请求统一补上 UA 和 Referer，详见 imageHeaderInterceptor。
            .addInterceptor(imageHeaderInterceptor)
            // 超时故意收紧：封面是"可失败"资源，宁可快速失败让 error 槽显示占位砖，
            // 也不要让一张死图把海报卡按住十几秒仍是空白盒子。
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            // 兜底总时长：连接 + 读取 + 重定向全算在内，单个请求绝不超过 8 秒。
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(32, 5, java.util.concurrent.TimeUnit.MINUTES))
            .build()
    }

    /**
     * 图片请求头拦截器。
     *
     * 国内图床（尤其 MacCMS 采集站的 pic 域名）普遍开防盗链：请求不带 Referer 会被 403，
     * 于是封面全变空白灰盒，而 API 因为 [com.ivan.cinema.data.MacCmsApi] 自带 UA 反而正常。
     * 这里给每个图片请求补上移动端 UA 和"取自图片自身域名"的 Referer，
     * 这是绕开防盗链的标准做法：图片在 a.com 上，就用 https://a.com/ 当 Referer。
     * 每个图片请求都会走这里，所以只做必要的字符串拼接和一次 Builder，尽量不额外分配。
     */
    private val imageHeaderInterceptor = okhttp3.Interceptor { chain ->
        val req = chain.request()
        val u = req.url
        // 用图片自己的源站做 Referer：跨域图床也能过防盗链校验。
        val referer = u.scheme + "://" + u.host + "/"
        chain.proceed(
            req.newBuilder()
                .header("User-Agent", IMAGE_UA)
                .header("Referer", referer)
                .build()
        )
    }

    companion object {
        lateinit var app: IVANApp
            private set
        fun ctx(): Context = app

        /** 与 MacCmsApi 同款移动端 UA：多数图床认这个，且和 API 请求保持一致，不易被判定为爬虫。 */
        private const val IMAGE_UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
    }
}

/** 预热条数上限：只提前一点点，绝不允许把带宽喂满。 */
private const val PREFETCH_MAX = 12

/**
 * 海报预热：列表滚动前把"即将进入视口"的封面塞进 Coil 队列，
 * 这样卡片真正出现时图已经在内存/磁盘里，少一段空白。
 *
 * 刻意封顶 [PREFETCH_MAX]（12）条并去重：预热只是提前量，
 * 一旦不设上限，快速滑动会瞬间发起几十上百个请求把带宽占满，
 * 反而拖慢用户当下真正在看的封面。
 *
 * 全程 runCatching：预热属于"锦上添花"，任何异常（含空 loader）都吞掉，绝不向上抛。
 * 空白/重复 URL 直接跳过。
 */
fun prefetch(ctx: Context, urls: List<String>) {
    runCatching {
        val loader = ctx.imageLoader
        val seen = HashSet<String>(urls.size)
        var queued = 0
        for (u in urls) {
            if (queued >= PREFETCH_MAX) break
            if (u.isBlank()) continue
            if (!seen.add(u)) continue
            loader.enqueue(ImageRequest.Builder(ctx).data(u).build())
            queued++
        }
    }
}
