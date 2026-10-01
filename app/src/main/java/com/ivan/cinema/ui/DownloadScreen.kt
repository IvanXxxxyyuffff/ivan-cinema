package com.ivan.cinema.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import com.ivan.cinema.IVANApp
import com.ivan.cinema.player.DownloadCenter
import com.ivan.cinema.player.IVANDownloadService
import com.ivan.cinema.ui.components.EmptyState
import com.ivan.cinema.ui.components.LiquidCard
import com.ivan.cinema.ui.components.ThinProgress
import com.ivan.cinema.ui.components.pressDip
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 下载管理：列表 / 进度 / 删除 / 离线播放。 */
@UnstableApi
@Composable
fun DownloadScreen(
    onPlayLocal: (url: String, title: String) -> Unit,
    contentBottomPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    val pal = LocalIVAN.current
    var items by remember { mutableStateOf<List<Download>>(emptyList()) }

    LaunchedEffect(Unit) {
        DownloadCenter.ensureInit(IVANApp.app)
        while (isActive) {
            val dm = DownloadCenter.managerRef
            if (dm != null) {
                val list = ArrayList<Download>()
                runCatching {
                    dm.downloadIndex.getDownloads().use { cursor ->
                        while (cursor.moveToNext()) {
                            list.add(cursor.download)
                        }
                    }
                }
                items = list
            }
            delay(1200)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "下载",
                style = MaterialTheme.typography.headlineSmall,
                color = pal.ink,
                modifier = Modifier.weight(1f)
            )
            val anyActive = items.any {
                it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED
            }
            if (items.isNotEmpty()) {
                val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Text(
                    if (anyActive) "全部暂停" else "全部继续",
                    style = MaterialTheme.typography.labelLarge,
                    color = pal.ink,
                    modifier = Modifier
                        .pressDip(interaction, to = 0.94f)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(Color.White.copy(alpha = 0.14f))
                        .clickable(interactionSource = interaction, indication = null) {
                            if (anyActive) {
                                DownloadService.sendPauseDownloads(
                                    IVANApp.app, IVANDownloadService::class.java, false
                                )
                            } else {
                                DownloadService.sendResumeDownloads(
                                    IVANApp.app, IVANDownloadService::class.java, false
                                )
                            }
                        }
                        .padding(horizontal = Space.md, vertical = Space.sm)
                )
            }
        }
        if (items.isEmpty()) {
            EmptyState("还没有下载", "离线观看", "在详情页长按集数即可加入下载")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = Space.lg, end = Space.lg, top = Space.sm, bottom = contentBottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(Space.md),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items, key = { it.request.id }) { d ->
                    DownloadRow(
                        d = d,
                        onPlay = {
                            val title = String(d.request.data ?: ByteArray(0))
                            onPlayLocal(d.request.uri.toString(), title)
                        },
                        onDelete = {
                            DownloadService.sendRemoveDownload(
                                IVANApp.app,
                                IVANDownloadService::class.java,
                                d.request.id,
                                false
                            )
                        }
                    )
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun DownloadRow(d: Download, onPlay: () -> Unit, onDelete: () -> Unit) {
    val pal = LocalIVAN.current
    val title = remember(d.request.id) { String(d.request.data ?: ByteArray(0)) }
    val done = d.state == Download.STATE_COMPLETED
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    LiquidCard(
        modifier = Modifier
            .fillMaxWidth()
            .pressDip(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null
            ) { if (done) onPlay() },
        radius = Radius.lg
    ) {
        Column(Modifier.padding(Space.md + 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title.ifEmpty { "未命名" },
                    style = MaterialTheme.typography.titleMedium,
                    color = pal.ink,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "删除",
                    style = MaterialTheme.typography.labelSmall,
                    color = pal.inkMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(pal.surfaceRaised.copy(alpha = 0.7f))
                        .clickable { onDelete() }
                        .padding(horizontal = Space.sm, vertical = Space.xs)
                )
            }
            Spacer(Modifier.height(Space.sm))
            val status = when (d.state) {
                Download.STATE_COMPLETED -> "已下载 · 点击离线播放"
                Download.STATE_DOWNLOADING -> "下载中 ${(d.percentDownloaded).toInt()}%"
                Download.STATE_QUEUED -> "排队中"
                Download.STATE_FAILED -> "下载失败"
                else -> "已暂停"
            }
            Text(status, style = MaterialTheme.typography.labelSmall, color = pal.inkMuted)
            if (!done && d.state == Download.STATE_DOWNLOADING) {
                Spacer(Modifier.height(Space.sm))
                ThinProgress(fraction = { d.percentDownloaded / 100f })
            }
        }
    }
}
