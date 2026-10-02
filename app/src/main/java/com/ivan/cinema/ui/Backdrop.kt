package com.ivan.cinema.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ivan.cinema.ui.theme.DarkPalette

/**
 * 全 App 统一背景：平面深底 + 顶部一层极淡的冷色渐隐。
 *
 * 这里原来是「正在浏览影片的海报重度模糊铺满全屏」，实拍确认了三个问题：
 *   1. 图文对比度变成数据的函数 —— 浅色海报上的文字直接糊掉，深色海报上又什么都看不见；
 *   2. 下载页/设置页的背景是一张跟页面内容毫无关系的剧照（用户根本没下过那部片），纯噪音，
 *      而且会把视线从空态文案上抢走；
 *   3. 每屏一次全屏 180dp 模糊 + 三个径向色斑，白付 GPU，低端机上是实打实的开销。
 *
 * 腾讯视频 / 爱奇艺 / 哔哩哔哩 / Netflix 都是纯深底 + 让内容本身当唯一图像，
 * 这样文字对比度由设计系统决定，而不是由「恰好先加载出哪张海报」决定。改成同一路子。
 */
@Composable
fun AmbientPosterBackdrop(@Suppress("UNUSED_PARAMETER") posterUrl: String?) {
    FlatBackdrop()
}

/**
 * 详情 / 分类 / 筛选 / 登录页的背景。
 *
 * 详情页的海报已经作为 hero 大图出现过了，这里再糊一张同一张图既重复又压不住文字，
 * 所以同样走平面深底。参数保留只是为了不改调用点。
 */
@Composable
fun PosterBlurBackdrop(
    @Suppress("UNUSED_PARAMETER") posterUrl: String?,
    @Suppress("UNUSED_PARAMETER") blurDp: androidx.compose.ui.unit.Dp = 160.dp,
    @Suppress("UNUSED_PARAMETER") posterAlpha: Float = 0.5f
) {
    FlatBackdrop()
}

@Composable
private fun FlatBackdrop() {
    val base = DarkPalette.canvas
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF14121A),
                    0.35f to base,
                    1f to base
                )
            )
    )
}
