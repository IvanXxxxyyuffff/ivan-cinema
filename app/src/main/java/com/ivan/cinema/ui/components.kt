package com.ivan.cinema.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.ivan.cinema.data.MergedVod
import com.ivan.cinema.ui.components.press
import com.ivan.cinema.ui.components.pressDip
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.MotionPrefs
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.Space

/**
 * 海报卡：图片直出（等宽网格的列宽就是卡宽，排版天然对齐）。
 * 按压统一 pressDip；无海报走 FilmTile 占位砖。
 */
@Composable
fun PosterCard(
    item: MergedVod,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pal = LocalIVAN.current
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            // 海报砖是 card 档目标（默认 control 对整块海报太浅，点下去几乎看不出）
            .pressDip(interaction, to = press.card)
            .clip(RoundedCornerShape(Radius.md))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(Radius.md))
                // 加载期保留浅渐变占位；加载失败退到可见的 FilmTile，不留匿名空盒
                .background(
                    Brush.verticalGradient(
                        listOf(pal.surfaceRaised, pal.surfaceRaised.copy(alpha = 0.68f))
                    )
                )
        ) {
            if (item.pic.isNotEmpty()) {
                SubcomposeAsyncImage(
                    model = item.pic,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    // loading 槽：让占位"呼吸"起来（0.45→0.9 透明度循环），
                    // 一眼就能读出"正在加载"，而不是一块说不清的灰盒子；
                    // 失败仍退到可见的 FilmTile，两者不会混淆。
                    loading = {
                        val face = Brush.verticalGradient(
                            listOf(
                                pal.surfaceRaised,
                                pal.surfaceRaised.copy(alpha = 0.68f)
                            )
                        )
                        // 减少动效：无限「呼吸」循环必须停。镜像 PlayingBars 的写法 ——
                        // 静态占位面仍然看得见「在加载」，只是不再每 900ms 闪一次，
                        // 否则开了减少动效的用户照样被满屏循环闪烁影响。
                        if (MotionPrefs.reduce) {
                            Box(Modifier.fillMaxSize().background(face))
                        } else {
                            val breathe = rememberInfiniteTransition(label = "posterLoading")
                            val a by breathe.animateFloat(
                                initialValue = 0.45f,
                                targetValue = 0.9f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(durationMillis = 900, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "posterLoadingAlpha"
                            )
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = a }
                                    .background(face)
                            )
                        }
                    },
                    error = { FilmTile(Modifier.fillMaxSize()) }
                )
            } else {
                FilmTile(Modifier.fillMaxSize())
            }
            val remark = item.hits.firstOrNull()?.remarks
            if (!remark.isNullOrEmpty()) {
                Text(
                    text = remark,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = pal.badgeInk,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(pal.badge)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Text(
            text = item.name,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            color = pal.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = Space.sm)
        )
        Text(
            text = buildString {
                if (item.year.isNotEmpty()) append(item.year)
                if (item.hits.size > 1) {
                    if (isNotEmpty()) append(" · ")
                    append(item.hits.size)
                    append(" 源")
                }
            },
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = pal.inkMuted.copy(alpha = 0.72f),
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 2.dp, bottom = Space.sm)
        )
    }
}

/**
 * 无海报的统一占位砖：低彩度斜向渐变 + 顶部内高光 + 影片图形。
 * 不透明（替的是一张图），每一块长得一样 —— 安静的位置标记，不参与竞争。
 */
@Composable
fun FilmTile(modifier: Modifier = Modifier) {
    val pal = LocalIVAN.current
    // 上亮下暗 —— 按明度排，不按名字排。
    val face = listOf(pal.surface, pal.surfaceRaised).sortedByDescending { it.luminance() }
    Box(
        modifier
            .background(Brush.linearGradient(face)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(pal.hairline, pal.ink.copy(alpha = 0.06f)))),
        )
        Icon(
            Icons.Rounded.Movie,
            contentDescription = null,
            tint = pal.inkMuted.copy(alpha = 0.35f),
            modifier = Modifier
                .fillMaxSize(0.34f)
                .graphicsLayer { alpha = 0.5f }
        )
    }
}

/** 骨架网格：与真实 LazyVerticalGrid 完全同构（同列数/边距/间距），保证排版一致。 */
@Composable
fun PosterGridSkeleton(columns: Int, count: Int = 15, modifier: Modifier = Modifier) {
    val pal = LocalIVAN.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(Space.lg),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.block),
        userScrollEnabled = false,
        modifier = modifier.fillMaxSize()
    ) {
        items(count) {
            Box(
                Modifier
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(pal.surfaceRaised.copy(alpha = 0.6f))
            )
        }
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    val pal = LocalIVAN.current
    Box(modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = pal.inkMuted)
    }
}
