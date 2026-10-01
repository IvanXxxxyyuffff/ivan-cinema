package com.ivan.cinema.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class MacCmsApi(private val source: VodSource) {

    private fun json(path: String): JSONObject? {
        val url = if (path.contains("?")) source.api + path else source.api + "?$path"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .get()
            .build()
        return try {
            CLIENT.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                JSONObject(body.trim().trimStart('\uFEFF'))
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * MacCMS 分类/筛选列表。参数名按 MacCMS v10 规范：
     *   t=分类ID  by=排序(time|hits|score)  area=地区  class=剧情  year=年份  lang=语言
     * 用错参数名会被源忽略并返回全库（tid 那次教训）。
     */
    fun list(
        tid: String,
        page: Int,
        by: String? = null,
        area: String? = null,
        cls: String? = null,
        year: String? = null,
        lang: String? = null
    ): List<VodItem> {
        val sb = StringBuilder("ac=detail&t=$tid&pg=$page")
        if (!by.isNullOrEmpty()) sb.append("&by=$by")
        if (!area.isNullOrEmpty()) sb.append("&area=${urlEncode(area)}")
        if (!cls.isNullOrEmpty()) sb.append("&class=${urlEncode(cls)}")
        if (!year.isNullOrEmpty()) sb.append("&year=$year")
        if (!lang.isNullOrEmpty()) sb.append("&lang=${urlEncode(lang)}")
        val o = json(sb.toString()) ?: return emptyList()
        return parseList(o)
    }

    fun classes(): List<Pair<String, String>> {
        val o = json("ac=list&pg=1") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        val arr = o.optJSONArray("class") ?: return out
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            out.add(c.optString("type_id") to c.optString("type_name"))
        }
        return out
    }

    /** ac=detail&wd= 搜索，返回该源全部命中 */
    fun search(wd: String): List<VodItem> {
        val o = json("ac=detail&wd=${urlEncode(wd)}") ?: return emptyList()
        return parseList(o)
    }

    fun detail(vodId: String): VodDetail? {
        val o = json("ac=detail&ids=$vodId") ?: return null
        val arr = o.optJSONArray("list") ?: return null
        if (arr.length() == 0) return null
        val v = arr.getJSONObject(0)
        val playFrom = v.optString("vod_play_from")
        val playUrl = v.optString("vod_play_url")
        val lines = ArrayList<PlayLine>()
        val fromParts = playFrom.split("\\$\\$\\$".toRegex())
        val urlParts = playUrl.split("\\$\\$\\$".toRegex())
        for (i in urlParts.indices) {
            val lineName = if (i < fromParts.size) fromParts[i].trim() else "线路${i + 1}"
            val episodes = ArrayList<Episode>()
            for (ep in urlParts[i].split("#")) {
                if (ep.isBlank()) continue
                val idx = ep.indexOf('$')
                if (idx > 0) {
                    val name = ep.substring(0, idx).trim()
                    val url = ep.substring(idx + 1).trim()
                    if (url.startsWith("http")) episodes.add(Episode(name, url))
                }
            }
            if (episodes.isNotEmpty()) lines.add(PlayLine(lineName, episodes))
        }
        return VodDetail(
            source = source,
            vodId = v.optString("vod_id"),
            name = v.optString("vod_name").trim(),
            year = v.optString("vod_year").trim(),
            pic = v.optString("vod_pic").trim(),
            remarks = v.optString("vod_remarks").trim(),
            typeName = v.optString("type_name").trim(),
            actor = v.optString("vod_actor").trim(),
            director = v.optString("vod_director").trim(),
            score = v.optString("vod_score").trim(),
            area = v.optString("vod_area").trim(),
            lang = v.optString("vod_lang").trim(),
            content = stripTags(v.optString("vod_content").trim()),
            lines = lines
        )
    }

    private fun parseList(o: JSONObject): List<VodItem> {
        val arr = o.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<VodItem>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            out.add(
                VodItem(
                    source = source,
                    vodId = v.optString("vod_id"),
                    name = v.optString("vod_name").trim(),
                    year = v.optString("vod_year").trim(),
                    pic = v.optString("vod_pic").trim(),
                    remarks = v.optString("vod_remarks").trim(),
                    typeId = v.optString("type_id").trim(),
                    blurb = firstSentence(v.optString("vod_content"))
                )
            )
        }
        return out
    }

    /** 取简介第一句（卡片副标题用），最长 24 字。 */
    private fun firstSentence(raw: String): String {
        val t = stripTags(raw)
        if (t.isEmpty()) return ""
        val cut = t.indexOfFirst { it == '。' || it == '！' || it == '？' || it == '.' || it == '!' || it == '?' }
        val s = if (cut in 1..40) t.substring(0, cut) else t
        return if (s.length > 24) s.take(24) + "…" else s
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"

        val CLIENT: OkHttpClient by lazy {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }

        private fun urlEncode(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8")

        private fun stripTags(s: String): String =
            s.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim()
    }
}
