package com.ivan.cinema.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import java.security.MessageDigest

data class AccountState(
    val username: String,
    val isSvip: Boolean
)

/**
 * 本地账号（自用，无后端）：
 *   用户名 + 密码哈希（加盐 SHA-256）落在本机 SharedPreferences。
 *   用途是「身份位」——谁在用这台设备、是不是 SVIP（本地开关，自用自己说了算）。
 */
object Account {

    private const val PREF = "ivan_account"
    private const val KEY_USER = "user"
    private const val KEY_HASH = "hash"
    private const val KEY_SALT = "salt"
    private const val KEY_SVIP = "svip"

    val state = mutableStateOf<AccountState?>(null)

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val u = p.getString(KEY_USER, null)
        state.value = if (u.isNullOrEmpty()) null else AccountState(u, p.getBoolean(KEY_SVIP, false))
    }

    fun register(ctx: Context, user: String, pass: String): String? {
        val u = user.trim()
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
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val u = p.getString(KEY_USER, null) ?: return "还没有注册过账号"
        if (u != user.trim()) return "用户名不对"
        val salt = p.getString(KEY_SALT, "") ?: ""
        if (hash(salt + pass) != p.getString(KEY_HASH, "")) return "密码不对"
        state.value = AccountState(u, p.getBoolean(KEY_SVIP, false))
        return null
    }

    fun logout(ctx: Context) {
        // 退出只清登录态，保留注册信息（下次直接登录）
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

    private fun hash(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
