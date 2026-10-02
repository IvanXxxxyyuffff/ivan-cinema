package com.ivan.cinema.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ivan.cinema.ui.theme.MotionKind
import com.ivan.cinema.ui.theme.MotionPrefs
import com.ivan.cinema.ui.theme.Springs
import com.ivan.cinema.ui.theme.motionFade
import com.ivan.cinema.ui.theme.motionFloat
import com.ivan.cinema.ui.theme.motionSpring
import kotlinx.coroutines.delay

/*
 * 动效工具集 —— 「动效纪律」的执法点。
 * 任何一处交互反馈都不许自己手写弹簧、自己调时长、自己判断「减少动效」。
 * 一律从这里取。
 */

/** 已经播过入场动画的元素标识（全 App 共用一本账）。 */
private val staggerGate: MutableSet<Any> = mutableSetOf()

private const val STAGGER_MAX_INDEX = 6
private const val GATE_CAP = 6000

/**
 * 按压深度令牌（全 App 只此两档）。
 *   card    —— 整块卡片 / 海报砖；
 *   control —— 按钮、胶囊、图标等控件。
 * pressDip 的默认值即 control；调用点要覆盖时只准从这两档里选。
 */
object press {
    const val card = 0.97f
    const val control = 0.94f
}

/**
 * 落位动画：列表元素按索引级联淡入上浮，**只播一次**。
 * 判定依据是 [identity]（稳定标识，例如 source+id），不是下标 ——
 * 滚出去再滚回来不重演，删项导致上移也不重演。
 * 走 graphicsLayer 不走 AnimatedVisibility：位置从第一帧起就是最终位置，零布局抖动。
 *
 * [index] 收**原始下标**：内部 clamp 到 [STAGGER_MAX_INDEX]（超出只是不再增加延迟，
 * 不是取模回卷）—— 调用点若传 `index % 12` 会让级联每 12 项重来一次，多列网格读成一块块涌出。
 */
@Composable
fun StaggerIn(
    index: Int,
    modifier: Modifier = Modifier,
    identity: Any? = null,
    stepMs: Long = 28L,
    content: @Composable () -> Unit,
) {
    val reduce = MotionPrefs.reduce
    val token: Any = identity ?: index

    val fresh = remember(token) {
        if (staggerGate.size > GATE_CAP) staggerGate.clear()
        staggerGate.add(token)
    }

    var visible by remember(token) { mutableStateOf(!fresh || reduce) }
    LaunchedEffect(token) {
        if (!visible) {
            delay(index.coerceIn(0, STAGGER_MAX_INDEX) * stepMs)
            visible = true
        }
    }

    // `p` 用 `=` 不用 `by`：每帧只在绘制期读一次，整段入场零重组。
    val p = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = motionFloat(MotionKind.Snappy),
        label = "stagger",
    )

    Box(
        modifier.graphicsLayer {
            val v = p.value
            alpha = v
            translationY = (1f - v) * (size.height / 7f)
        },
    ) { content() }
}

/**
 * 按压缩放：整块轻微内缩，松手弹回（全 App 唯一的按压反馈实现）。
 * 用法：同一个 interaction source 同时给 pressDip 和 clickable。
 */
@Composable
fun Modifier.pressDip(
    interaction: MutableInteractionSource,
    to: Float = press.control,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) to else 1f,
        animationSpec = motionSpring(Springs.snappy),
        label = "pressDip",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** 图标交叉切换：同一位置换一张图（播放/暂停），淡出淡入不硬切。 */
@Composable
fun MotionIconSwap(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    Crossfade(
        targetState = icon,
        animationSpec = motionFade(140),
        label = "iconSwap",
        modifier = modifier,
    ) { current ->
        Icon(current, contentDescription, tint = tint, modifier = Modifier.size(size))
    }
}

/** 文字交叉切换：同一位置换一句文案。 */
@Composable
fun MotionTextSwap(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Crossfade(
        targetState = text,
        animationSpec = motionFade(150),
        label = "textSwap",
        modifier = modifier,
    ) { current ->
        Text(current, style = style, color = color, maxLines = 1)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Portal：首页 ⇄ 搜索页的共享时间轴转场
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 转场唯一时间轴：0 = 首页态，1 = 搜索页态。
 * 纯函数关系：每个块该在哪儿只由这一个数决定，返回 = 时间轴倒放，无需第二套编排。
 * 页面容器负责驱动（首页侧静息 0、搜索页侧静息 1），块级 portalOut/portalIn 读它。
 */
val LocalPortal = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Float>> { androidx.compose.runtime.mutableStateOf(0f) }

/** 块的去向（也是它回来的那一侧）。 */
enum class PortalTo { Left, Right, Down }

// 退场 [0.00, 0.30]，相邻块错开 0.036 → 第一块 210ms 走完；
// 进场 [0.28, 0.84]，两件事"搭着走"：退场尾巴接上进场起手，一进一出读成一次转场。
private const val OutStart = 0.00f
private const val OutStep = 0.036f
private const val OutSpan = 0.30f
private const val InStart = 0.28f
private const val InStep = 0.028f
private const val InSpan = 0.56f

/** 固定距离（不是自身尺寸）：同方向上的块走一样远，才是一个节奏。 */
private val PortalSideTravel = 460.dp
private val PortalDownTravel = 640.dp

/** 离场加速（不回弹），进场带一点点过冲落位。 */
private val PortalOutEase = androidx.compose.animation.core.FastOutLinearInEasing
private val PortalInEase = androidx.compose.animation.core.CubicBezierEasing(0.25f, 1.18f, 0.45f, 1f)

/** 退场侧的块：静息在低位，时间轴涨上去而离开；倒放时从同一侧滑回原位。 */
@Composable
fun Modifier.portalOut(to: PortalTo, index: Int = 0): Modifier =
    portal(to, index, restingHigh = false)

/** 进场侧的块：静息在高位，时间轴涨到 1 时从 to 那一侧归位。 */
@Composable
fun Modifier.portalIn(to: PortalTo, index: Int = 0): Modifier =
    portal(to, index, restingHigh = true)

/**
 * 搜索框的「裁剪揭示」：右缘固定、向左逐渐展开（纯绘制期，零布局抖动）。
 * 展开窗口 [0.02, 0.55]（轴的前半段），缓动 EaseOut —— 起步快、末段慢慢贴住。
 */
@Composable
fun Modifier.portalReveal(): Modifier {
    val m = LocalPortal.current
    if (MotionPrefs.reduce) return this
    return this.drawWithContent {
        val raw = ((m.value - 0.02f) / 0.53f).coerceIn(0f, 1f)
        val eased = 1f - (1f - raw) * (1f - raw)
        val left = size.width * (1f - eased)
        clipRect(left = left, top = 0f, right = size.width, bottom = size.height) {
            this@drawWithContent.drawContent()
        }
    }
}

/** 揭示进度（0→1），给框内文字/图标的延迟淡入用。 */
@Composable
fun portalRevealProgress(): Float {
    val m = LocalPortal.current
    val raw = ((m.value - 0.28f) / 0.35f).coerceIn(0f, 1f)
    return raw
}

@Composable
private fun Modifier.portal(to: PortalTo, index: Int, restingHigh: Boolean): Modifier {
    val m = LocalPortal.current
    if (MotionPrefs.reduce) return this
    val i = index.coerceIn(0, 8)
    val start = if (restingHigh) InStart + i * InStep else OutStart + i * OutStep
    val span = if (restingHigh) InSpan else OutSpan
    return graphicsLayer {
        val raw = ((m.value - start) / span).coerceIn(0f, 1f)
        val travel = if (restingHigh) PortalInEase.transform(raw) else PortalOutEase.transform(raw)
        val out = if (restingHigh) 1f - travel else travel
        when (to) {
            PortalTo.Left -> translationX = -out * PortalSideTravel.toPx()
            PortalTo.Right -> translationX = out * PortalSideTravel.toPx()
            PortalTo.Down -> translationY = out * PortalDownTravel.toPx()
        }
    }
}
