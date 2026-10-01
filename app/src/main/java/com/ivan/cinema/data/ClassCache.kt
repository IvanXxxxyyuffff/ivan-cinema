package com.ivan.cinema.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 分类映射缓存：首次启动探测后落盘，之后启动直接用缓存拉列表（一轮网络即出封面），
 * 后台仍会刷新缓存。
 */
object ClassCache {

    private fun file(ctx: Context, tab: String) =
        File(ctx.filesDir, "class_cache_$tab.json")

    fun read(ctx: Context, tab: String): Map<String, String> {
        // filesDir 热更新优先；其次 assets 出厂预置（首启零探测直接出封面）
        val f = file(ctx, tab)
        if (f.isFile) {
            return parse(f.readText())
        }
        return try {
            parse(ctx.assets.open("preset_class_$tab.json").bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun parse(text: String): Map<String, String> {
        return try {
            val o = JSONObject(text)
            val out = HashMap<String, String>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = o.getString(k)
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun write(ctx: Context, tab: String, map: Map<String, String>) {
        try {
            val o = JSONObject()
            for ((k, v) in map) o.put(k, v)
            val f = file(ctx, tab)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (_: Exception) {
        }
    }
}
