package com.ivan.cinema.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * IVAN CINEMA design system —— 「studio dark」。
 *
 * 画廊般安静的低彩度炭底 + 唯一的香槟金强调色（只给状态：激活导航、
 * 播放指示、进度）。没有纯黑、没有霓虹、没有第二强调色。
 * 层次来自抬升 + 发丝线，不靠发光。
 */

val BrandGold = Color(0xFFE8B23A)

data class IVANPalette(
    val canvas: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val ink: Color,
    val inkMuted: Color,
    val hairline: Color,
    val accent: Color,
    val accentInk: Color,
    val danger: Color,
    /** 品牌色。只给字标和徽章用，不表示状态。 */
    val brand: Color,
    /**
     * 玻璃面上的文字色。
     *
     * 玻璃底本身是「白 26% 叠在 canvas 上」，比 canvas 亮得多，
     * inkMuted / accent / danger 直接放上去只有 2.4–3.7:1，够不到正文 4.5:1。
     * 这三只是同色相的加亮版本，专供玻璃/卡片面使用。
     */
    val inkMutedOnGlass: Color,
    val accentOnGlass: Color,
    val dangerOnGlass: Color,
)

val DarkPalette = IVANPalette(
    canvas = Color(0xFF0C0B10),
    surface = Color(0xFF17171C),
    surfaceRaised = Color(0xFF24242B),
    ink = Color(0xFFF5F3EF),
    inkMuted = Color(0xFFA9A5B4),
    hairline = Color(0x1FFFFFFF),
    // 状态金比品牌金更亮一档，深底上才站得住（明度解决可见度，不换色相）。
    accent = Color(0xFFD9BC82),
    accentInk = Color(0xFF241C08),
    danger = Color(0xFFD96A6A),
    brand = BrandGold,
    inkMutedOnGlass = Color(0xFFD5D2DE),
    accentOnGlass = Color(0xFFE8CE9A),
    dangerOnGlass = Color(0xFFFFB4B4),
)

val LightPalette = IVANPalette(
    canvas = Color(0xFFFAFAF9),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFEFEDE8),
    ink = Color(0xFF1C1917),
    inkMuted = Color(0xFF57534E),
    hairline = Color(0x14000000),
    accent = Color(0xFF8A6A2E),
    accentInk = Color(0xFFFFFFFF),
    danger = Color(0xFFB04A4A),
    brand = Color(0xFF8A6716),
    inkMutedOnGlass = Color(0xFF57534E),
    accentOnGlass = Color(0xFF6B5122),
    dangerOnGlass = Color(0xFF8A2F2F),
)

/** 全 App 唯一允许的"有色填充"：同色 16% 明度差两档，给体积不给第二色相。 */
fun accentBrush(pal: IVANPalette): Brush =
    Brush.linearGradient(listOf(pal.accent, lerp(pal.accent, pal.ink, 0.16f)))

/** 实色 Brush —— 让"选中渐变 / 未选中实色"分支类型统一为 Brush。 */
fun SolidColorBrushCompat(color: Color): Brush = Brush.linearGradient(listOf(color, color))

/*
 * 4px base scale —— 纵向梯只有四档：xs4 / sm8 / md12 / block24。
 * lg/xl 及以上只留给横向排布与容器内边距，不出现在两个元素的垂直间距上。
 */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val block = 24.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}

object Radius {
    val sm = 8.dp
    val md = 14.dp
    val lg = 20.dp
    val xl = 26.dp
    val sheet = 32.dp
    val pill = 999.dp
}

/** 字距梯单调：字号越大越松；labelSmall 是 Mono 的元数据声音。 */
val IVANType = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.40.sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.24.sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.16.sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.10.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.15.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.10.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.35.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace),
)

/** Mono，用于时间戳/计数。 */
val MetaMono = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, letterSpacing = 0.6.sp)

/*
 * Motion tokens —— 三档物理弹簧（iOS response/damping 映射）：
 *   general (0.40s, 1.00) 临界阻尼：铬件、导航
 *   sheet   (0.30s, 0.80) 轻微过冲：面板、按压回弹
 *   snappy  (0.25s, 1.00) 绝不越界：小状态翻转
 *   jelly   (0.38s, 0.70) 整屏落位的"刹住车"
 *   pop     (0.40s, 0.30) 只给 20dp 级小物件的卡通弹跳
 *
 * 纪律：任何地方不许手写 spring()；一切动效取值器都是 @Composable（要订阅减少动效）；
 * 位移/缩放/透明度用弹簧；离场用加速曲线（motionExit）。
 */
enum class MotionKind(val stiffness: Float, val dampingRatio: Float) {
    General(246.74f, 1.0f),
    Sheet(438.65f, 0.8f),
    Snappy(631.65f, 1.0f),
    Jelly(380f, 0.70f),
    Pop(400f, 0.30f),
}

object Springs {
    val general: SpringSpec<Float> = spring(stiffness = MotionKind.General.stiffness, dampingRatio = MotionKind.General.dampingRatio)
    val sheet: SpringSpec<Float> = spring(stiffness = MotionKind.Sheet.stiffness, dampingRatio = MotionKind.Sheet.dampingRatio)
    val snappy: SpringSpec<Float> = spring(stiffness = MotionKind.Snappy.stiffness, dampingRatio = MotionKind.Snappy.dampingRatio)
}

private fun <T> settle(kind: MotionKind): SpringSpec<T> = spring(
    stiffness = kind.stiffness,
    // 减少动效：去掉过冲，保留平稳落位（不是"不动"，而是"不晃"）。
    dampingRatio = if (MotionPrefs.reduce) 1f else kind.dampingRatio,
)

@Composable
fun motionFloat(kind: MotionKind): SpringSpec<Float> = settle(kind)

@Composable
fun motionColor(kind: MotionKind): SpringSpec<Color> = settle(kind)

@Composable
fun motionFade(
    durationMs: Int = 200,
    easing: Easing = FastOutSlowInEasing,
): FiniteAnimationSpec<Float> = if (MotionPrefs.reduce) snap() else tween(durationMs, easing = easing)

/** 离场：加速离开屏幕，不"舍不得走"。 */
@Composable
fun motionExit(durationMs: Int = 200): FiniteAnimationSpec<Float> =
    if (MotionPrefs.reduce) snap() else tween(durationMs, easing = FastOutLinearInEasing)

@Composable
fun motionSpring(spec: SpringSpec<Float>): SpringSpec<Float> =
    if (MotionPrefs.reduce) {
        spring(stiffness = spec.stiffness, dampingRatio = 1f)
    } else {
        spec
    }

val LocalIVAN = staticCompositionLocalOf { DarkPalette }

@Composable
fun IVANTheme(content: @Composable () -> Unit) {
    val palette = DarkPalette
    val scheme = darkColorScheme(
        background = palette.canvas,
        surface = palette.surface,
        surfaceVariant = palette.surfaceRaised,
        onBackground = palette.ink,
        onSurface = palette.ink,
        onSurfaceVariant = palette.inkMuted,
        primary = palette.accent,
        onPrimary = palette.accentInk,
        secondary = palette.inkMuted,
        outline = palette.hairline,
        error = palette.danger,
    )
    CompositionLocalProvider(LocalIVAN provides palette) {
        MaterialTheme(colorScheme = scheme, typography = IVANType, content = content)
    }
}
