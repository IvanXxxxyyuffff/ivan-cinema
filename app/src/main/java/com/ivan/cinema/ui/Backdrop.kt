package com.ivan.cinema.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ivan.cinema.ui.theme.ambience

/**
 * 详情页/分类页专用的**不透明**背景：该片海报大图模糊照亮整页。
 * 与全局氛围场不同，它必须完全遮住下层的上一页（否则两层内容叠在一起）。
 */
@Composable
fun PosterBlurBackdrop(posterUrl: String?, blurDp: androidx.compose.ui.unit.Dp = 160.dp, posterAlpha: Float = 0.5f) {
    val base = com.ivan.cinema.ui.theme.DarkPalette.canvas
    Box(Modifier.fillMaxSize().background(base)) {
        if (!posterUrl.isNullOrEmpty()) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = posterAlpha,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(blurDp)
                        else Modifier
                    )
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to base.copy(alpha = 0.35f),
                            0.42f to base.copy(alpha = 0.68f),
                            1f to base.copy(alpha = 0.88f)
                        )
                    )
            )
        }
    }
}

/**
 * 环境光背景 —— 用**正在浏览的影片海报**照亮整个界面。
 *
 * 这是 Liquid Glass 的前提：玻璃是"看着背景做出来的"。海报（大图、高起伏、
 * 有色相）被重度模糊后铺满全屏，玻璃浮其上才有真实的透与折射；
 * 之前的近黑色斑背景永远只能透出一块灰。
 *
 * 结构：底色 → 海报模糊层（API31+，低版本自动跳过）→ 色斑（补色相）→ 上下压暗。
 */
@Composable
fun AmbientPosterBackdrop(posterUrl: String?) {
    val amb = ambience()
    Box(Modifier.fillMaxSize().background(amb.base)) {
        if (!posterUrl.isNullOrEmpty()) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.42f,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Modifier.blur(180.dp)
                        } else Modifier
                    )
            )
            // 压暗只到「文字站得住」为止：压太狠玻璃就无物可透，整屏退化成灰板
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to amb.base.copy(alpha = 0.72f),
                            0.40f to amb.base.copy(alpha = 0.64f),
                            1f to amb.base.copy(alpha = 0.80f)
                        )
                    )
            )
        }
        // 色斑补色相（海报缺失时的兜底，也是有色相变化的第二层）
        Box(
            Modifier
                .fillMaxSize()
                .drawAmbience(amb)
        )
    }
}

private fun Modifier.drawAmbience(amb: com.ivan.cinema.ui.theme.Ambience): Modifier =
    this.drawWithCache {
        val blobs = listOf(
            Triple(amb.a.copy(alpha = 0.45f), 0.16f to 0.10f, 1.0f),
            Triple(amb.b.copy(alpha = 0.38f), 0.94f to 0.34f, 0.95f),
            Triple(amb.c.copy(alpha = 0.34f), 0.30f to 0.92f, 1.05f),
        ).map { (color, c, rw) ->
            val center = Offset(size.width * c.first, size.height * c.second)
            val r = size.width * rw
            Triple(
                Brush.radialGradient(
                    colors = (0..16).map { i ->
                        val t = i.toDouble() / 16
                        color.copy(
                            alpha = color.alpha *
                                (0.5 * (1.0 + kotlin.math.cos(t * kotlin.math.PI))).toFloat()
                        )
                    },
                    center = center,
                    radius = r
                ),
                center,
                r
            )
        }
        onDrawBehind {
            for ((brush, center, r) in blobs) {
                drawCircle(brush = brush, radius = r, center = center)
            }
        }
    }
