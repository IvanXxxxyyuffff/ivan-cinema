package com.ivan.cinema.player

import com.ivan.cinema.data.MacCmsApi
import kotlinx.coroutines.launch

/**
 * 播放地址解析：量子系 share 中间页 → 从 HTML 里提取真实 m3u8。
 * 直链（m3u8/mp4）原样返回。
 */
object PlayResolver {

    private val ABS_M3U8 = Regex("https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*")
    private val JSON_URL = Regex("\"url\"\\s*:\\s*\"(https?://[^\"]+)\"")
    private val REL_M3U8 = Regex("[\"'](/[^\"'\\s<>]*\\.m3u8[^\"'\\s<>]*)[\"']")

    /**
     * 解析结果缓存。
     *
     * 为什么必须有：采集源给的播放地址大多**不是 m3u8 而是一个网页**
     * （`play.xluuss.com/play/xxx` 这种），resolve 要再发 1~2 次 HTTP 把 HTML 抓回来
     * 挖真地址 —— 每次最长 10s，串行，而且**每次播放都重做一遍**。
     * 这是"点播放要等好几秒"的主因，不是聚合慢。
     *
     * 详情页会提前预热第一条线路，所以进播放页时通常已经命中。
     * TTL 30 分钟：m3u8 地址一般带时效签名，缓存太久会拿到过期的。
     */
    private const val TTL_MS = 30 * 60 * 1000L
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    fun cached(input: String): String? {
        val e = cache[input.trim()] ?: return null
        return if (System.currentTimeMillis() - e.second < TTL_MS) e.first else null
    }

    /**
     * 像 [cached]，但只在缓存里是**真地址**时才返回。
     *
     * 为什么不能直接用 [cached] 判断「备线能不能顶」：`resolve()` 解析不出时会把**原文**
     * 也写进缓存（TTL 减半），所以死线路在缓存里同样 "cached() != null"。
     * 两条线路都死时，A 认为 B 可用、B 认为 A 可用，就会来回切换成死循环（评审实测指出的 P0）。
     */
    fun cachedGenuine(input: String): String? {
        val key = input.trim()
        val v = cached(key) ?: return null
        val playable = v.contains(".m3u8") || v.contains(".mp4")
        return if (playable || v != key) v else null
    }

    /**
     * 后台预热：在详情页就把第一条线路第一集解析好。
     * 用户在详情页看简介、挑集数的那几秒正好把这一跳藏掉。
     */
    fun warm(input: String) {
        val key = input.trim()
        if (key.isEmpty() || cached(key) != null) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { resolve(key) }
        }
    }

    fun resolve(input: String): String {
        val key = input.trim()
        cached(key)?.let { return it }

        var url = key
        if (url.contains(".m3u8") || url.contains(".mp4")) {
            cache[key] = url to System.currentTimeMillis()
            return url
        }
        var current = url
        run found@{
            repeat(2) {
                val text = try {
                    fetch(current)
                } catch (_: Exception) {
                    return@repeat
                }
                if (text == null || !text.contains("http")) return@repeat
                if (text.contains("#EXTM3U")) {
                    url = current
                    return@found
                }
                val abs = ABS_M3U8.find(text)
                if (abs != null) {
                    url = abs.value
                    return@found
                }
                val json = JSON_URL.find(text)
                if (json != null) {
                    val candidate = json.groupValues[1]
                    if (candidate.contains(".m3u8") || candidate.contains(".mp4")) {
                        url = candidate
                        return@found
                    }
                }
                val rel = REL_M3U8.find(text)
                if (rel != null) {
                    url = current.substringBeforeLast('/') + rel.groupValues[1]
                    return@found
                }
            }
        }
        // 解析不出真地址时也缓存"原文"，避免同一集反复打两次没结果的 HTTP；
        // 但 TTL 减半，给源站改地址留出恢复窗口
        cache[key] = url to (System.currentTimeMillis() - TTL_MS / 2)
        return url
    }

    private fun fetch(url: String): String? {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) Chrome/124.0 Mobile Safari/537.36")
            .header("Referer", url)
            .get()
            .build()
        return MacCmsApi.CLIENT.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }
}
