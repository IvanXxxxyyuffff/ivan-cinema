package com.ivan.cinema.data

import org.json.JSONObject

/**
 * Supabase 接入配置（自用）。
 *
 * 两种填法（二选一，assets 覆盖优先）：
 *   1) 直接改下面两个常量（最快）；
 *   2) 放 app/src/main/assets/config.json：{"supabaseUrl":"...","supabaseAnon":"..."}（可随网盘分发，不进源码）。
 *
 * 只要两个值都非空，[isConfigured] 即为 true，Account 会自动切到 Supabase 登录/同步。
 */
object SupabaseConfig {

    /** Project URL（不含 /rest/v1 后缀；代码内部自己拼 /auth/v1 与 /rest/v1） */
    const val URL = "https://ooyxaaabfehhknwljbnh.supabase.co"

    /** Publishable key（新版命名，等价于旧的 anon public key；绝不要用 sb_secret_ 开头的） */
    const val ANON_KEY = "sb_publishable_g44SsX-43Rd2tvKROOBTEw_7hnuPs_R"

    private const val ASSET = "config.json"

    @Volatile private var assetUrl: String? = null
    @Volatile private var assetKey: String? = null
    @Volatile private var loaded = false

    /** 懒加载 assets/config.json（不存在或解析失败都静默回退到常量）。 */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            val ctx = com.ivan.cinema.IVANApp.app
            val text = ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
            val o = JSONObject(text.trim().trimStart('\uFEFF'))
            assetUrl = o.optString("supabaseUrl", "").trim().ifEmpty { null }
            assetKey = o.optString("supabaseAnon", "").trim().ifEmpty { null }
        }
    }

    /** 生效的 Project URL（assets 覆盖优先，其次常量）。 */
    fun url(): String {
        ensureLoaded()
        return (assetUrl ?: URL).trim()
    }

    /** 生效的 anon key。 */
    fun anonKey(): String {
        ensureLoaded()
        return (assetKey ?: ANON_KEY).trim()
    }

    /** URL 与 anon key 都非空才为 true。 */
    fun isConfigured(): Boolean = url().isNotEmpty() && anonKey().isNotEmpty()
}
