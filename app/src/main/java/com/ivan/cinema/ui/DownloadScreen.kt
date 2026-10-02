package com.ivan.cinema.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.ivan.cinema.ui.components.press
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
    contentBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    // 底栏在推入页隐藏，本页此前只能靠系统返回键退出 —— 必须由调用方接上 popPage()
    onBack: () -> Unit = {}
) {
    val pal = LocalIVAN.current
    var items by remember { mutableStateOf<List<Download>>(emptyList()) }
    // 首次读到下载索引之前 items 必然是空的，直接渲染空态会闪一下
    // 「还没有下载」，即使其实有任务在跑
    var loaded by remember { mutableStateOf(false) }
    // 已完成的下载删掉就没了，必须二次确认；失败/排队项没内容可丢，直接删
    var pendingDelete by remember { mutableStateOf<Download?>(null) }

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
                loaded = true
            }
            delay(1200)
        }
    }

    val svc = IVANDownloadService::class.java
    val removeNow: (Download) -> Unit = { d ->
        DownloadService.sendRemoveDownload(IVANApp.app, svc, d.request.id, false)
    }

    Column(
        Modifier
            .fillMaxSize()
            // 推入页必须自带不透明底，否则下层内容会透上来
            .opaqueScreenBackground()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回入口：和详情页/分类页返回胶囊同款（48dp / 黑 55% / 1px 白描边）
            val backInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(
                Modifier
                    .padding(end = Space.sm)
                    .size(48.dp)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(Radius.pill))
                    .clickable(interactionSource = backInteraction, indication = null) { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
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
                // 48dp 命中区：原来只有 34dp，手指按不准
                Box(
                    Modifier
                        .pressDip(interaction, to = press.control)
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
                        .heightIn(min = 48.dp)
                        .padding(horizontal = Space.lg),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (anyActive) "全部暂停" else "全部继续",
                        style = MaterialTheme.typography.labelLarge,
                        color = pal.ink
                    )
                }
            }
        }
        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "正在读取下载…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = pal.inkMuted
                )
            }
        } else if (items.isEmpty()) {
            EmptyState("还没有下载", "离线观看", "在详情页长按集数即可加入下载")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = Space.lg, end = Space.lg, top = Space.sm, bottom = contentBottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(Space.md),
                modifier = Modifier.fillMaxSize()
            ) {
                // 按剧名分组：下载标题是「剧名 · 集名」，40 集一次性铺开就是 40 行垃圾
                val groups = items.groupBy { showNameOf(downloadTitle(it)) }
                groups.forEach { (show, episodes) ->
                    item(key = "show:$show") {
                        ShowHeader(name = show, count = episodes.size)
                    }
                    items(episodes, key = { it.request.id }) { d ->
                        DownloadRow(
                            d = d,
                            onAction = {
                                when (d.state) {
                                    Download.STATE_COMPLETED -> {
                                        val title = downloadTitle(d)
                                        onPlayLocal(d.request.uri.toString(), title)
                                    }
                                    Download.STATE_DOWNLOADING ->
                                        // media3 1.3.1 没有单条的 sendPauseDownload，
                                        // 单条暂停要走 stopReason
                                        DownloadService.sendSetStopReason(
                                            IVANApp.app, svc, d.request.id,
                                            STOP_REASON_PAUSED_BY_APP, false
                                        )
                                    Download.STATE_QUEUED -> removeNow(d)
                                    else -> {
                                        // 失败重试 / 暂停后继续：清掉 stopReason 再催一次
                                        DownloadService.sendSetStopReason(
                                            IVANApp.app, svc, d.request.id,
                                            Download.STOP_REASON_NONE, false
                                        )
                                        DownloadService.sendResumeDownloads(IVANApp.app, svc, false)
                                    }
                                }
                            },
                            onDelete = {
                                if (d.state == Download.STATE_COMPLETED) pendingDelete = d
                                else removeNow(d)
                            }
                        )
                    }
                }
            }
        }
    }

    // 删除已完成下载前确认：文件删掉要重新下，不能一次误触就没了
    pendingDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                Text("删除下载", color = pal.ink)
            },
            text = {
                Text(
                    "将删除「${downloadTitle(d)}」的本地文件，删除后需要重新下载。",
                    color = pal.inkMutedOnGlass
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeNow(d)
                        pendingDelete = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("删除", color = pal.dangerOnGlass)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = null },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("取消", color = pal.inkMutedOnGlass)
                }
            },
            containerColor = pal.surfaceRaised,
            titleContentColor = pal.ink,
            textContentColor = pal.inkMutedOnGlass
        )
    }
}

/** 剧名分组抬头：剧名 + 集数，把「40 行一坨」收成一眼可读的分段。 */
@Composable
private fun ShowHeader(name: String, count: Int) {
    val pal = LocalIVAN.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            color = pal.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            "$count 集",
            style = MaterialTheme.typography.labelSmall,
            color = pal.inkMutedOnGlass
        )
    }
}

@UnstableApi
@Composable
private fun DownloadRow(d: Download, onAction: () -> Unit, onDelete: () -> Unit) {
    val pal = LocalIVAN.current
    val title = remember(d.request.id) { downloadTitle(d) }
    val done = d.state == Download.STATE_COMPLETED
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    LiquidCard(
        modifier = Modifier
            .fillMaxWidth()
            .pressDip(interaction)
            // 每行按状态都可点：完成播放 / 下载中暂停 / 暂停继续 / 失败重试 / 排队取消
            .clickable(
                interactionSource = interaction,
                indication = null
            ) { onAction() },
        radius = Radius.lg
    ) {
        Column(Modifier.padding(Space.md + 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 剧名已经在分组抬头里，这里只显示集名，行内不再重复剧名
                Text(
                    episodeNameOf(title),
                    style = MaterialTheme.typography.titleMedium,
                    color = pal.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 48dp 命中区：原来 22dp 且嵌在可点卡片里，误触就是删下载
                Box(
                    Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(pal.surfaceRaised.copy(alpha = 0.7f))
                        .clickable { onDelete() }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = Space.lg),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "删除",
                        style = MaterialTheme.typography.labelSmall,
                        color = pal.inkMutedOnGlass
                    )
                }
            }
            Spacer(Modifier.height(Space.sm))
            val status = when (d.state) {
                Download.STATE_COMPLETED -> "已下载 · 点击离线播放"
                Download.STATE_DOWNLOADING -> "下载中 ${d.percentDownloaded.toInt()}% · 点击暂停"
                Download.STATE_QUEUED -> "排队中 · 点击取消"
                Download.STATE_FAILED -> "下载失败 · 点击重试"
                else -> "已暂停 · 点击继续"
            }
            Text(status, style = MaterialTheme.typography.labelSmall, color = pal.inkMutedOnGlass)
            if (!done && d.state == Download.STATE_DOWNLOADING) {
                Spacer(Modifier.height(Space.sm))
                ThinProgress(fraction = { d.percentDownloaded / 100f })
            }
        }
    }
}

/** 下载标题：下载时写入的是「剧名 · 集名」。 */
@UnstableApi
private fun downloadTitle(d: Download): String =
    String(d.request.data ?: ByteArray(0)).ifEmpty { "未命名" }

private const val TITLE_SEP = " · "

/**
 * 单条下载暂停用的 stopReason。
 * media3 只暴露 `Download.STOP_REASON_NONE`（0），其余是应用自定义值，
 * 官方 demo 用的就是 1 作为「被 App 暂停」。
 */
private const val STOP_REASON_PAUSED_BY_APP = 1

/** 从「剧名 · 集名」里取剧名（没有分隔符就当整串是剧名）。 */
private fun showNameOf(title: String): String {
    val i = title.indexOf(TITLE_SEP)
    return if (i > 0) title.substring(0, i) else title
}

/** 从「剧名 · 集名」里取集名（没有分隔符就原样返回）。 */
private fun episodeNameOf(title: String): String {
    val i = title.indexOf(TITLE_SEP)
    return if (i > 0) title.substring(i + TITLE_SEP.length) else title
}
