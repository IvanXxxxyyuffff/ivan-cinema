package com.ivan.cinema.data

import android.content.Context

/**
 * 单个源的**共享**健康聚合：由所有客户端共同贡献、共同读取。
 *
 * 与 [SourceHealth] 的本地探测不同，这里的数字来自服务端聚合，
 * 换设备 / 换账号后依然有效（20 个用户共享同一份判断）。
 */
data class SourceStat(
    val sourceApi: String,
    val ok: Int = 0,
    val fail: Int = 0,
    val lastOk: Long = 0L,
    val lastFail: Long = 0L
) {
    /** 总样本数（ok + fail）。样本太少时不应据此判定源的好坏。 */
    val total: Int get() = ok + fail

    /**
     * 成功率，取值 0..1。没有样本时返回 1.0（中性、不惩罚），
     * 调用方需结合 [total] 判断是否值得采信（见 [SharedHealth.isBad]）。
     */
    val successRate: Double
        get() = if (total <= 0) 1.0 else ok.toDouble() / total
}

/**
 * 共享源健康：两条信号，所有客户端贡献、所有客户端读取。
 *
 *   (a) 探测信号 —— 客户端启动探测的结果（[reportProbe]）；
 *   (b) 播放失败信号 —— 播放失败时静默上报（[reportPlayFailure]）。
 *
 * 约定：所有方法都返回 Result / 空集合，**绝不抛异常**；未登录（无 Supabase
 * 会话）时一律安全 no-op，不会影响本地使用。网络调用只走 [SupabaseClient]
 * 已有的 OkHttp 管道，不另起客户端。
 */
object SharedHealth {

    /** 判定「坏源」所需的最小样本数：样本不足不降权，避免误伤新源。 */
    const val MIN_SAMPLES = 4

    /** 成功率低于该值即视为坏源（会在排行里被降权）。 */
    const val BAD_RATE = 0.5

    /**
     * 上报一次探测结果：可用 / 不可用各记一次。无会话时静默 no-op。
     * 建议 fire-and-forget 调用，不阻塞首屏。
     */
    suspend fun reportProbe(ctx: Context, results: Map<String, Boolean>): Result<Unit> {
        if (results.isEmpty()) return Result.success(Unit)
        val token = tokenOrNull() ?: return Result.success(Unit)
        return SupabaseClient.upsertSourceHealth(token, results)
    }

    /**
     * 播放失败静默上报：对 (源, 片) 计数 +1。可安全 fire-and-forget；
     * 参数为空或无会话时直接返回成功，不产生任何网络请求。
     */
    suspend fun reportPlayFailure(ctx: Context, sourceApi: String, vodKey: String): Result<Unit> {
        if (sourceApi.isBlank() || vodKey.isBlank()) return Result.success(Unit)
        val token = tokenOrNull() ?: return Result.success(Unit)
        return SupabaseClient.bumpPlayFailure(token, sourceApi, vodKey)
    }

    /**
     * 读取共享聚合，返回 source_api → [SourceStat]。
     * 未登录 / 无网络 / 解析失败一律返回空表（绝不抛异常）。
     */
    suspend fun fetch(ctx: Context): Map<String, SourceStat> {
        val token = tokenOrNull() ?: return emptyMap()
        return SupabaseClient.pullSourceHealth(token)
            .filter { it.sourceApi.isNotEmpty() }
            .associateBy { it.sourceApi }
    }

    /** 供排行使用：该源是否已被判定为坏源（样本足够且成功率低）。 */
    fun isBad(stat: SourceStat?): Boolean =
        stat != null && stat.total >= MIN_SAMPLES && stat.successRate < BAD_RATE

    /**
     * 给播放层用：把候选线路按「先试哪个」排好序，best-first。
     *
     * 规则（产品要求）：**只降权、绝不隐藏** —— 传进来的每个 api 都恰好返回一次，
     * 不新增、不丢弃、不去重。共享健康里被判定为坏源（[isBad]）的排到后面，
     * 其余保持原有相对顺序（Kotlin [sortedBy] 是稳定排序，所以「未知源」的相对
     * 次序与传入时完全一致）。未登录 / 无网络时 [fetch] 返回空表，等价于原样返回。
     *
     * 例：rankLines(ctx, [A, B, C])，若共享池判定 B 是坏源 → [A, C, B]。
     */
    suspend fun rankLines(ctx: Context, apis: List<String>): List<String> {
        if (apis.size <= 1) return apis
        val stats = fetch(ctx)
        return apis.sortedBy { if (isBad(stats[it])) 1 else 0 }
    }

    /** 当前会话的 access token；未配置 Supabase 或未登录时返回 null。 */
    private fun tokenOrNull(): String? {
        if (!SupabaseConfig.isConfigured()) return null
        return Account.supabaseSession.value?.first?.takeIf { it.isNotEmpty() }
    }
}
