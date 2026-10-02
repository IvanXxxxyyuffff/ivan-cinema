package com.ivan.cinema.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ivan.cinema.MainActivity
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * 追剧更新检查。
 *
 * MacCMS 没有推送，只能**轮询**：每天跑一次，逐个重查已追剧集的详情，
 * 集数比基线大就发一条本地通知。设计上有三条硬约束：
 *
 *   ① 单源必须能被抛弃 —— 每个请求套 [SOURCE_TIMEOUT_MS] 超时，超时/失败只跳过这一条，
 *      绝不阻塞后面的剧（一个死源不该拖垮整轮检查）。
 *   ② 有上限 —— 最多检查 [MAX_FOLLOWS] 部，避免追了几百部时一天打几百个请求。
 *   ③ 基线只在**成功拿到集数**时推进 —— 源挂了时若把集数写成 0，下次恢复会误报
 *      「更新到第 N 集」；宁可这次不推进，也不制造假提醒。
 *
 * 失败一律返回 [Result.success]：这是一次尽力而为的后台检查，不是需要重试的作业，
 * WorkManager 的重试退避只会把网络请求堆起来。
 */
class FollowWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val follows = runCatching { FollowStore.allOnce(ctx) }.getOrDefault(emptyList())
        if (follows.isEmpty()) return Result.success()

        ensureChannel(ctx)

        for (f in follows.take(MAX_FOLLOWS)) {
            // 单个源：超时即放弃，不影响下一部
            val detail = runCatching {
                withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                    MacCmsApi(VodSource(f.sourceName, f.sourceApi)).detail(f.vodId)
                }
            }.getOrNull()

            // 拿不到详情（源挂了 / 影片下架）→ 跳过，保留旧基线
            val count = detail?.lines?.firstOrNull()?.episodes?.size ?: continue

            // episodeCount == 0 是"关注时就没集数"的兜底：把它当基线建立，不提醒
            if (f.episodeCount > 0 && count > f.episodeCount) {
                notifyUpdate(ctx, f.vodKey, f.name, count)
            }
            runCatching {
                FollowStore.markChecked(ctx, f.vodKey, count, System.currentTimeMillis())
            }
        }
        return Result.success()
    }

    private fun notifyUpdate(ctx: Context, vodKey: String, name: String, count: Int) {
        // Android 13+ 未授权时 notify 会被静默丢弃，先自查，省一次无意义调用
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return

        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx,
            vodKey.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(name)
            .setContentText("更新到第 $count 集")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // 每部剧一个通知 id：多部同时更新时不会互相覆盖
        runCatching { NotificationManagerCompat.from(ctx).notify(vodKey.hashCode(), n) }
    }

    companion object {
        const val UNIQUE_NAME = "ivan_follow_daily"
        private const val CHANNEL_ID = "ivan_follow"
        private const val CHANNEL_NAME = "追剧更新"
        private const val MAX_FOLLOWS = 20
        private const val SOURCE_TIMEOUT_MS = 8_000L

        /** 通知渠道：与下载渠道同一套写法，IMPORTANCE_DEFAULT（要能响，不是静默）。 */
        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        /**
         * 排一次每天的后台检查。
         *
         * [ExistingPeriodicWorkPolicy.KEEP]：已存在同名作业就原样保留，不重置周期 ——
         * 否则每次冷启都会把"下次执行"往后推，一天开十次 App 就永远等不到执行。
         * 约束 [NetworkType.CONNECTED]：没网时不做无意义的请求。
         */
        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<FollowWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            runCatching {
                WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            }
        }
    }
}
