package com.ivan.cinema.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.ivan.cinema.IVANApp
import com.ivan.cinema.data.Aggregator
import com.ivan.cinema.data.MergedVod
import com.ivan.cinema.data.VodDetail
import com.ivan.cinema.db.WatchEntry
import com.ivan.cinema.ui.components.DefaultLiquid
import com.ivan.cinema.ui.components.LiquidCard
import com.ivan.cinema.ui.components.liquidGlass
import com.ivan.cinema.ui.components.pressDip
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.Space
import com.ivan.cinema.ui.theme.accentBrush
import kotlinx.coroutines.delay

/**
 * 详情页 —— 消费者最需要的三件事按优先级排：
 *   ① 一眼知道这是什么片（海报大图 + 片名 + 年份类型）
 *   ② **一键就能播**（主播放按钮是整页最大的点击目标，有观看记录时变「继续观看 第N集」）
 *   ③ 切线路 / 选集 / 下载
 * 简介退到最后（可折叠）—— 它是决策后的信息，不是决策信息。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    merged: MergedVod,
    watchEntry: WatchEntry?,
    onPlay: (detail: VodDetail, lineIndex: Int, episodeIndex: Int, hits: List<com.ivan.cinema.data.SourceHit>) -> Unit,
    onDownload: (VodDetail, lineIndex: Int, episodeIndex: Int) -> Unit,
    onBack: () -> Unit = {}
) {
    val pal = LocalIVAN.current
    var details by remember(merged.key) { mutableStateOf<List<VodDetail>>(emptyList()) }
    var loading by remember(merged.key) { mutableStateOf(true) }
    var failed by remember(merged.key) { mutableStateOf(false) }
    var selectedLine by remember(merged.key) { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var reloadKey by remember(merged.key) { mutableStateOf(0) }
    // 12s 仍未出结果 → 允许用户不再等，直接试第一个源
    var slow by remember(merged.key) { mutableStateOf(false) }
    var firstOnly by remember(merged.key) { mutableStateOf(false) }

    // 补全后的多源命中。必须原样交给播放器 —— 之前播放器拿的是 merged.hits（单源），
    // 所以详情页显示多条线路、播放页却永远没有「换源」按钮。
    var resolvedHits by remember(merged.key) {
        mutableStateOf<List<com.ivan.cinema.data.SourceHit>>(merged.hits)
    }

    LaunchedEffect(merged.key, reloadKey, firstOnly) {
        loading = true
        failed = false
        slow = false
        // 单源命中时按片名搜全网补全（否则播放器只有一条线路，脏源无法绕过）。
        // firstOnly（用户点了「先试试第一个源」）时跳过补全，直接用已有命中里的第一条。
        val fullHits = runCatching {
            if (firstOnly) resolvedHits.take(1)
            else if (merged.hits.size < 3) {
                com.ivan.cinema.data.Aggregator.expandHits(
                    com.ivan.cinema.data.SourceHealth.sources(IVANApp.ctx()),
                    merged.name,
                    merged.year,
                    merged.hits
                )
            } else merged.hits
        }.getOrDefault(if (firstOnly) resolvedHits.take(1) else merged.hits)
        resolvedHits = fullHits
        if (firstOnly) selectedLine = 0

        val fetched = runCatching { Aggregator.fetchDetailAll(fullHits) }
        if (fetched.isFailure) {
            failed = true
            details = emptyList()
        } else {
            details = fetched.getOrDefault(emptyList())
                .filter { it.lines.isNotEmpty() }
                .sortedByDescending { it.lines.maxOf { l -> l.episodes.size } }
        }
        loading = false
    }

    // 慢源逃生：12 秒还没回来就换文案并给出「先试试第一个源」
    LaunchedEffect(merged.key, reloadKey, firstOnly, loading) {
        if (loading) {
            delay(12_000)
            if (loading) slow = true
        }
    }

    // ── 观看记录自读 ──
    // 入参 watchEntry 只由调用方加载一次，播完一集回来仍是旧值（按钮停在旧集、旧集高亮）。
    // 这里把它当初始值，自己按 key 重读 DB，并在 ON_RESUME 时刷新。
    val ctx = IVANApp.ctx()
    var liveEntry by remember(merged.key) { mutableStateOf(watchEntry) }
    var resumeTick by remember(merged.key) { mutableStateOf(0) }
    LaunchedEffect(merged.key, resumeTick) {
        liveEntry = watchEntry
        runCatching { com.ivan.cinema.db.AppDb.get(ctx).watchDao().get(merged.key) }
            .getOrNull()
            ?.let { liveEntry = it }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, merged.key) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val current = details.getOrNull(selectedLine) ?: details.firstOrNull()
    val episodes = current?.lines?.firstOrNull()?.episodes ?: emptyList()
    val resumeIndex = liveEntry?.episodeIndex?.takeIf { it in episodes.indices }

    Box(Modifier.fillMaxSize()) {
        // 高模糊海报背景（不透明，遮住下层）
        PosterBlurBackdrop(merged.pic, blurDp = 160.dp, posterAlpha = 0.30f)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // ── ① 海报大图 + 片名（280dp，底部渐变接住信息）──
            // clipToBounds：底部元信息列（标题+元信息+评分+导演+主演）总高约 195dp，
            // 超出 280dp 英雄区时原来会直接画到下面的播放按钮上，必须裁掉。
            Box(Modifier.fillMaxWidth().height(280.dp).clipToBounds()) {
                if (merged.pic.isNotEmpty()) {
                    AsyncImage(
                        model = merged.pic,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // 顶部压暗：海报可能是亮色，返回按钮和状态栏需要一层底
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(110.dp)
                        .background(
                            Brush.verticalGradient(
                                0f to Color(0x730C0B10),
                                0.35f to Color.Transparent
                            )
                        )
                )
                // 详情页原来没有任何返回入口，只能靠系统返回键 —— 这里补一个
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(start = Space.md, top = Space.sm)
                        .size(48.dp)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(Radius.pill))
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color(0x330C0B10),
                                0.60f to Color(0xB30C0B10),
                                1f to pal.canvas
                            )
                        )
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = Space.lg, vertical = Space.md)
                ) {
                    Text(
                        merged.name,
                        style = MaterialTheme.typography.displaySmall,
                        color = pal.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(Space.xs + 2.dp))
                    Text(
                        listOfNotNull(
                            merged.year.ifEmpty { null },
                            current?.typeName?.ifEmpty { null },
                            current?.area?.ifEmpty { null },
                            current?.remarks?.ifEmpty { null }
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = pal.inkMutedOnGlass,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val scoreText = current?.score?.takeIf { it.isNotEmpty() && it != "0" && it != "0.0" }
                    if (scoreText != null) {
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            "★ $scoreText",
                            style = MaterialTheme.typography.titleMedium,
                            color = pal.accent
                        )
                    }
                    val directorText = current?.director?.takeIf { it.isNotEmpty() }
                    if (directorText != null) {
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            "导演 $directorText",
                            style = MaterialTheme.typography.bodySmall,
                            color = pal.inkMutedOnGlass,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val actorText = current?.actor?.takeIf { it.isNotEmpty() }
                    if (actorText != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "主演 $actorText",
                            style = MaterialTheme.typography.bodySmall,
                            color = pal.inkMutedOnGlass,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // ── ② 主行动：播放 / 继续观看（整页最大的点击目标）──
            // 聚合未完成时按钮不可点（之前是「可点但无响应」的死按钮）
            val ready = current != null
            val playInteraction = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg)
                    .then(if (ready) Modifier.pressDip(playInteraction, to = 0.98f) else Modifier)
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(
                        if (ready) accentBrush(pal)
                        else com.ivan.cinema.ui.theme.SolidColorBrushCompat(Color.White.copy(alpha = 0.10f))
                    )
                    .clickable(
                        enabled = ready,
                        interactionSource = playInteraction,
                        indication = null
                    ) {
                        val idx = resumeIndex ?: 0
                        current?.let { onPlay(it, selectedLine, idx, resolvedHits) }
                    }
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 聚合中：按钮原地换成同尺寸的转圈，形状/位置不变 —— 读起来是「在干活」，
                // 而不是一条按不动的灰色死条。
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = pal.inkMuted,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = if (ready) pal.accentInk else pal.inkMuted,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(Space.sm))
                Text(
                    when {
                        !ready -> "正在准备线路…"
                        resumeIndex != null && liveEntry != null -> "继续观看 · 第${resumeIndex + 1}集"
                        episodes.size > 1 -> "播放第 1 集"
                        else -> "立即播放"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (ready) pal.accentInk else pal.inkMuted
                )
            }

            if (loading) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        if (slow) "源响应较慢，仍在尝试…"
                        else "正在聚合 ${resolvedHits.size} 个源的线路…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = pal.inkMutedOnGlass,
                        modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md)
                    )
                    if (slow) {
                        val escInteraction = remember { MutableInteractionSource() }
                        Text(
                            "先试试第一个源",
                            style = MaterialTheme.typography.labelLarge,
                            color = pal.accentInk,
                            modifier = Modifier
                                .padding(horizontal = Space.lg)
                                .pressDip(escInteraction, to = 0.95f)
                                .clip(RoundedCornerShape(Radius.pill))
                                .background(pal.accent)
                                .clickable(interactionSource = escInteraction, indication = null) {
                                    firstOnly = true
                                }
                                .padding(horizontal = Space.lg, vertical = 15.dp)
                        )
                    }

                    // 骨架：结构先立住，页面不再是一片空白（选集 8 格 + 简介卡）
                    Spacer(Modifier.height(Space.block))
                    Text(
                        "选集",
                        style = MaterialTheme.typography.titleMedium,
                        color = pal.ink.copy(alpha = 0.35f),
                        modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm)
                    )
                    repeat(2) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Space.lg, vertical = Space.xs),
                            horizontalArrangement = Arrangement.spacedBy(Space.sm)
                        ) {
                            repeat(4) {
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(Radius.sm + 2.dp))
                                        .background(pal.surfaceRaised.copy(alpha = 0.55f))
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(Space.block))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Space.lg)
                            .height(132.dp)
                            .clip(RoundedCornerShape(Radius.lg))
                            .background(pal.surfaceRaised.copy(alpha = 0.45f))
                    )
                }
            } else if (failed) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.lg, vertical = Space.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "线路加载失败，检查网络后重试",
                        style = MaterialTheme.typography.bodyMedium,
                        color = pal.danger,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "重试",
                        style = MaterialTheme.typography.labelLarge,
                        color = pal.accentInk,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(pal.accent)
                            .clickable {
                                firstOnly = false
                                reloadKey++
                            }
                            .padding(horizontal = Space.lg, vertical = 15.dp)
                    )
                }
            } else if (details.isEmpty()) {
                // 空态原来没有任何出口 —— 补一个和失败分支一致的重试
                Column(Modifier.fillMaxWidth()) {
                    EmptyState("各源暂无可用线路")
                    val emptyRetry = remember { MutableInteractionSource() }
                    Text(
                        "重试",
                        style = MaterialTheme.typography.labelLarge,
                        color = pal.accentInk,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .pressDip(emptyRetry, to = 0.95f)
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(pal.accent)
                            .clickable(interactionSource = emptyRetry, indication = null) {
                                firstOnly = false
                                reloadKey++
                            }
                            .padding(horizontal = Space.lg, vertical = 15.dp)
                    )
                }
            } else {
                // ── ③ 线路切换 ──
                if (details.size > 1) {
                    Spacer(Modifier.height(Space.block))
                    Text(
                        "线路",
                        style = MaterialTheme.typography.titleMedium,
                        color = pal.ink,
                        modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm)
                    )
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.lg),
                        horizontalArrangement = Arrangement.spacedBy(Space.sm)
                    ) {
                        itemsIndexed(details) { i, d ->
                            val isSelected = i == selectedLine
                            val interaction = remember { MutableInteractionSource() }
                            Text(
                                "${d.source.name} · ${d.lines.firstOrNull()?.episodes?.size ?: 0}集",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isSelected) pal.accentInk else pal.ink,
                                modifier = Modifier
                                    .pressDip(interaction, to = 0.95f)
                                    .clip(RoundedCornerShape(Radius.pill))
                                    .background(
                                        if (isSelected) accentBrush(pal)
                                        else com.ivan.cinema.ui.theme.SolidColorBrushCompat(Color.White.copy(alpha = 0.10f))
                                    )
                                    .clickable(interactionSource = interaction, indication = null) {
                                        selectedLine = i
                                    }
                                    .semantics { selected = isSelected }
                                    // labelLarge 18dp + 15*2 = 48dp，达最小触摸目标
                                    .padding(horizontal = Space.md, vertical = 15.dp)
                            )
                        }
                    }
                }

                // ── ④ 选集（有观看记录时高亮当前集）──
                Spacer(Modifier.height(Space.block))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.lg),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (episodes.size > 1) "选集" else "播放",
                        style = MaterialTheme.typography.titleMedium,
                        color = pal.ink,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "长按下载",
                        style = MaterialTheme.typography.labelSmall,
                        color = pal.inkMuted.copy(alpha = 0.7f)
                    )
                }
                Spacer(Modifier.height(Space.sm))
                // 单集（电影）时撑满整行 —— 之前只占 1/4 宽，右侧整行空白
                val grid = if (episodes.size == 1) listOf(episodes) else episodes.chunked(4)
                grid.forEachIndexed { rowIdx, rowEps ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Space.lg, vertical = Space.xs),
                        horizontalArrangement = Arrangement.spacedBy(Space.sm)
                    ) {
                        rowEps.forEachIndexed { col, ep ->
                            val globalIdx = if (episodes.size == 1) 0 else rowIdx * 4 + col
                            // liveEntry 是委托属性，不能直接智能转换，先取到局部变量
                            val we = liveEntry
                            val isCurrent = we != null &&
                                we.episodeIndex == globalIdx &&
                                we.sourceApi == current?.source?.api
                            val interaction = remember { MutableInteractionSource() }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .pressDip(interaction, to = 0.94f)
                                    .clip(RoundedCornerShape(Radius.sm + 2.dp))
                                    .background(
                                        if (isCurrent) accentBrush(pal)
                                        else com.ivan.cinema.ui.theme.SolidColorBrushCompat(Color.White.copy(alpha = 0.10f))
                                    )
                                    .combinedClickable(
                                        interactionSource = interaction,
                                        indication = null,
                                        onClick = { current?.let { onPlay(it, selectedLine, globalIdx, resolvedHits) } },
                                        onLongClick = { current?.let { onDownload(it, selectedLine, globalIdx) } }
                                    )
                                    .semantics { selected = isCurrent }
                                    // labelLarge 18dp + 15*2 = 48dp，达最小触摸目标
                                    .padding(vertical = 15.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    ep.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isCurrent) pal.accentInk else pal.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        repeat(4 - rowEps.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            // ── ⑤ 简介（折叠，决策后的信息）──
            // 优先选含中文的简介（源数据常混英文简介，中文用户读不了）
            val isCjk: (String) -> Boolean = { s -> s.any { it.code in 0x4E00..0x9FFF } }
            val synopsis = details.firstOrNull { it.content.isNotEmpty() && isCjk(it.content) }?.content
                ?: details.firstOrNull { it.content.isNotEmpty() }?.content
                ?: ""
            if (synopsis.isNotEmpty()) {
                Spacer(Modifier.height(Space.block))
                LiquidCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.lg),
                    radius = Radius.lg
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .animateContentSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { expanded = !expanded }
                            .padding(Space.md + 2.dp)
                    ) {
                        Text(
                            "简介",
                            style = MaterialTheme.typography.titleMedium,
                            color = pal.ink
                        )
                        Spacer(Modifier.height(Space.sm))
                        Text(
                            synopsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = pal.inkMuted,
                            maxLines = if (expanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(Space.sm))
                        Text(
                            if (expanded) "收起" else "展开全文 ›",
                            style = MaterialTheme.typography.labelLarge,
                            color = pal.accent
                        )
                    }
                }
            }

            Spacer(Modifier.height(Space.xxxl))
        }
    }
}
