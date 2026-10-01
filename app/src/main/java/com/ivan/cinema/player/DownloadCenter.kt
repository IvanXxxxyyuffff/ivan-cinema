package com.ivan.cinema.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.ivan.cinema.IVANApp
import java.io.File
import java.util.concurrent.Executors

@UnstableApi
object DownloadCenter {

    private var initialized = false

    fun ensureInit(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext as IVANApp
            val upstream = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 15) Chrome/124.0 Mobile Safari/537.36")
                .setAllowCrossProtocolRedirects(true)
            val manager = DownloadManager(
                app,
                androidx.media3.database.StandaloneDatabaseProvider(app),
                app.downloadCache,
                upstream,
                Executors.newSingleThreadExecutor()
            )
            manager.maxParallelDownloads = 3
            manager.resumeDownloads()
            managerRef = manager
            initialized = true
        }
    }

    @Volatile
    var managerRef: DownloadManager? = null
        private set

    fun add(context: Context, url: String, title: String) {
        ensureInit(context)
        val id = (title + "|" + url).hashCode().toString() + "|" + System.currentTimeMillis()
        val request = DownloadRequest.Builder(id, android.net.Uri.parse(url))
            .setData(title.toByteArray())
            .build()
        DownloadService.sendAddDownload(context, IVANDownloadService::class.java, request, false)
    }

    private val pool by lazy { Executors.newSingleThreadExecutor() }
}
