package com.ivan.cinema.data

import android.content.Context
import com.ivan.cinema.db.AppDb
import com.ivan.cinema.db.FavEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 片单收藏（想看）的门面。
 *
 * 只是一层薄壳：所有 SQL 在 [com.ivan.cinema.db.FavDao]，这里只负责
 *   ① 给每条记录盖上当前用户 id（和 Account.currentUserId() 同一口径，避免多账号串号）；
 *   ② 把数据库异常兜住 —— 收藏是增强功能，DB 出问题也绝不能崩掉详情页。
 *
 * 与 [FollowStore] 完全同构：object 无状态、随处可调，调用方不必持有实例。
 */
object FavStore {

    private fun dao(ctx: Context) = AppDb.get(ctx).favDao()

    /** 当前用户是否已收藏这部片。读失败按「未收藏」处理。 */
    suspend fun isFav(ctx: Context, vodKey: String): Boolean = runCatching {
        dao(ctx).getFor(Account.currentUserId(), vodKey) != null
    }.getOrDefault(false)

    /** 收藏：写入时强制盖当前 userId（传入值会被覆盖，调用方不必自己 stamp）。 */
    suspend fun add(ctx: Context, entry: FavEntry) {
        runCatching { dao(ctx).upsert(entry.copy(userId = Account.currentUserId())) }
    }

    /** 取消收藏。 */
    suspend fun remove(ctx: Context, vodKey: String) {
        runCatching { dao(ctx).delete(vodKey) }
    }

    /** 「我的片单」列表（当前用户，Flow）。DB 读失败退化为空列表，不抛给 UI。 */
    fun allFor(ctx: Context): Flow<List<FavEntry>> = runCatching {
        dao(ctx).allFor(Account.currentUserId())
    }.getOrDefault(flowOf(emptyList()))

    /** 一次性快照（不走 Flow）。 */
    suspend fun allOnce(ctx: Context): List<FavEntry> = runCatching {
        dao(ctx).allOnce(Account.currentUserId())
    }.getOrDefault(emptyList())
}
