package com.ivan.cinema.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 错误上报：崩溃 / 播放失败 / 解析失败 / 接口失败，统一进同一个**共享池**。
 *
 * 产品决定（owner 已拍板，实现必须照此）：
 *   · 数据上云共享，20 个用户共同贡献、共同排障，喂给和播放逻辑同一份数据；
 *   · 只报「错误类型 + 源 + APP 版本 + Android 版本 + 机型」（播放失败额外带片名）；
 *   · **绝不报用户名 / user_id / IP / 精确位置等任何身份信息** —— 表里根本没有
 *     身份列（见 docs/supabase.sql），从结构上保证匿名；
 *   · 保留 30 天，旧行由 prune_error_report 清理。
 *
 * 崩溃采集是**自研**的（不引第三方 SDK）：装一个
 * Thread.setDefaultUncaughtExceptionHandler，先把崩溃写进本地队列，
 * 再把控制权交回原处理器让 APP 照常崩溃；下次启动时由 [flush] 补传。
 *
 * 线程/健壮性约定：所有公开方法都**绝不抛异常**，也**绝不阻塞调用方**；
 * 未登录 / 无网络一律安全 no-op。R8 安全：无反射、不依赖类名，载荷全是纯字符串。
 */
object ErrorReporter {

    /** 保留天数：与 docs/supabase.sql 的 prune_error_report 约定一致。 */
    private const val RETENTION_DAYS = 30

    /** 安装只做一次；[install] 会被多个入口调用，必须幂等。 */
    @Volatile private var installed = false

    /** 串行化 flush，避免启动补传与 report 触发的补传并发重复上传。 */
    private val flushLock = Mutex()

    /**
     * 安装崩溃兜底。幂等：重复调用只生效一次。
     *
     * 同时会在后台补传上次崩溃 + 平时积压的错误，并顺带清理 30 天前的旧行 ——
     * 所以 IVANApp.onCreate 只需调这一个方法即可覆盖「崩溃后下次启动上报」。
     */
    fun install(ctx: Context) {
        synchronized(this) {
            if (installed) return
            val app = ctx.applicationContext
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            // 已经是我们的处理器就不再包裹（防重复安装导致自我递归）
            if (prev is CrashHandler) {
                installed = true
                return
            }
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(prev, app))
            installed = true
        }
        val app = ctx.applicationContext
        CoroutineScope(Dispatchers.IO).launch { runCatching { flush(app) } }
    }

    /**
     * 上传本地队列里所有待传错误，然后清理过期数据。
     *
     * fire-and-forget 安全：未登录 / 无网络直接返回，本地条目原样保留；
     * **只有成功上传的条目才会被清掉**，失败的下次再传。
     */
    suspend fun flush(ctx: Context) {
        val app = ctx.applicationContext
        flushLock.withLock {
            val token = tokenOrNull() ?: return@withLock   // 未登录：本地留着，下次再说
            runCatching {
                val pending = loadQueue(app)
                if (pending.isNotEmpty()) {
                    val remaining = ArrayList<JSONObject>(pending.size)
                    for (entry in pending) {
                        if (SupabaseClient.reportError(token, entry).isSuccess) continue
                        remaining.add(entry)   // 失败的保留，下次重试
                    }
                    if (remaining.size != pending.size) saveQueue(app, remaining)
                }
                // 清理 30 天前的旧行（best-effort，失败无所谓）
                SupabaseClient.pruneErrors(token, RETENTION_DAYS)
            }
        }
    }

    /**
     * 非致命错误上报：先本地入队，再后台尝试发送。
     *
     * 绝不抛异常、绝不阻塞调用方 —— 适合在播放 / 解析 / 请求失败处直接调用。
     */
    fun report(
        ctx: Context,
        kind: String,
        sourceApi: String = "",
        vodKey: String = "",
        message: String = "",
        stack: String = ""
    ) {
        runCatching {
            val app = ctx.applicationContext
            enqueue(app, buildEntry(kind, sourceApi, vodKey, message, stack), sync = false)
            CoroutineScope(Dispatchers.IO).launch { runCatching { flush(app) } }
        }
    }

    /** 当前会话的 access token；未配置 Supabase 或未登录时返回 null。 */
    private fun tokenOrNull(): String? {
        if (!SupabaseConfig.isConfigured()) return null
        return Account.supabaseSession.value?.first?.takeIf { it.isNotEmpty() }
    }
}

// ─────────────────────────────── 文件内私有实现 ───────────────────────────────
// 放在顶层：崩溃处理器（独立类）与单例 object 都能访问，且都是 private 不外泄。

/** 本地待传队列（SharedPreferences 存一个 JSON 数组字符串）。 */
private const val PREF = "ivan_error_queue"
private const val KEY_QUEUE = "queue"

/** 队列上限：崩溃循环时丢最旧的，防止把存储写爆。 */
private const val MAX_PENDING = 20

/** 截断长度：message 2KB、stack 8KB，避免单条把请求体撑爆。 */
private const val MAX_MESSAGE = 2 * 1024
private const val MAX_STACK = 8 * 1024

/** 防递归：崩溃处理器自己再抛异常时不能无限重入。 */
private val handling = AtomicBoolean(false)

/** 组装一条上报载荷；只含去标识化的运行信息，**没有任何身份字段**。 */
private fun buildEntry(
    kind: String,
    sourceApi: String,
    vodKey: String,
    message: String,
    stack: String
): JSONObject = JSONObject()
    .put("kind", truncate(kind, 64))
    .put("source_api", truncate(sourceApi, 512))
    .put("vod_key", truncate(vodKey, 512))
    .put("message", truncate(message, MAX_MESSAGE))
    .put("stack", truncate(stack, MAX_STACK))
    .put("app_version", runCatching { com.ivan.cinema.BuildConfig.VERSION_NAME }.getOrDefault(""))
    .put("android_version", Build.VERSION.RELEASE.orEmpty())
    .put("device_model", Build.MODEL.orEmpty())
    .put("created_at", System.currentTimeMillis())

private fun truncate(s: String, max: Int): String =
    if (s.length <= max) s else s.substring(0, max)

/** 读本地队列；损坏 / 缺失一律当空表，绝不抛。 */
private fun loadQueue(ctx: Context): MutableList<JSONObject> {
    val text = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString(KEY_QUEUE, null) ?: return mutableListOf()
    return runCatching {
        val arr = JSONArray(text)
        MutableList(arr.length()) { arr.getJSONObject(it) }
    }.getOrDefault(mutableListOf())
}

/**
 * 写本地队列。崩溃路径必须同步落盘（[sync] = true，用 commit），
 * 因为紧接着进程就会死，apply 的异步写可能来不及持久化。
 */
private fun saveQueue(ctx: Context, list: List<JSONObject>, sync: Boolean = false) {
    runCatching {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        val edit = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_QUEUE, arr.toString())
        if (sync) edit.commit() else edit.apply()
    }
}

/** 入队并封顶；超上限丢最旧的（队首）。 */
private fun enqueue(ctx: Context, entry: JSONObject, sync: Boolean = false) {
    runCatching {
        val q = loadQueue(ctx)
        q.add(entry)
        while (q.size > MAX_PENDING) q.removeAt(0)
        saveQueue(ctx, q, sync)
    }
}

/**
 * 自研崩溃处理器：先把崩溃留档到本地队列，再交回原处理器。
 *
 * 关键点：
 *   · 只处理一次（[handling] CAS），自身出错也不会递归；
 *   · 落盘用同步 commit，保证进程死前数据已持久化；
 *   · 处理完**一定**委托给原处理器（没有则按系统默认结束进程），
 *     保证 APP 仍然正常崩溃，不吞异常、不制造僵尸进程。
 */
private class CrashHandler(
    private val prev: Thread.UncaughtExceptionHandler?,
    private val app: Context
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        if (handling.compareAndSet(false, true)) {
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                enqueue(app, buildEntry("crash", "", "", e.toString(), sw.toString()), sync = true)
            }
        }
        if (prev != null) {
            prev.uncaughtException(t, e)
        } else {
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }
}
