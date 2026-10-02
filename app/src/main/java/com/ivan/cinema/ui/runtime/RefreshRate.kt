package com.ivan.cinema.ui.runtime

import android.app.Activity
import android.content.Context
import android.view.Display
import android.view.WindowManager

/*
 * 刷新率 —— 一块 120Hz 的屏，系统省电策略默认给普通 App 锁在低档。
 * 用 preferredDisplayModeId 指名要某个显示模式；只在同分辨率里挑最高帧率，
 * 不为帧率牺牲分辨率。apply 返回是否真的改了窗口属性（不承诺屏幕立刻换挡）。
 */
object RefreshRate {

    data class Snapshot(val current: Float, val best: Float)

    data class ModeOption(val modeId: Int, val hz: Float) {
        fun label(auto: Boolean = false): String {
            val h = if (hz >= 100f) hz.toInt().toString() else String.format("%.0f", hz)
            return if (auto) "自动" else "${h}Hz"
        }
    }

    fun hasChoice(activity: Activity?): Boolean = options(activity).size > 1

    /** 同分辨率下所有可选的刷新率档，从高到低。 */
    fun options(activity: Activity?): List<ModeOption> {
        val display = display(activity) ?: return emptyList()
        val modes = runCatching { display.supportedModes?.toList() }.getOrNull().orEmpty()
        if (modes.isEmpty()) return emptyList()
        val base = runCatching { display.mode }.getOrNull()
        val pool = (base?.let { b -> modes.filter { sameResolution(it, b) } }?.takeIf { it.isNotEmpty() }
            ?: modes)
        return pool
            .sortedBy { it.modeId }
            .distinctBy { it.refreshRate.toInt() }
            .sortedByDescending { it.refreshRate }
            .map { ModeOption(it.modeId, it.refreshRate) }
    }

    fun snapshot(activity: Activity?): Snapshot {
        val display = display(activity) ?: return Snapshot(0f, 0f)
        val cur = runCatching { display.refreshRate }.getOrDefault(0f)
        val best = options(activity).maxOfOrNull { it.hz } ?: cur
        return Snapshot(cur, best)
    }

    /** 请求同分辨率内最高刷新率。 */
    fun apply(activity: Activity?, enabled: Boolean): Boolean {
        val a = activity ?: return false
        if (!enabled) return resetMode(a)

        val list = options(a)
        if (list.isEmpty()) return false
        val target = list.first()

        return runCatching {
            val w = a.window
            val attrs = w.attributes
            var changed = false

            if (attrs.preferredDisplayModeId != target.modeId) {
                attrs.preferredDisplayModeId = target.modeId
                changed = true
            }
            // 第二条腿：有些厂商的省电策略不认 modeId，但认 preferredRefreshRate。
            if (attrs.preferredRefreshRate != target.hz) {
                attrs.preferredRefreshRate = target.hz
                changed = true
            }
            if (changed) w.attributes = attrs
            changed
        }.getOrDefault(false)
    }

    /**
     * 按**内容帧率**挑刷新率档。
     *
     * 这一项不产生新帧，但能把抖动消掉：24fps 在 60Hz 上是 3:2 下拉（每两帧里有一帧重复三次、
     * 一帧两次），眼睛看得出来一顿一顿；在 120Hz 上是 5:5，每帧均匀重复五次，完全不抖。
     * 动画绝大多数是 23.976/24fps，所以这条对看番是实打实的收益，而且零成本。
     *
     * 规则：在「刷新率 ÷ 帧率 为整数」的档里取最小的那个，且至少 2 倍（1 倍等于没有余量）；
     * 一个整数倍都没有（比如 25fps 遇上 60/90/120 都不是整数倍）就退回最高档，至少不吃亏。
     */
    fun applyForContent(activity: Activity?, frameRate: Float): Boolean {
        val a = activity ?: return false
        val list = options(a)
        if (list.isEmpty()) return false
        if (frameRate <= 1f) return apply(a, true)

        val exact = list
            .filter { m ->
                val ratio = m.hz / frameRate
                val rounded = Math.round(ratio)
                rounded >= 2 && kotlin.math.abs(ratio - rounded) < 0.02f
            }
            .sortedBy { it.hz }

        val target = exact.firstOrNull() ?: list.first()
        return requestMode(a, target)
    }

    private fun requestMode(a: Activity, target: ModeOption): Boolean = runCatching {
        val w = a.window
        val attrs = w.attributes
        var changed = false
        if (attrs.preferredDisplayModeId != target.modeId) {
            attrs.preferredDisplayModeId = target.modeId
            changed = true
        }
        if (attrs.preferredRefreshRate != target.hz) {
            attrs.preferredRefreshRate = target.hz
            changed = true
        }
        if (changed) w.attributes = attrs
        changed
    }.getOrDefault(false)

    private fun resetMode(a: Activity): Boolean = runCatching {
        val w = a.window
        val attrs = w.attributes
        if (attrs.preferredDisplayModeId == 0 && attrs.preferredRefreshRate == 0f) {
            false
        } else {
            attrs.preferredDisplayModeId = 0
            attrs.preferredRefreshRate = 0f
            w.attributes = attrs
            true
        }
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun display(activity: Activity?): Display? = runCatching {
        val wm = activity?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        wm?.defaultDisplay
    }.getOrNull()

    private fun sameResolution(a: Display.Mode, b: Display.Mode): Boolean =
        a.physicalWidth == b.physicalWidth && a.physicalHeight == b.physicalHeight
}
