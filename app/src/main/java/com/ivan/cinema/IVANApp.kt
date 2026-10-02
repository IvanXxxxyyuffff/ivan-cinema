package com.ivan.cinema

import android.app.Application
import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
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
        // 本地账号 + 静默检查 APP 更新
        com.ivan.cinema.data.Account.init(this)
        com.ivan.cinema.data.UpdateChecker.check(this, BuildConfig.VERSION_CODE)
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
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(32, 5, java.util.concurrent.TimeUnit.MINUTES))
            .build()
    }

    companion object {
        lateinit var app: IVANApp
            private set
        fun ctx(): Context = app
    }
}
