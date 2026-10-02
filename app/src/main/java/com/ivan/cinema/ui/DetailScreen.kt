package com.ivan.cinema.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.SubcomposeAsyncImage
import com.ivan.cinema.IVANApp
import com.ivan.cinema.data.Aggregator
import com.ivan.cinema.data.FavStore
import com.ivan.cinema.data.FollowStore
import com.ivan.cinema.data.MergedVod
import com.ivan.cinema.data.VodDetail
import com.ivan.cinema.db.FavEntry
import com.ivan.cinema.db.FollowEntry
import com.ivan.cinema.db.WatchEntry
import com.ivan.cinema.ui.components.DefaultLiquid
import com.ivan.cinema.ui.components.LiquidCard
import com.ivan.cinema.ui.components.SelectPill
import com.ivan.cinema.ui.components.liquidGlass
import com.ivan.cinema.ui.components.press
import com.ivan.cinema.ui.components.pressDip
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.Space
import com.ivan.cinema.ui.theme.accentBrush
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    // 播放地址预热：用户还在这一页读简介、挑集数时，就把第一条线路第一集解析好。
    //
    // 采集源给的播放地址大多是个**网页**，PlayResolver 要再发 1~2 次 HTTP 才能挖出真 m3u8，
    // 每次最长 10s —— 那一跳就是"点播放要等好几秒"的主因。放在这里预热，
    // 等真进播放页时通常已经命中缓存，用户感觉就是秒开。
    LaunchedEffect(details) {
        val firstEp = details.firstOrNull()?.lines?.firstOrNull()?.episodes?.firstOrNull()
        if (firstEp != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.ivan.cinema.player.PlayResolver.warm(firstEp.url)
            }
        }
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

    // ── 追剧订阅自读 ──
    // 和观看记录同理由：详情页在推入栈里长期存活，订阅状态必须自己从 DB 读，
    // 不能只靠一次性的入参（否则在别处取消追剧后回来按钮还是「已追剧」）。
    var followed by remember(merged.key) { mutableStateOf(false) }
    var followTick by remember(merged.key) { mutableStateOf(0) }
    LaunchedEffect(merged.key, followTick) {
        followed = runCatching { FollowStore.isFollowed(ctx, merged.key) }.getOrDefault(false)
    }
    val followScope = rememberCoroutineScope()

    // ── 片单收藏自读 ──
    // 与追剧同理由：详情页长期存活在推入栈里，收藏状态必须自己从 DB 读，
    // 否则在「我的片单」移除后回到详情页，按钮仍显示「已收藏」。
    var faved by remember(merged.key) { mutableStateOf(false) }
    var favTick by remember(merged.key) { mutableStateOf(0) }
    // 同时挂在 resumeTick 上：从「我的片单」移除后回到本页（ON_RESUME）要能立刻反映
    LaunchedEffect(merged.key, favTick, resumeTick) {
        faved = runCatching { FavStore.isFav(ctx, merged.key) }.getOrDefault(false)
    }
    val favScope = rememberCoroutineScope()
    // 通知权限只在「本页第一次订阅」时申请一次；被拒绝也照样能追剧，只是收不到提醒
    var askedNotif by remember(merged.key) { mutableStateOf(false) }
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 授权结果不改变追剧本身，只决定能否收到更新通知 */ }

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
                    SubcomposeAsyncImage(
                        model = merged.pic,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        // 加载中给渐变占位，失败退到 FilmTile —— 二者都不同于「一片空白」
                        loading = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(pal.surfaceRaised, pal.surface)
                                        )
                                    )
                            )
                        },
                        error = { FilmTile(Modifier.fillMaxSize()) }
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
                                // 0x0C0B10 就是 pal.canvas，写成令牌，主题漂移时不会脱节
                                0f to pal.canvas.copy(alpha = 0.45f),
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
                                // 三档同为 pal.canvas 的透明度阶梯（0x33≈0.20 / 0xB3≈0.70）
                                0f to pal.canvas.copy(alpha = 0.20f),
                                0.60f to pal.canvas.copy(alpha = 0.70f),
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
                    .then(if (ready) Modifier.pressDip(playInteraction, to = press.card) else Modifier)
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

            // ── ②b 追剧（次级动作：描边胶囊，紧贴播放按钮下方，不与主 CTA 抢视觉）──
            // 详情页此时还没有源信息时不可追（追剧记录必须带 sourceApi/vodId 才能重查）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg)
                    .padding(top = Space.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SelectPill(
                    selected = followed,
                    enabled = ready,
                    horizontalPadding = Space.lg,
                    // 保持实色填充（本页线路/选集用渐变），不改变既有观感
                    selectedFill = com.ivan.cinema.ui.theme.SolidColorBrushCompat(pal.accent),
                    onClick = {
                        val target = !followed
                        followed = target
                        followScope.launch {
                            runCatching {
                                if (target) {
                                    val d = current
                                    if (d != null) {
                                        FollowStore.follow(
                                            ctx,
                                            FollowEntry(
                                                vodKey = merged.key,
                                                name = merged.name,
                                                year = merged.year,
                                                pic = merged.pic,
                                                sourceApi = d.source.api,
                                                sourceName = d.source.name,
                                                vodId = d.vodId,
                                                episodeCount = episodes.size,
                                                followedAt = System.currentTimeMillis(),
                                                lastCheckedAt = System.currentTimeMillis()
                                            )
                                        )
                                    }
                                } else {
                                    FollowStore.unfollow(ctx, merged.key)
                                }
                            }
                            followTick++   // 回读 DB，让按钮反映真实落库结果
                        }
                        // 首次订阅时上下文申请通知权限（Android 13+）
                        if (target && !askedNotif) {
                            askedNotif = true
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    ctx, Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    }
                ) {
                    Text(
                        if (followed) "已追剧" else "追剧",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (followed) pal.accentInk else if (ready) pal.ink else pal.inkMuted
                    )
                }
                Spacer(Modifier.width(Space.md))
                Text(
                    if (followed) "每天检查一次，更新了通知你" else "更新了通知你",
                    style = MaterialTheme.typography.labelSmall,
                    color = pal.inkMutedOnGlass,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 占满中段：收藏胶囊靠右，长文案先省略，不与两个胶囊抢位
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Space.md))
                // ── ②c 片单收藏（与追剧同款描边胶囊，靠右；不改动追剧与主 CTA）──
                SelectPill(
                    selected = faved,
                    horizontalPadding = Space.lg,
                    selectedFill = com.ivan.cinema.ui.theme.SolidColorBrushCompat(pal.accent),
                    onClick = {
                        val target = !faved
                        faved = target
                        favScope.launch {
                            runCatching {
                                if (target) {
                                    // 源信息优先取当前详情线路，退到列表命中（merged.hits 至少一条）
                                    val hit = merged.hits.firstOrNull()
                                    FavStore.add(
                                        ctx,
                                        FavEntry(
                                            vodKey = merged.key,
                                            name = merged.name,
                                            year = merged.year,
                                            pic = merged.pic,
                                            sourceApi = current?.source?.api ?: hit?.source?.api.orEmpty(),
                                            sourceName = current?.source?.name ?: hit?.source?.name.orEmpty(),
                                            vodId = current?.vodId ?: hit?.vodId.orEmpty(),
                                            addedAt = System.currentTimeMillis()
                                        )
                                    )
                                } else {
                                    FavStore.remove(ctx, merged.key)
                                }
                            }
                            favTick++   // 回读 DB，让按钮反映真实落库结果
                        }
                    }
                ) {
                    Text(
                        if (faved) "已收藏" else "收藏",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (faved) pal.accentInk else pal.ink
                    )
                }
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
                                .pressDip(escInteraction, to = press.control)
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
                    val retryInteraction = remember { MutableInteractionSource() }
                    Text(
                        "重试",
                        style = MaterialTheme.typography.labelLarge,
                        color = pal.accentInk,
                        modifier = Modifier
                            .pressDip(retryInteraction, to = press.control)
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(pal.accent)
                            .clickable(interactionSource = retryInteraction, indication = null) {
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
                            .pressDip(emptyRetry, to = press.control)
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
                            // labelLarge 18dp + 15*2 = 48dp，达最小触摸目标
                            SelectPill(
                                selected = isSelected,
                                onClick = { selectedLine = i },
                            ) {
                                Text(
                                    "${d.source.name} · ${d.lines.firstOrNull()?.episodes?.size ?: 0}集",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isSelected) pal.accentInk else pal.ink
                                )
                            }
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
                            SelectPill(
                                selected = isCurrent,
                                onClick = { current?.let { onPlay(it, selectedLine, globalIdx, resolvedHits) } },
                                onLongClick = { current?.let { onDownload(it, selectedLine, globalIdx) } },
                                modifier = Modifier.weight(1f),
                                // 网格里横向不留白，长集名尽量完整（labelLarge 18dp + 15*2 = 48dp）
                                horizontalPadding = 0.dp,
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
