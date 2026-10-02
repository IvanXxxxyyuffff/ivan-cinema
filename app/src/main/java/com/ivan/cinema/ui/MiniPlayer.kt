package com.ivan.cinema.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ivan.cinema.db.WatchEntry
import com.ivan.cinema.ui.components.press
import com.ivan.cinema.ui.components.pressDip
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.Space

/**
 * 迷你播放条。
 *
 * 从播放页退出来之后，根页底部保留一条「刚才在看什么」—— 点一下直接回到那一集、那个位置。
 *
 * 这里**不做**真正的悬浮小窗视频：那要求播放器实例跨 Activity 存活，要额外处理音频焦点
 * 抢占、后台耗电、以及和其它播放器打架；对「快速回到影片」这个诉求，一条带缩略图 +
 * 集数 + 进度的可点条就够，而且不会在用户不想看的时候偷偷出声。
 */
@Composable
fun MiniPlayerBar(
    entry: WatchEntry,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pal = LocalIVAN.current
    val openInteraction = remember { MutableInteractionSource() }
    val closeInteraction = remember { MutableInteractionSource() }

    Row(
        modifier
            .fillMaxWidth()
            .pressDip(openInteraction, to = press.card)
            .clip(RoundedCornerShape(Radius.lg))
            .background(pal.surfaceRaised)
            .clickable(interactionSource = openInteraction, indication = null, onClick = onOpen)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(pal.surface)
        ) {
            if (entry.pic.isNotEmpty()) {
                AsyncImage(
                    model = entry.pic,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // 播放角标：暗示「点这里继续看」
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(26.dp)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(Modifier.width(Space.md))

        Column(Modifier.weight(1f)) {
            Text(
                entry.name.ifBlank { "正在播放" },
                style = MaterialTheme.typography.titleMedium,
                color = pal.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(
                        entry.episodeName.ifBlank { "第${entry.episodeIndex + 1}集" }
                    )
                    if (entry.positionMs > 0) {
                        append(" · ")
                        append(fmtMini(entry.positionMs))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = pal.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            Modifier
                .size(48.dp)
                .pressDip(closeInteraction, to = press.control)
                .clip(RoundedCornerShape(Radius.pill))
                .clickable(
                    interactionSource = closeInteraction,
                    indication = null,
                    onClick = onClose
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "关闭迷你播放器",
                tint = pal.inkMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun fmtMini(ms: Long): String {
    if (ms <= 0) return "00:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
    else String.format("%02d:%02d", m, sec)
}
