package com.ivan.cinema.data

import android.content.Context
import org.json.JSONArray
import java.io.File

object SourcePool {

    @Volatile
    private var cached: List<VodSource>? = null

    fun load(ctx: Context): List<VodSource> {
        cached?.let { return it }
        // 优先外部热更新文件，缺失则回退 assets 内置
        val external = File(ctx.getExternalFilesDir(null), "sources.json")
        val text: String = if (external.isFile) {
            external.readText()
        } else {
            ctx.assets.open("sources.json").bufferedReader().use { it.readText() }
        }
        val list = parse(text)
        cached = list
        return list
    }

    fun invalidate() {
        cached = null
    }

    internal fun parse(text: String): List<VodSource> {
        // 外部 sources.json 可能被写坏 —— 不能让它崩掉首页
        return runCatching {
            val arr = JSONArray(text)
            val out = ArrayList<VodSource>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val api = o.optString("api").trim().trimEnd('/')
                val name = o.optString("name").trim()
                if (api.isNotEmpty() && name.isNotEmpty()) {
                    out.add(VodSource(name, api))
                }
            }
            out
        }.getOrDefault(emptyList())
    }
}
