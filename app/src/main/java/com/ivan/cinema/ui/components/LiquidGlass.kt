package com.ivan.cinema.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.SolidColorBrushCompat
import com.ivan.cinema.ui.theme.Space
import com.ivan.cinema.ui.theme.accentBrush
import kotlin.math.PI
import kotlin.math.cos

/*
 * Liquid Glass 引擎 —— 视觉基准：Web 端 liquid-glass（Apple iOS 26 语言）。
 *
 * 五层结构（缺一层就退化成"半透明卡片"）：
 *   ⓪ 外投影   底下压一层柔和暗影 —— 玻璃才"浮"起来（渐变淡出，不是实心轮廓）
 *   ① 本体     半透明白，上亮下暗（光穿过厚度）
 *   ② 内高光   顶边 1px 亮线 + 左上斜向光斑
 *   ③ 边缘折射 一圈渐变描边：迎光亮、背光暗
 *   ④ 内阴影   底边内侧一道暗 —— 玻璃的"厚度感"来自它
 *
 * 前提：底下必须有内容。本 App 的底 = 影片海报模糊环境光（AmbientPosterBackdrop），
 * 玻璃透的就是它 —— 黑底上的玻璃永远是灰矩形。
 */

/**
 * 玻璃五层参数。
 *
 * 注意：liquidGlass 现在是**不透明实色引擎**，实际只读两个字段 ——
 * [dropShadow]（外投影）与 [topLine]（顶边 1px 亮线，由 GlassTopLine 消费）。
 * 其余字段（bodyTop / bodyBottom / edgeHi / edgeLo / sheen / innerShadow）属于早期
 * 半透明玻璃实现，当前引擎不再读取；保留它们只为不破坏 NavLiquid / ImmersiveLiquid
 * 等预设，新代码不要指望这些字段产生效果。
 */
data class LiquidParams(
    val bodyTop: Color = Color(0x42FFFFFF),
    val bodyBottom: Color = Color(0x24FFFFFF),
    val edgeHi: Color = Color(0x8AFFFFFF),
    val edgeLo: Color = Color(0x26FFFFFF),
    val sheen: Color = Color(0x38FFFFFF),
    val topLine: Color = Color(0x8CFFFFFF),
    val innerShadow: Color = Color(0x3D000000),
    val dropShadow: Color = Color(0x66000000),
)

val DefaultLiquid = LiquidParams()

/** 底栏/悬浮条：比卡片厚一档 —— 它压在内容之上，必须压得住下面的海报。 */
val NavLiquid = LiquidParams(
    bodyTop = Color(0x5CFFFFFF),
    bodyBottom = Color(0x3DFFFFFF),
    edgeHi = Color(0xA6FFFFFF),
    edgeLo = Color(0x33FFFFFF),
    sheen = Color(0x42FFFFFF),
    topLine = Color(0xA6FFFFFF),
    innerShadow = Color(0x4D000000),
    dropShadow = Color(0x73000000),
)

val ImmersiveLiquid = LiquidParams(
    bodyTop = Color(0x5CFFFFFF),
    bodyBottom = Color(0x33FFFFFF),
    edgeHi = Color(0x99FFFFFF),
    edgeLo = Color(0x2EFFFFFF),
    sheen = Color(0x40FFFFFF),
    topLine = Color(0xA6FFFFFF),
    innerShadow = Color(0x4D000000),
    dropShadow = Color(0x80000000),
)

private fun fadeSteps(c: Color, steps: Int = 12): List<Color> =
    (0..steps).map { i ->
        val t = i.toDouble() / steps
        val w = (0.5 * (1.0 + cos(t * PI))).toFloat()
        c.copy(alpha = c.alpha * w)
    }

private fun blendSteps(a: Color, b: Color, steps: Int = 12): List<Color> =
    (0..steps).map { i ->
        val t = i.toDouble() / steps
        androidx.compose.ui.graphics.lerp(a, b, (0.5 * (1.0 - cos(t * PI))).toFloat())
    }

/**
 * 卡片面。
 *
 * 原来是半透明「液态玻璃」，但整体观感改成平面深底之后，玻璃就没有可折射的东西了 ——
 * 必然退化成一块灰板（本文件开头那句「玻璃底下必须有内容」说的就是这个）。
 * 而且各页有的用玻璃、有的用实色，体验是割裂的。
 *
 * 现在统一成**不透明实色面 + 1px 顶亮描边**，和腾讯视频/爱奇艺的卡片一致：
 * 文字对比度由设计系统决定，不再随背景内容浮动。
 *
 * [elevated] 保留外投影（浮起元素：底栏、搜索框）；[fill] 可换底色（底栏比卡片亮一档）。
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape,
    params: LiquidParams = DefaultLiquid,
    elevated: Boolean = true,
    fill: Color? = null,
    bordered: Boolean = true,
): Modifier {
    val pal = LocalIVAN.current
    val base = fill ?: pal.surface
    return this
        // ⓪ 外投影：只在浮起元素上（在 clip 之前画，才能落到形状外面）
        .drawBehind {
            if (elevated) {
                val grow = 14.dp.toPx()
                val brush = Brush.verticalGradient(
                    listOf(params.dropShadow, Color.Transparent),
                    startY = 0f,
                    endY = size.height + grow
                )
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(-grow * 0.4f, grow * 0.35f),
                    size = Size(size.width + grow * 0.8f, size.height + grow),
                    cornerRadius = CornerRadius(28.dp.toPx())
                )
            }
        }
        .clip(shape)
        // ① 本体：实色
        .background(base)
        // ② 边缘：顶部一道亮线，底部略暗，给出厚度感
        .drawWithCache {
            if (!bordered) return@drawWithCache onDrawBehind { }
            val stroke = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.12f),
                1f to Color.White.copy(alpha = 0.03f)
            )
            onDrawBehind {
                drawRoundRect(
                    brush = stroke,
                    topLeft = Offset(0.5f, 0.5f),
                    size = Size(size.width - 1f, size.height - 1f),
                    cornerRadius = CornerRadius(24.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        }
}

/** 顶边 1px 亮线（贴在内容之上画）。 */
@Composable
fun BoxScope.GlassTopLine(params: LiquidParams = DefaultLiquid) {
    Box(
        Modifier
            .matchParentSize()
            .drawWithCache {
                val line = Brush.horizontalGradient(
                    listOf(Color.Transparent, params.topLine, Color.Transparent)
                )
                onDrawBehind { drawRect(line, size = Size(size.width, 1.dp.toPx())) }
            }
    )
}

/** 液态玻璃卡片（内容容器）。 */
@Composable
fun LiquidCard(
    modifier: Modifier = Modifier,
    radius: Dp = Radius.xl,
    params: LiquidParams = DefaultLiquid,
    elevated: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = remember(radius) { RoundedCornerShape(radius) }
    Box(modifier.liquidGlass(shape, params, elevated)) {
        GlassTopLine(params)
        content()
    }
}

/** 液态玻璃胶囊（导航项、标签、按钮）。 */
@Composable
fun LiquidPill(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = remember { RoundedCornerShape(Radius.pill) }
    val interaction = remember { MutableInteractionSource() }
    val pal = LocalIVAN.current
    Row(
        modifier
            .then(
                if (onClick == null) Modifier
                else Modifier
                    .pressDip(interaction, to = press.control)
                    .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            )
            .liquidGlass(
                shape,
                if (selected) ImmersiveLiquid else DefaultLiquid,
                elevated = false,
                // 未选中的胶囊必须完全透明，否则底栏会变成一排实心方块
                fill = if (selected) pal.surfaceRaised else Color.Transparent,
                bordered = selected
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content
    )
}

/**
 * 描边 / 选中胶囊的唯一实现 —— 线路、选集、追剧、收藏等全部走这里。
 *
 * 全 App 只有两种状态：未选中 = 透明底 + 1px 发丝描边；选中 = 香槟金填充。
 * 之前各处自己拼 background / border，出现「有的有描边、有的只有半透明白底」的分裂，
 * 统一到这里后状态语义只由 [selected] 决定。
 *
 * 命中区由调用方通过 [verticalPadding] 保证（labelLarge 18dp + 15*2 = 48dp）。
 *
 * @param onLongClick 传入时改用 combinedClickable（选集长按下载）。
 * @param selectedFill 选中填充，默认走 [accentBrush]；需要实色时可覆盖。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectPill(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    horizontalPadding: Dp = Space.md,
    verticalPadding: Dp = 15.dp,
    selectedFill: Brush? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val pal = LocalIVAN.current
    val shape = remember { RoundedCornerShape(Radius.pill) }
    val interaction = remember { MutableInteractionSource() }
    // semantics 里的 selected 是接收者属性，先取到局部变量避免与参数同名歧义
    val sel = selected
    Box(
        modifier
            .pressDip(interaction, to = press.control)
            .clip(shape)
            .background(
                if (sel) (selectedFill ?: accentBrush(pal))
                else SolidColorBrushCompat(Color.Transparent)
            )
            // 选中时由填充本身表达状态，未选中才需要描边把它从背景里拎出来
            .then(if (sel) Modifier else Modifier.border(1.dp, pal.hairline, shape))
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        enabled = enabled,
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(
                        enabled = enabled,
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                }
            )
            .semantics { this.selected = sel }
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** 横条（搜索框、顶栏）。 */
@Composable
fun LiquidBar(
    modifier: Modifier = Modifier,
    radius: Dp = Radius.pill,
    elevated: Boolean = true,
    params: LiquidParams = DefaultLiquid,
    fill: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = remember(radius) { RoundedCornerShape(radius) }
    Box(modifier.liquidGlass(shape, params, elevated, fill)) {
        GlassTopLine(params)
        content()
    }
}

/** 悬浮胶囊导航底栏：图标 + 标签，选中项有一档更亮的底。 */
@Composable
fun LiquidNavBar(
    modifier: Modifier = Modifier,
    items: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val pal = LocalIVAN.current
    LiquidBar(
        modifier = modifier,
        radius = Radius.pill,
        elevated = true,
        params = NavLiquid,
        // 底栏比普通卡片亮一档，才能在平面深底上「浮」起来
        fill = pal.surfaceRaised
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            items.forEachIndexed { i, item ->
                val on = i == selected
                val interaction = remember { MutableInteractionSource() }
                Row(
                    Modifier
                        .pressDip(interaction, to = press.control)
                        .clip(RoundedCornerShape(Radius.pill))
                        // 选中项：香槟金 14% 淡底 —— 底栏是全 App 唯一允许用强调色标状态的地方
                        .background(if (on) pal.accent.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable(interactionSource = interaction, indication = null) { onSelect(i) }
                        // 14dp 让点击目标达到 48dp（原来 8dp 只有 36dp）
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    androidx.compose.material3.Icon(
                        item.icon,
                        // 图标已经有常驻文字标签，读屏再念一遍会变成「首页 首页」
                        contentDescription = null,
                        // 选中态用强调色（#D9BC82 在 surfaceRaised 上 >4.5:1）
                        tint = if (on) pal.accent else Color.White.copy(alpha = 0.80f),
                        modifier = Modifier.height(20.dp)
                    )
                    // 文字常驻（未选中也显示）—— 只显示图标用户认不出哪个是「下载」
                    androidx.compose.material3.Text(
                        item.label,
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        // 0.62 在玻璃上只有 3.6:1，够不到正文 4.5:1；选中态直接用强调色
                        color = if (on) pal.accent else Color.White.copy(alpha = 0.80f)
                    )
                }
            }
        }
    }
}

data class NavItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
