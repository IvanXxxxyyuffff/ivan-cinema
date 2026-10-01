package com.ivan.cinema.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.ivan.cinema.db.AppDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest

data class AccountState(
    val username: String,
    val isSvip: Boolean
)

/**
 * 账号（身份位）。
 *
 *   - 默认（未配置 Supabase）：本地账号 —— 用户名 + 加盐 SHA-256 密码落 SharedPreferences。
 *   - 配置了 Supabase（见 [SupabaseConfig]）：register/login 走 Supabase Auth，
 *     用户名输入框填邮箱；登录成功后 [supabaseSession] 保存 (accessToken, userId)，
 *     观看记录可通过 [syncWatches] 与云端双向合并。
 *
 * [state] 的语义始终不变：UI（LoginScreen / SettingsScreen）靠它显示身份位。
 */
object Account {

    private const val PREF = "ivan_account"
    private const val KEY_USER = "user"
    private const val KEY_HASH = "hash"
    private const val KEY_SALT = "salt"
    private const val KEY_SVIP = "svip"

    // Supabase 会话（与本地账号分开存，互不覆盖）
    private const val KEY_SB_USER = "sb_user"
    private const val KEY_SB_TOKEN = "sb_token"
    private const val KEY_SB_UID = "sb_uid"

    val state = mutableStateOf<AccountState?>(null)

    /** Supabase 会话：accessToken to userId；未登录为 null。 */
    val supabaseSession = mutableStateOf<Pair<String, String>?>(null)

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        // 优先恢复 Supabase 会话（若存在）
        val sbUser = p.getString(KEY_SB_USER, null)
        val sbToken = p.getString(KEY_SB_TOKEN, null)
        val sbUid = p.getString(KEY_SB_UID, null)
        if (!sbUser.isNullOrEmpty() && !sbToken.isNullOrEmpty() && !sbUid.isNullOrEmpty()) {
            supabaseSession.value = sbToken to sbUid
            state.value = AccountState(sbUser, p.getBoolean(KEY_SVIP, true))
            return
        }
        val u = p.getString(KEY_USER, null)
        state.value = if (u.isNullOrEmpty()) null else AccountState(u, p.getBoolean(KEY_SVIP, false))
    }

    fun register(ctx: Context, user: String, pass: String): String? {
        val u = user.trim()

        if (SupabaseConfig.isConfigured()) {
            if (!isEmail(u)) return "Supabase 模式下用户名需填邮箱"
            if (pass.length < 6) return "密码至少 6 位"
            val up = runBlocking { SupabaseClient.signUp(u, pass) }
            if (up.isFailure) return up.exceptionOrNull()?.message ?: "注册失败"
            // 注册后立刻登录拿会话（若开启了邮箱验证，这里会返回可读提示）
            val si = runBlocking { SupabaseClient.signIn(u, pass) }
            val pair = si.getOrElse { e ->
                return e.message ?: "注册成功，但自动登录失败（可能需先完成邮箱验证）"
            }
            onSupabaseLogin(ctx, u, pair)
            return null
        }

        if (u.length < 2) return "用户名至少 2 个字符"
        if (pass.length < 4) return "密码至少 4 位"
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (p.getString(KEY_USER, null) == u) return "这个用户名已经注册过了"
        val salt = java.util.UUID.randomUUID().toString().take(8)
        p.edit()
            .putString(KEY_USER, u)
            .putString(KEY_SALT, salt)
            .putString(KEY_HASH, hash(salt + pass))
            .putBoolean(KEY_SVIP, true)   // 自用版：注册即 SVIP
            .apply()
        state.value = AccountState(u, true)
        return null
    }

    fun login(ctx: Context, user: String, pass: String): String? {
        val u = user.trim()

        if (SupabaseConfig.isConfigured()) {
            if (!isEmail(u)) return "Supabase 模式下用户名需填邮箱"
            val si = runBlocking { SupabaseClient.signIn(u, pass) }
            val pair = si.getOrElse { e -> return e.message ?: "登录失败" }
            onSupabaseLogin(ctx, u, pair)
            return null
        }

        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val existing = p.getString(KEY_USER, null) ?: return "还没有注册过账号"
        if (existing != u) return "用户名不对"
        val salt = p.getString(KEY_SALT, "") ?: ""
        if (hash(salt + pass) != p.getString(KEY_HASH, "")) return "密码不对"
        state.value = AccountState(existing, p.getBoolean(KEY_SVIP, false))
        return null
    }

    fun logout(ctx: Context) {
        // 退出只清登录态，保留注册信息（下次直接登录）
        supabaseSession.value = null
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .remove(KEY_SB_USER)
            .remove(KEY_SB_TOKEN)
            .remove(KEY_SB_UID)
            .apply()
        state.value = null
    }

    /** SVIP 开关（自用：本地直接切）。 */
    fun setSvip(ctx: Context, on: Boolean) {
        val cur = state.value ?: return
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SVIP, on)
            .apply()
        state.value = cur.copy(isSvip = on)
    }

    // ─────────────────────────── Supabase 同步 ───────────────────────────

    /**
     * 观看记录双向同步：先把本地 Room 记录 upsert 到云端，再把云端记录拉回来合并。
     * 冲突（同一 vodKey）以 updatedAt 大的为准。失败静默，不影响使用。
     */
    suspend fun syncWatches(ctx: Context) {
        val (token, userId) = supabaseSession.value ?: return
        if (!SupabaseConfig.isConfigured()) return
        val dao = AppDb.get(ctx).watchDao()

        // 1) 本地 → 云端
        val local = runCatching { dao.recent().first() }.getOrDefault(emptyList())
        for (e in local) {
            SupabaseClient.upsertWatch(token, userId, e)
        }

        // 2) 云端 → 本地（按 updatedAt 取新的）
        val remote = SupabaseClient.pullWatches(token, userId)
        for (r in remote) {
            if (r.vodKey.isEmpty()) continue
            val cur = runCatching { dao.get(r.vodKey) }.getOrNull()
            if (cur == null || r.updatedAt > cur.updatedAt) {
                runCatching { dao.upsert(r) }
            }
        }
    }

    /** Supabase 登录成功：存会话 + 身份位，并后台触发一次同步。 */
    private fun onSupabaseLogin(ctx: Context, user: String, pair: Pair<String, String>) {
        supabaseSession.value = pair
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_SB_USER, user)
            .putString(KEY_SB_TOKEN, pair.first)
            .putString(KEY_SB_UID, pair.second)
            .putBoolean(KEY_SVIP, true)   // 自用版：登录即 SVIP
            .apply()
        state.value = AccountState(user, true)
        CoroutineScope(Dispatchers.IO).launch { runCatching { syncWatches(ctx) } }
    }

    private val EMAIL_RE = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    private fun isEmail(s: String): Boolean = EMAIL_RE.matches(s)

    private fun hash(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
