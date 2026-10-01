package com.ivan.cinema.data

import com.ivan.cinema.db.WatchEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Supabase REST 客户端（不引依赖，直接用 OkHttp 打 Auth 与 PostgREST）。
 *
 * 约定：所有网络方法都在 IO 线程执行，失败一律包进 Result / 返回空集合，
 * 绝不把异常抛给调用方导致 APP 崩溃；错误信息取自 Supabase 返回 JSON 的
 * message / error_description / msg 字段，尽量可读。
 */
object SupabaseClient {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 专用客户端：正常校验证书（不用 MacCMS 那个 trust-all），短超时避免卡登录页。 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private class SupabaseException(message: String) : Exception(message)

    // ────────────────────────────── Auth ──────────────────────────────

    /** 注册：POST {URL}/auth/v1/signup。成功返回 userId（或提示语）。 */
    suspend fun signUp(email: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("email", email)
                .put("password", password)
                .toString()
            val req = Request.Builder()
                .url("${base()}/auth/v1/signup")
                .header("apikey", key())
                .header("Authorization", "Bearer ${key()}")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON))
                .build()
            val o = JSONObject(send(req).trim().trimStart('\uFEFF'))
            val id = o.optString("id", "")
                .ifEmpty { o.optJSONObject("user")?.optString("id", "").orEmpty() }
            id.ifEmpty { "注册成功" }
        }
    }

    /** 登录：POST {URL}/auth/v1/token?grant_type=password。成功返回 (accessToken, userId)。 */
    suspend fun signIn(email: String, password: String): Result<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("email", email)
                    .put("password", password)
                    .toString()
                val req = Request.Builder()
                    .url("${base()}/auth/v1/token?grant_type=password")
                    .header("apikey", key())
                    .header("Authorization", "Bearer ${key()}")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody(JSON))
                    .build()
                val o = JSONObject(send(req).trim().trimStart('\uFEFF'))
                val token = o.optString("access_token", "")
                val uid = o.optJSONObject("user")?.optString("id", "").orEmpty()
                if (token.isEmpty() || uid.isEmpty()) throw SupabaseException("登录失败：未获取到会话")
                token to uid
            }
        }

    // ─────────────────────────── Watch history ───────────────────────────

    /** 上传一条观看记录：POST {URL}/rest/v1/watch_history（主键 user_id + vod_key，merge-duplicates）。 */
    suspend fun upsertWatch(token: String, userId: String, entry: WatchEntry): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = entry.toJson(userId).toString()
                val req = Request.Builder()
                    .url("${base()}/rest/v1/watch_history")
                    .header("apikey", key())
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .header("Prefer", "resolution=merge-duplicates,return=minimal")
                    .post(body.toRequestBody(JSON))
                    .build()
                send(req)
                Unit
            }
        }

    /** 拉取该用户全部观看记录：GET {URL}/rest/v1/watch_history?user_id=eq.{userId}&select=*。 */
    suspend fun pullWatches(token: String, userId: String): List<WatchEntry> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("${base()}/rest/v1/watch_history?user_id=eq.$userId&select=*")
                    .header("apikey", key())
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                val text = send(req).trim()
                if (text.isEmpty() || text == "[]") emptyList()
                else {
                    val arr = JSONArray(text)
                    (0 until arr.length()).map { arr.getJSONObject(it).toWatchEntry() }
                }
            }.getOrElse { emptyList() }
        }

    // ────────────────────────────── 内部 ──────────────────────────────

    /** 执行请求；非 2xx 抛出带可读信息的异常，成功返回响应体。 */
    private fun send(req: Request): String {
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw SupabaseException(readError(resp.code, body))
            return body
        }
    }

    /** 从 Supabase 错误体里挑出人能看懂的字段。 */
    private fun readError(code: Int, body: String): String {
        val msg = runCatching {
            val o = JSONObject(body.trim())
            o.optString("msg", "")
                .ifEmpty { o.optString("message", "") }
                .ifEmpty { o.optString("error_description", "") }
                .ifEmpty { o.optString("error", "") }
        }.getOrDefault("")
        return if (msg.isNotEmpty()) msg else "请求失败（HTTP $code）"
    }

    private fun base(): String = SupabaseConfig.url().trimEnd('/')

    private fun key(): String = SupabaseConfig.anonKey()

    private fun WatchEntry.toJson(userId: String): JSONObject = JSONObject()
        .put("user_id", userId)
        .put("vod_key", vodKey)
        .put("name", name)
        .put("year", year)
        .put("pic", pic)
        .put("source_api", sourceApi)
        .put("source_name", sourceName)
        .put("line_index", lineIndex)
        .put("episode_index", episodeIndex)
        .put("episode_name", episodeName)
        .put("position_ms", positionMs)
        .put("duration_ms", durationMs)
        .put("updated_at", updatedAt)

    private fun JSONObject.toWatchEntry(): WatchEntry = WatchEntry(
        vodKey = optString("vod_key", ""),
        name = optString("name", ""),
        year = optString("year", ""),
        pic = optString("pic", ""),
        sourceApi = optString("source_api", ""),
        sourceName = optString("source_name", ""),
        lineIndex = optInt("line_index", 0),
        episodeIndex = optInt("episode_index", 0),
        episodeName = optString("episode_name", ""),
        positionMs = optLong("position_ms", 0L),
        durationMs = optLong("duration_ms", 0L),
        updatedAt = optLong("updated_at", 0L)
    )
}
