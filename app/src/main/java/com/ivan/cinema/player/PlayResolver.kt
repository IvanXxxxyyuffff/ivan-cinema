package com.ivan.cinema.player

import com.ivan.cinema.data.MacCmsApi

/**
 * 播放地址解析：量子系 share 中间页 → 从 HTML 里提取真实 m3u8。
 * 直链（m3u8/mp4）原样返回。
 */
object PlayResolver {

    private val ABS_M3U8 = Regex("https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*")
    private val JSON_URL = Regex("\"url\"\\s*:\\s*\"(https?://[^\"]+)\"")
    private val REL_M3U8 = Regex("[\"'](/[^\"'\\s<>]*\\.m3u8[^\"'\\s<>]*)[\"']")

    fun resolve(input: String): String {
        var url = input.trim()
        if (url.contains(".m3u8") || url.contains(".mp4")) return url
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
