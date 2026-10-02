package com.ivan.cinema.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.ivan.cinema.db.AppDb
import com.ivan.cinema.db.WatchEntry
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

    /** 进度上云的最小间隔：播放中每 5s 写一次本地，上云按这个节流。 */
    private const val PUSH_MIN_INTERVAL_MS = 60_000L

    /** 上次上云时间（毫秒）；播放线程与 IO 线程都会读写。 */
    @Volatile private var lastPushAt = 0L

    val state = mutableStateOf<AccountState?>(null)

    /** Supabase 会话：accessToken to userId；未登录为 null。 */
    val supabaseSession = mutableStateOf<Pair<String, String>?>(null)

    /**
     * 未登录（本地账号 / 未配置 Supabase）时观看记录归属的「用户」。
     * 与 AppDb MIGRATION_3_4 里旧数据回填的字面量保持一致。
     */
    const val LOCAL_USER = "local"

    /** 当前观看记录归属：登录后是 Supabase uid，否则 [LOCAL_USER]。 */
    fun currentUserId(): String = supabaseSession.value?.second ?: LOCAL_USER

    /** 给一条观看记录盖上当前用户 id（写本地库之前调用，避免串号）。 */
    fun stamp(entry: WatchEntry): WatchEntry = entry.copy(userId = currentUserId())

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
        validateUsername(u)?.let { return it }
        if (pass.length < 6) return "密码至少 6 位"

        if (SupabaseConfig.isConfigured()) {
            val email = syntheticEmail(u)
            val up = runBlocking { SupabaseClient.signUp(email, pass, u) }
            if (up.isFailure) return friendly(up.exceptionOrNull()?.message) ?: "注册失败"
            // 注册后立刻登录拿会话（若开启了邮箱验证，这里会返回可读提示）
            val si = runBlocking { SupabaseClient.signIn(email, pass) }
            val pair = si.getOrElse { e ->
                return friendly(e.message)
                    ?: "注册成功，但自动登录失败（可能需先在 Supabase 关闭邮箱验证）"
            }
            // 身份位存用户名，不存合成邮箱
            onSupabaseLogin(ctx, u, pair)
            return null
        }

        if (u.length < 2) return "用户名至少 2 个字符"
        if (pass.length < 4) return "密码至少 4 位"
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (p.getString(KEY_USER, null).equals(u, ignoreCase = true)) return "这个用户名已经注册过了"
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
        if (u.isEmpty()) return "请输入用户名"

        if (SupabaseConfig.isConfigured()) {
            // 老账号是用真实邮箱注册的：输入里带 @ 就按邮箱原样登录，避免升级后登不进
            val email = if (u.contains('@')) u.lowercase() else syntheticEmail(u)
            val si = runBlocking { SupabaseClient.signIn(email, pass) }
            val pair = si.getOrElse { e -> return friendly(e.message) ?: "登录失败" }
            onSupabaseLogin(ctx, u, pair)
            return null
        }

        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val existing = p.getString(KEY_USER, null) ?: return "还没有注册过账号"
        if (!existing.equals(u, ignoreCase = true)) return "用户名不对"
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
     *
     * 多账号隔离：本地表是设备级的（主键只有 vodKey），所以上传只挑属于**当前用户**
     * 的行，拉回的云端行也盖上当前 userId —— 否则一台设备上的多个账号会把彼此的
     * 观看记录混进同一张本地表。
     */
    suspend fun syncWatches(ctx: Context) {
        val (token, userId) = supabaseSession.value ?: return
        if (!SupabaseConfig.isConfigured()) return
        val dao = AppDb.get(ctx).watchDao()

        // 1) 本地 → 云端：只上传属于当前用户的记录
        val local = runCatching { dao.recentFor(userId).first() }.getOrDefault(emptyList())
        for (e in local) {
            SupabaseClient.upsertWatch(token, userId, e)
        }

        // 2) 云端 → 本地：只和当前用户自己的行比新旧，落库时盖上当前 userId
        val remote = SupabaseClient.pullWatches(token, userId)
        for (r in remote) {
            if (r.vodKey.isEmpty()) continue
            val cur = runCatching { dao.getFor(userId, r.vodKey) }.getOrNull()
            if (cur == null || r.updatedAt > cur.updatedAt) {
                runCatching { dao.upsert(r.copy(userId = userId)) }
            }
        }
        lastPushAt = System.currentTimeMillis()
    }

    /**
     * 播放进度单条上云。
     *
     * 播放中每 5 秒就会写一次本地进度，如果每次都打网络会太吵 —— 所以默认
     * 节流 [minIntervalMs]（60 秒）内只推一条；退出播放页时用 force = true
     * 兜底推最后一条，保证「看到哪」不丢。
     */
    fun pushWatch(ctx: Context, entry: WatchEntry, force: Boolean = false) {
        val (token, userId) = supabaseSession.value ?: return
        if (!SupabaseConfig.isConfigured()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPushAt < PUSH_MIN_INTERVAL_MS) return
        lastPushAt = now
        // 盖上当前 userId 再上传，保证云端 user_id 与本地归属一致
        val stamped = entry.copy(userId = userId)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { SupabaseClient.upsertWatch(token, userId, stamped) }
        }
    }

    /** 当前是否处于云端登录态（决定要不要做同步）。 */
    fun isCloudLoggedIn(): Boolean = supabaseSession.value != null && SupabaseConfig.isConfigured()

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

    /**
     * 用户名规则：2–20 位，只允许英文、数字与 `.` `_` `-`。
     *
     * Supabase Auth 的密码模式协议上必须有 email，所以注册时把用户名映射成
     * `用户名@ivan-cinema.app` 这个固定域名下的合成地址，用户看到的始终是用户名。
     */
    private val USERNAME_RE = Regex("^[A-Za-z0-9._-]{2,20}$")

    /** 中日韩文字（含假名、谚文），用于给出「不能用中文」的明确提示。 */
    private val CJK_RE = Regex("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\uAC00-\\uD7AF]")

    private const val SYNTH_DOMAIN = "@ivan-cinema.app"

    /** 用户名 → 合成邮箱（统一小写，避免 Ivan / ivan 变成两个账号）。 */
    fun syntheticEmail(username: String): String =
        username.trim().lowercase() + SYNTH_DOMAIN

    /** 校验用户名；通过返回 null，否则返回可直接展示给用户的中文提示。 */
    fun validateUsername(raw: String): String? {
        val u = raw.trim()
        if (u.isEmpty()) return "请输入用户名"
        if (CJK_RE.containsMatchIn(u)) return "用户名不能包含中文，请改用英文、数字或 . _ -"
        if (u.length < 2) return "用户名至少 2 个字符"
        if (u.length > 20) return "用户名最多 20 个字符"
        if (!USERNAME_RE.matches(u)) return "用户名只能用英文、数字或 . _ -，不能有空格"
        return null
    }

    /** 把 Supabase 的英文错误翻成能看懂的中文；翻不了就原样返回。 */
    private fun friendly(msg: String?): String? {
        val m = msg.orEmpty().trim()
        if (m.isEmpty()) return null
        return when {
            m.contains("already registered", true) || m.contains("already been registered", true) ->
                "该用户名已被占用，换一个吧"
            m.contains("Invalid login credentials", true) -> "用户名或密码不对"
            m.contains("Email not confirmed", true) ->
                "这个账号需要邮箱验证。请到 Supabase 后台关闭 Confirm email 后再试"
            m.contains("Password should be at least", true) -> "密码至少 6 位"
            m.contains("rate limit", true) || m.contains("too many", true) -> "操作太频繁，稍后再试"
            m.contains("Unable to validate email", true) || m.contains("invalid format", true) ->
                "用户名格式不被接受，请换一个"
            else -> m
        }
    }

    private fun hash(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
