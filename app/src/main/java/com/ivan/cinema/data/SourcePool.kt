package com.ivan.cinema.data

import android.content.Context
import org.json.JSONArray
import java.io.File

object SourcePool {

    @Volatile
    private var cached: List<VodSource>? = null

    fun load(ctx: Context): List<VodSource> {
        cached?.let { return it }
        // 覆盖优先级：应用内部 files/ → 外部 files/（热更新）→ assets 内置。
        // 内部目录排最前是因为它不需要任何存储权限就能写入（adb run-as 可直接改），
        // 排查/取证时能换源而不用重打包；正式分发只用到 assets 那一路。
        val internal = File(ctx.filesDir, "sources.json")
        val external = File(ctx.getExternalFilesDir(null), "sources.json")
        val text: String = when {
            internal.isFile -> internal.readText()
            external.isFile -> external.readText()
            else -> ctx.assets.open("sources.json").bufferedReader().use { it.readText() }
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
