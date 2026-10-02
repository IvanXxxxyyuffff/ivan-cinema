package com.ivan.cinema.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ivan.cinema.ui.theme.Ambience
import com.ivan.cinema.ui.theme.GlassPalette
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.MotionKind
import com.ivan.cinema.ui.theme.MotionPrefs
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.Space
import com.ivan.cinema.ui.theme.ambience
import com.ivan.cinema.ui.theme.glassPalette
import com.ivan.cinema.ui.theme.motionExit
import com.ivan.cinema.ui.theme.motionFade
import com.ivan.cinema.ui.theme.motionFloat
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos

/*
 * 液态玻璃 —— 忠实移植自 Aurora 的 LiquidGlass 体系。
 *
 * 三条纪律：
 *   ① 渐变一律余弦采样多档（两端导数为 0），线性两档在宽卡上就是马赫带；
 *   ② 不用 Modifier.shadow —— 投影内部是实心填充，面板一透它就透出来（方形片成因）；
 *   ③ 笔刷按 palette 缓存 + drawWithCache，绝不每帧现算。
 */

/** 平滑衰减序列：同色降 alpha，余弦采样，全程无折点。 */
private fun smoothFade(c: Color, steps: Int = 12): List<Color> =
    (0..steps).map { i ->
        val t = i.toDouble() / steps
        val w = (0.5 * (1.0 + cos(t * PI))).toFloat()
        c.copy(alpha = c.alpha * w)
    }

/** 两色余弦缓动过渡：两端导数都是 0，中间没有折点。 */
private fun cosineBlend(a: Color, b: Color, steps: Int = 12): List<Color> =
    (0..steps).map { i ->
        val t = i.toDouble() / steps
        lerp(a, b, (0.5 * (1.0 - cos(t * PI))).toFloat())
    }

/** 中间最亮、两端归于同色透明。顶边亮线用它。 */
private fun centerBump(c: Color, steps: Int = 8): List<Color> {
    val half = (0..steps).map { i ->
        val t = i.toDouble() / steps
        val w = (0.5 * (1.0 - cos(t * PI))).toFloat()
        c.copy(alpha = c.alpha * w)
    }
    return half + half.reversed().drop(1)
}

/** 一套调色板派生的全部笔刷 —— 只取决于 GlassPalette，算一次一直复用。 */
private class GlassBrushes(g: GlassPalette) {
    /** ① 本体：上清下浊，余弦多档。 */
    val body: Brush = Brush.verticalGradient(cosineBlend(g.bodyTop, g.bodyBottom))

    /** ② 边缘折射：迎光亮 → 背光暗。 */
    val edge: Brush = Brush.linearGradient(cosineBlend(g.edgeHi, g.edgeLo))
    val edgeStroke: BorderStroke = BorderStroke(1.dp, edge)

    /** ③ 顶边 1px 亮线。 */
    val topLine: Brush = Brush.horizontalGradient(centerBump(g.topLine))

    /** ④ 外发光（画在卡内、内容之前）。 */
    val glow: Brush = Brush.verticalGradient(smoothFade(g.glow, 14))

    /** 高光色表（径向 Brush 圆心/半径要按 size 算，只有色表能在这儿缓存）。 */
    val sheen: List<Color> = smoothFade(g.sheen)
    val sheenDim: List<Color> = smoothFade(g.sheen.copy(alpha = g.sheen.alpha * 0.55f))
}

private val GlassBrushCache = ConcurrentHashMap<GlassPalette, GlassBrushes>()

@Composable
private fun glassBrushes(g: GlassPalette): GlassBrushes =
    remember(g) { GlassBrushCache.getOrPut(g) { GlassBrushes(g) } }

/** 高光：两团角部光，半径绑窄边（minDimension），左上主光 + 右下副光。 */
private fun Modifier.sheen(b: GlassBrushes): Modifier = drawWithCache {
    val short = size.minDimension
    val r1 = short * 0.58f
    val main = Brush.radialGradient(
        colors = b.sheen,
        center = Offset(size.width * 0.14f, -r1 * 0.30f),
        radius = r1,
    )
    val r2 = short * 0.46f
    val sub = Brush.radialGradient(
        colors = b.sheenDim,
        center = Offset(size.width * 0.94f, size.height * 1.06f),
        radius = r2,
    )
    onDrawBehind {
        drawRect(main)
        drawRect(sub)
    }
}

/**
 * 玻璃面板。tint 是叠在玻璃上的一层"色偏"，封顶 0.22 —— 是着色不是填充。
 * 没有 shadow：层次由边缘折射承担。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = Radius.xl,
    tint: Color? = null,
    glow: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val g = glassPalette()
    val b = glassBrushes(g)
    val shape = RoundedCornerShape(radius)
    val ta = tint?.alpha?.coerceAtMost(0.22f)
    Box(
        modifier
            .clip(shape)
            .background(b.body)
            .then(
                if (tint == null || ta == null || ta <= 0f) Modifier else Modifier.background(
                    Brush.verticalGradient(
                        listOf(tint.copy(alpha = ta * 0.55f), tint.copy(alpha = ta * 0.22f)),
                    ),
                ),
            )
            .sheen(b)
            .border(b.edgeStroke, shape),
    ) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(1.dp)
                .background(b.topLine),
        )
        if (glow) {
            // matchParentSize：在高度无界的父（LazyColumn item）里也成立
            Box(
                Modifier
                    .matchParentSize()
                    .background(b.glow),
            )
        }
        content()
    }
}

/** 玻璃胶囊：素胶囊是一层平色（矮东西再叠渐变只会多出两条折点）。 */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    radius: Dp = Radius.pill,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val g = glassPalette()
    val b = glassBrushes(g)
    val shape = RoundedCornerShape(radius)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .then(
                if (onClick == null) Modifier
                else Modifier
                    .pressDip(interaction, to = press.control)
                    .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            )
            .clip(shape)
            .background(if (selected) g.bodyTop else g.bodyBottom)
            .sheen(b)
            .border(b.edgeStroke, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content,
    )
}

/** 玻璃长条：顶栏、搜索框 —— 永远贴在内容之上，所以边缘更亮、顶线更硬。 */
@Composable
fun GlassBar(
    modifier: Modifier = Modifier,
    radius: Dp = Radius.xl,
    shapeOverride: Shape? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val g = glassPalette()
    val b = glassBrushes(g)
    val shape = shapeOverride ?: RoundedCornerShape(radius)
    Box(
        modifier
            .clip(shape)
            .background(b.body)
            .sheen(b)
            .border(b.edgeStroke, shape),
    ) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(1.dp)
                .background(b.topLine),
        )
        content()
    }
}

/** 三团色斑参数（颜色 + 相对圆心 + 相对半径）。 */
private data class Blob(val color: Color, val cx: Float, val cy: Float, val rw: Float)

private fun ambienceBlobs(amb: Ambience) = listOf(
    Blob(amb.a.copy(alpha = 0.80f), 0.16f, 0.10f, 1.00f),
    Blob(amb.b.copy(alpha = 0.70f), 0.94f, 0.34f, 0.95f),
    Blob(amb.c.copy(alpha = 0.60f), 0.30f, 0.92f, 1.05f),
)

/** 氛围场：三团半径接近屏宽的色斑 —— 大到"看不出是三个圆"，连成一片才是色域。 */
private fun Modifier.ambienceField(amb: Ambience): Modifier = drawWithCache {
    val spots = ambienceBlobs(amb).map { b ->
        val center = Offset(size.width * b.cx, size.height * b.cy)
        val r = size.width * b.rw
        Triple(
            Brush.radialGradient(colors = smoothFade(b.color, 16), center = center, radius = r),
            center,
            r,
        )
    }
    onDrawBehind {
        for ((brush, center, r) in spots) {
            drawCircle(brush = brush, radius = r, center = center)
        }
    }
}

/**
 * 全局氛围场 —— 始终存在的那一层。玻璃透的就是它。
 * 上下压暗用**底色自身的 alpha**，不用透明黑（透明黑会把整屏拦腰压出灰带）。
 */
@Composable
fun LiquidBackdrop(modifier: Modifier = Modifier) {
    val amb = ambience()
    Box(modifier.fillMaxSize().background(amb.base)) {
        Box(Modifier.fillMaxSize().ambienceField(amb))
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to amb.base.copy(alpha = amb.scrimTop),
                        0.30f to amb.base.copy(alpha = 0f),
                        0.68f to amb.base.copy(alpha = 0f),
                        1f to amb.base.copy(alpha = amb.scrimBottom),
                    ),
                ),
        )
    }
}

/** 章节抬头。 */
@Composable
fun SectionHeader(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val pal = LocalIVAN.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = pal.ink)
        Spacer(Modifier.width(Space.sm))
        Text(
            eyebrow.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = pal.inkMuted.copy(alpha = 0.7f),
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** 构图式空态：失败态给的是出路（可点重试），不是「稍后再试」。 */
@Composable
fun EmptyState(
    eyebrow: String,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val pal = LocalIVAN.current
    Column(
        modifier
            .fillMaxWidth()
            // 原来没有横向内边距、且左对齐，在下载页会顶到屏幕左缘并被裁掉半个图标
            .padding(horizontal = Space.xl, vertical = Space.xl)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(pal.accent.copy(alpha = 0.24f), pal.accent.copy(alpha = 0f)),
                    ),
                ),
        )
        Spacer(Modifier.height(Space.md))
        Text(
            eyebrow.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = pal.inkMutedOnGlass,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = pal.ink,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = pal.inkMutedOnGlass,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/** 极细进度线：fraction 是函数，取值推迟到绘制期。 */
@Composable
fun ThinProgress(
    fraction: () -> Float,
    modifier: Modifier = Modifier,
    color: Color? = null,
    track: Color? = null,
) {
    val pal = LocalIVAN.current
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(track ?: pal.hairline),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = fraction().coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .background(color ?: pal.accent),
        )
    }
}

/** 播放中的三根律动条（graphicsLayer scaleY，零重组）。 */
@Composable
fun PlayingBars(
    active: Boolean,
    modifier: Modifier = Modifier,
    barWidth: Dp = 2.dp,
    height: Dp = 12.dp,
) {
    val pal = LocalIVAN.current
    androidx.compose.animation.AnimatedVisibility(
        visible = active,
        enter = androidx.compose.animation.fadeIn(motionFade(180)) +
            androidx.compose.animation.scaleIn(motionFloat(MotionKind.Sheet), transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)),
        exit = androidx.compose.animation.fadeOut(motionExit(120)) +
            androidx.compose.animation.scaleOut(motionFloat(MotionKind.Snappy), transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)),
        modifier = modifier,
    ) {
        if (MotionPrefs.reduce) {
            Row(
                Modifier.height(height),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                listOf(0.55f, 1.0f, 0.7f).forEach { peak ->
                    Box(
                        Modifier
                            .width(barWidth)
                            .height(height * peak)
                            .clip(RoundedCornerShape(1.dp))
                            .background(pal.accent),
                    )
                }
            }
        } else {
            val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "bars")
            Row(
                Modifier.height(height),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                listOf(0.55f, 1.0f, 0.7f).forEachIndexed { i, peak ->
                    val v = transition.animateFloat(
                        initialValue = 0.28f,
                        targetValue = peak,
                        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                            animation = androidx.compose.animation.core.tween(
                                durationMillis = 620 + i * 170,
                                easing = androidx.compose.animation.core.FastOutSlowInEasing,
                            ),
                            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                        ),
                        label = "bar$i",
                    )
                    Box(
                        Modifier
                            .width(barWidth)
                            .height(height)
                            .graphicsLayer {
                                scaleY = v.value
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            }
                            .clip(RoundedCornerShape(1.dp))
                            .background(pal.accent),
                    )
                }
            }
        }
    }
}

/** 扫光细线：解析/加载用（DESIGN 口径：不用转圈）。 */
@Composable
fun ScannerLine(modifier: Modifier = Modifier, color: Color? = null) {
    val pal = LocalIVAN.current
    // 减少动效：不跑无限扫光，静态留一条居中亮线，仍然看得见"在加载"。
    if (MotionPrefs.reduce) {
        Box(
            modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, color ?: pal.accent, Color.Transparent)
                    )
                )
        )
        return
    }
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "scan")
    val x by transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "x"
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(pal.hairline)
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.35f)
                .height(2.dp)
                .graphicsLayer { translationX = x * size.width * 2.6f }
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            color ?: pal.accent,
                            Color.Transparent
                        )
                    )
                )
        )
    }
}
