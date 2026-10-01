package com.ivan.cinema.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ivan.cinema.ui.theme.Radius
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

/** 玻璃五层参数。 */
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
 * 液态玻璃面板。
 * [elevated] 打开外投影（浮起元素：导航、搜索框、播放控制）；静态内容卡可关掉省渲染。
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape,
    params: LiquidParams = DefaultLiquid,
    elevated: Boolean = true,
): Modifier = this
    .drawBehind {
        // ⓪ 外投影：柔和暗影（渐变淡出，非实心轮廓 —— 面板透明不会透出方片）
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
    // ① 本体
    .background(Brush.verticalGradient(blendSteps(params.bodyTop, params.bodyBottom)))
    // ④ 内阴影：底边内侧一道暗
    .drawWithCache {
        val inner = Brush.verticalGradient(
            0f to Color.Transparent,
            0.78f to Color.Transparent,
            1f to params.innerShadow
        )
        onDrawBehind { drawRect(inner) }
    }
    // ② 高光：左上斜向光斑
    .drawWithCache {
        val r = size.minDimension * 0.75f
        val main = Brush.radialGradient(
            colors = fadeSteps(params.sheen),
            center = Offset(size.width * 0.12f, -r * 0.25f),
            radius = r
        )
        onDrawBehind { drawRect(main) }
    }
    // ③ 边缘折射描边
    .drawWithCache {
        val stroke = Brush.verticalGradient(blendSteps(params.edgeHi, params.edgeLo))
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
    Row(
        modifier
            .then(
                if (onClick == null) Modifier
                else Modifier
                    .pressDip(interaction, to = 0.96f)
                    .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            )
            .liquidGlass(shape, if (selected) ImmersiveLiquid else DefaultLiquid, elevated = false)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content
    )
}

/** 液态玻璃横条（搜索框、顶栏）。 */
@Composable
fun LiquidBar(
    modifier: Modifier = Modifier,
    radius: Dp = Radius.pill,
    elevated: Boolean = true,
    params: LiquidParams = DefaultLiquid,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = remember(radius) { RoundedCornerShape(radius) }
    Box(modifier.liquidGlass(shape, params, elevated)) {
        GlassTopLine(params)
        content()
    }
}

/** 悬浮胶囊导航底栏：图标 + 标签，选中项有液态高亮底。 */
@Composable
fun LiquidNavBar(
    modifier: Modifier = Modifier,
    items: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    LiquidBar(modifier = modifier, radius = Radius.pill, elevated = true, params = NavLiquid) {
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
                        .pressDip(interaction, to = 0.94f)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(if (on) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable(interactionSource = interaction, indication = null) { onSelect(i) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    androidx.compose.material3.Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = if (on) Color.White else Color.White.copy(alpha = 0.62f),
                        modifier = Modifier.height(20.dp)
                    )
                    // 文字常驻（未选中也显示）—— 只显示图标用户认不出哪个是「下载」
                    androidx.compose.material3.Text(
                        item.label,
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        color = if (on) Color.White else Color.White.copy(alpha = 0.62f)
                    )
                }
            }
        }
    }
}

data class NavItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
