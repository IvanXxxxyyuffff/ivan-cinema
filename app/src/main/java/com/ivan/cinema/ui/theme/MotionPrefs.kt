package com.ivan.cinema.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 「减少动效」的唯一可观察源。
 *
 * 两级来源：
 *   · 用户在设置页手动打开 —— 持久化到 SharedPreferences，重启后仍然生效；
 *   · 系统级动画开关（开发者选项 / 无障碍）—— 三个 scale 只要有一个为 0 即视为开启。
 *
 * 裁决值 = 用户选择 || 系统选择。
 */
object MotionPrefs {
    private const val PREFS = "ivan_motion"
    private const val KEY_USER_REDUCED = "user_reduced"

    private var prefs: SharedPreferences? = null

    private var userReduced by mutableStateOf(false)
    private var systemReduced by mutableStateOf(false)

    /** 真正的裁决值。所有动效取值器都读它。 */
    val reduce: Boolean get() = userReduced || systemReduced

    fun init(ctx: Context) {
        val p = store(ctx)
        userReduced = p.getBoolean(KEY_USER_REDUCED, false)
        refreshSystem(ctx)
    }

    /** 设置页开关：立即生效并持久化。 */
    fun setUser(ctx: Context, on: Boolean) {
        userReduced = on
        store(ctx).edit().putBoolean(KEY_USER_REDUCED, on).apply()
    }

    /** 设备设置可能被中途改动，回到前台时重读一次。 */
    fun refreshSystem(ctx: Context) {
        systemReduced = runCatching {
            val cr = ctx.contentResolver
            val animator = Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            val transition = Settings.Global.getFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
            val window = Settings.Global.getFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, 1f)
            animator == 0f || transition == 0f || window == 0f
        }.getOrDefault(false)
    }

    private fun store(ctx: Context): SharedPreferences =
        prefs ?: ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { prefs = it }
}
