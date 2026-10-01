package com.ivan.cinema.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 「减少动效」的唯一可观察源。
 *
 * 系统级「动画时长比例 = 关闭」（开发者选项 / 无障碍）即视为开启 ——
 * 用户对整台设备的要求，App 必须跟随。
 */
object MotionPrefs {
    private var userReduced by mutableStateOf(false)
    private var systemReduced by mutableStateOf(false)

    /** 真正的裁决值。所有动效取值器都读它。 */
    val reduce: Boolean get() = userReduced || systemReduced

    fun init(ctx: Context) {
        refreshSystem(ctx)
    }

    /** 设置页开关：立即生效。 */
    fun setUser(value: Boolean) {
        userReduced = value
    }

    /** 设备设置可能被中途改动，回到前台时重读一次。 */
    fun refreshSystem(ctx: Context) {
        systemReduced = runCatching {
            Settings.Global.getFloat(
                ctx.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}
