package com.ivan.cinema.data

import android.content.Context
import com.ivan.cinema.db.AppDb
import com.ivan.cinema.db.FollowEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 追剧订阅的门面。
 *
 * 只是一层薄壳：所有 SQL 在 [com.ivan.cinema.db.FollowDao]，这里只负责
 *   ① 给每条记录盖上当前用户 id（和 Account.stamp 同一口径，避免多账号串号）；
 *   ② 把数据库异常兜住 —— 追剧是增强功能，DB 出问题也绝不能崩掉详情页。
 *
 * 和 Account 一样用 object：无状态、随处可调，调用方不必持有实例。
 */
object FollowStore {

    private fun dao(ctx: Context) = AppDb.get(ctx).followDao()

    /** 当前用户是否已追这部剧。读失败按「未追」处理。 */
    suspend fun isFollowed(ctx: Context, vodKey: String): Boolean = runCatching {
        dao(ctx).getFor(Account.currentUserId(), vodKey) != null
    }.getOrDefault(false)

    /** 追剧：写入时强制盖当前 userId（传入值会被覆盖，调用方不必自己 stamp）。 */
    suspend fun follow(ctx: Context, entry: FollowEntry) {
        runCatching { dao(ctx).upsert(entry.copy(userId = Account.currentUserId())) }
    }

    /** 取消追剧。 */
    suspend fun unfollow(ctx: Context, vodKey: String) {
        runCatching { dao(ctx).delete(vodKey) }
    }

    /** 「我的追剧」列表（当前用户，Flow）。DB 读失败退化为空列表，不抛给 UI。 */
    fun allFor(ctx: Context): Flow<List<FollowEntry>> = runCatching {
        dao(ctx).allFor(Account.currentUserId())
    }.getOrDefault(flowOf(emptyList()))

    /** 定时任务用的一次性快照。 */
    suspend fun allOnce(ctx: Context): List<FollowEntry> = runCatching {
        dao(ctx).allOnce(Account.currentUserId())
    }.getOrDefault(emptyList())

    /** 核验后推进集数基线与核验时间。 */
    suspend fun markChecked(ctx: Context, vodKey: String, count: Int, checkedAt: Long) {
        runCatching { dao(ctx).updateEpisodeCount(vodKey, count, checkedAt) }
    }
}
