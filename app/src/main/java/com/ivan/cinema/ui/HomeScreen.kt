package com.ivan.cinema.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.ivan.cinema.IVANApp
import com.ivan.cinema.prefetch
import com.ivan.cinema.data.Aggregator
import com.ivan.cinema.data.ClassCache
import com.ivan.cinema.data.HomeTab
import com.ivan.cinema.data.MergedVod
import com.ivan.cinema.data.SourceHealth
import com.ivan.cinema.data.SourceHit
import com.ivan.cinema.data.VodItem
import com.ivan.cinema.db.AppDb
import com.ivan.cinema.db.WatchEntry
import com.ivan.cinema.ui.components.LiquidBar
import com.ivan.cinema.ui.components.ThinProgress
import com.ivan.cinema.ui.components.pressDip
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.Space
import java.util.Locale

/**
 * 首页 —— 腾讯视频式布局：
 *   ① 顶栏：搜索框（内含筛选入口）
 *   ② 分类 Tab（选中加粗放大）
 *   ③ 继续观看（有记录时）
 *   ④ 大 Banner（全宽 16:9 + 底部压渐变 + 片名/一句话 + 指示点）
 *   ⑤ 两列大卡（竖版封面 + 角标 + 片名 + 一句话副标题）
 */
@Composable
fun HomeScreen(
    columns: Int,
    onOpenDetail: (MergedVod) -> Unit,
    onOpenWatch: (WatchEntry) -> Unit,
    onAmbient: (String) -> Unit,
    onMore: (HomeTab) -> Unit,
    onSearch: () -> Unit,
    onFilter: () -> Unit,
    contentBottomPadding: Dp
) {
    val pal = LocalIVAN.current
    val ctx = IVANApp.ctx()
    // 观察探测结果，否则后台核验完成后可信源排序在本会话内不生效
    val verified by SourceHealth.verified
    val sources = remember(verified) { SourceHealth.sources(ctx) }
    // 观看记录按当前用户过滤（本机表原来没有 user 列，换账号会串记录）
    val uid = com.ivan.cinema.data.Account.currentUserId()
    val watch by remember(uid) {
        AppDb.get(IVANApp.app).watchDao().recentFor(uid)
    }.collectAsState(initial = emptyList())

    var tab by remember { mutableStateOf(HomeTab.MOVIE) }
    var items by remember { mutableStateOf<List<VodItem>?>(null) }
    var page by remember { mutableStateOf(1) }
    var endReached by remember { mutableStateOf(false) }
    // 当前页的流是否仍在收集：用于「还在加载更多源…」提示与分页守卫
    var loadingMore by remember { mutableStateOf(false) }

    // 源权重：sources 已由 SourceHealth 按可信度排好序（内部已折入 SharedHealth.isBad 降权），
    // 越靠前权重越高 → categoryStream 先查先落位。这里不发任何网络请求，纯本地换算。
    val sourceWeight: (String) -> Int = remember(sources) {
        val rank = HashMap<String, Int>(sources.size)
        sources.forEachIndexed { i, s -> rank[s.api] = sources.size - i }
        fun(api: String): Int = rank[api] ?: 0
    }

    LaunchedEffect(tab) {
        items = null
        page = 1
        endReached = false
    }

    LaunchedEffect(tab, page) {
        if (endReached && page > 1) return@LaunchedEffect
        // 切 tab 时重置 effect 与加载 effect 同帧竞争：旧 page 先跑会拿到 items=null，
        // 直接跳过，避免把第 N 页当成首页闪一下
        if (page > 1 && items == null) return@LaunchedEffect
        val cached = ClassCache.read(ctx, tab.name)
        val map = if (cached.isNotEmpty()) cached else {
            val r = runCatching { Aggregator.resolveClassMap(sources, tab) }.getOrNull()
            if (r != null) ClassCache.write(ctx, tab.name, r.tidMap)
            r?.tidMap.orEmpty()
        }
        if (map.isEmpty()) {
            // 分类表拿不到：首屏落到空态并停止翻页（原实现会永久停在骨架屏）
            if (page == 1) items = emptyList()
            endReached = true
            return@LaunchedEffect
        }
        // 分页基准：本页之前的条目视为已存在，新批次只做追加，绝不重排/丢项
        val base = if (page == 1) emptyList() else (items ?: emptyList())
        val baseKeys = base.map { Aggregator.mergeKey(it.name, it.year) }.toHashSet()
        var addedAny = false
        var ambientSent = false
        loadingMore = true
        runCatching {
            Aggregator.categoryStream(
                sources, map, page,
                maxSources = 8,
                by = "time",
                sourceWeight = sourceWeight
            ).collect { batch ->
                // batch 是本页「已到齐的全集」（内部已按 mergeKey 去重、只增不改）。
                // 相对分页基准去重后整体作为本页部分，保证整屏从头到尾追加式增长 ——
                // 不能只取「本批新增」，否则第二次发值会把前几批已上屏的条目丢掉
                val pagePart = if (page == 1) batch
                else base + batch.filter { Aggregator.mergeKey(it.name, it.year) !in baseKeys }
                if (pagePart.size > base.size) {
                    addedAny = true
                    items = pagePart
                }
                if (!ambientSent) {
                    batch.firstOrNull { it.pic.isNotEmpty() }?.pic?.let(onAmbient)
                    ambientSent = true
                }
            }
        }
        loadingMore = false
        // 本页没有任何新增（全被去重 / 各源都没数据）→ 到底；首屏此时应落到空态
        if (!addedAny) {
            if (page == 1 && items == null) items = emptyList()
            endReached = true
        }
    }

    val list = items
    val banner = list?.firstOrNull { it.pic.isNotEmpty() }
    // 原来是 drop(1).take(24)：既把网格硬砍到 24 条（后面分页全白加载），
    // 又在首项没有封面时把第 2 条同时当作 banner 和卡片。改成按 key 排除 banner。
    val gridItems = list?.filter { it !== banner } ?: emptyList()

    val listState = rememberLazyListState()
    // 预热「即将进入视口」的封面：找出视口内最后一条网格行，预热其后 9 条。
    // 网格行的 key 是 "grid:<rowIndex>"，所以能直接解析出行号；行号在追加式列表里恒定。
    val lastGridRow by remember {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo
                .mapNotNull { (it.key as? String)?.takeIf { k -> k.startsWith("grid:") } }
                .mapNotNull { it.removePrefix("grid:").toIntOrNull() }
                .maxOrNull() ?: -1
        }
    }
    LaunchedEffect(list?.size, lastGridRow) {
        val l = list ?: return@LaunchedEffect
        val start = (lastGridRow + 1) * columns
        if (start >= gridItems.size) return@LaunchedEffect
        prefetch(ctx, gridItems.drop(start).take(9).map { it.pic }.filter { it.isNotEmpty() })
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = contentBottomPadding),
        modifier = Modifier.fillMaxSize()
    ) {
        // ① 顶栏：搜索框（内含筛选）
        item(key = "top") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = Space.lg, vertical = Space.sm + 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LiquidBar(
                    modifier = Modifier
                        .weight(1f)
                        .pressDip(remember { MutableInteractionSource() }, to = 0.98f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSearch() },
                    radius = Radius.pill,
                    elevated = true
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(horizontal = Space.md + 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            tint = pal.inkMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(Space.sm))
                        Text(
                            "搜索片名",
                            style = MaterialTheme.typography.bodyLarge,
                            color = pal.inkMutedOnGlass,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(18.dp)
                                .background(pal.hairline)
                        )
                        Spacer(Modifier.width(Space.md))
                        Text(
                            "筛选",
                            style = MaterialTheme.typography.bodyLarge,
                            color = pal.inkMutedOnGlass,
                            modifier = Modifier
                                // 补足点击目标：撑满 48dp 胶囊高度；wrapContentHeight
                                // 让文字在撑满后仍垂直居中（默认 CenterVertically）
                                .clickable { onFilter() }
                                .fillMaxHeight()
                                .wrapContentHeight()
                                .padding(horizontal = 9.dp)
                        )
                    }
                }
            }
        }

        // ② 分类 Tab
        item(key = "tabs") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Space.lg),
                horizontalArrangement = Arrangement.spacedBy(Space.xl),
                modifier = Modifier.padding(vertical = Space.sm)
            ) {
                items(HomeTab.entries, key = { it.name }) { t ->
                    val on = t == tab
                    Text(
                        t.title,
                        style = if (on) MaterialTheme.typography.titleLarge
                        else MaterialTheme.typography.titleMedium,
                        color = if (on) pal.ink else pal.inkMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.sm))
                            .clickable { tab = t }
                            // 14dp 让分类 tab 的点击目标到 48dp
                            .padding(vertical = 14.dp)
                    )
                }
            }
        }

        // ③ 继续观看（放在 Banner 之后，避免打断「Tab → 大图」的节奏）
        // ④ 大 Banner
        if (banner != null) {
            item(key = "banner") {
                val merged = banner.toMerged()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.lg, vertical = Space.sm)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(Radius.lg))
                        .clickable { onOpenDetail(merged) }
                ) {
                    SubcomposeAsyncImage(
                        model = banner.pic,
                        contentDescription = banner.name,
                        contentScale = ContentScale.Crop,
                        // 2:3 竖版海报裁成 16:9 时，从顶部取才拿得到标题美术字
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize(),
                        // 加载中给带转圈的占位，失败退到 FilmTile —— 二者与「空白」都不同
                        loading = { LoadingPoster(Modifier.fillMaxSize(), spinner = true) },
                        error = { FilmTile(Modifier.fillMaxSize()) }
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.55f to Color.Transparent,
                                    1f to Color(0xCC000000)
                                )
                            )
                    )
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            // 右侧预留 56dp 车道，长文案不越过安全边距
                            .padding(start = 14.dp, end = 56.dp, bottom = 14.dp)
                    ) {
                        Text(
                            banner.name,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (banner.blurb.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                banner.blurb,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.86f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // ③ 继续观看（Banner 之后，内容流之前）
        if (watch.isNotEmpty()) {
            item(key = "watch") {
                WatchRow(entries = watch, onOpen = onOpenWatch)
            }
        }

        // ⑤ 内容区标题 + 两列大卡
        if (list != null && list.isNotEmpty()) {
            item(key = "section") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.lg, vertical = Space.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "最近更新",
                        style = MaterialTheme.typography.titleLarge,
                        color = pal.ink,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "更多 ›",
                        style = MaterialTheme.typography.labelLarge,
                        color = pal.accent,
                        modifier = Modifier
                            .pressDip(remember { MutableInteractionSource() }, to = 0.94f)
                            .clip(RoundedCornerShape(Radius.pill))
                            .clickable { onMore(tab) }
                            .padding(horizontal = 9.dp, vertical = 15.dp)
                    )
                }
            }
        }

        if (list == null) {
            item(key = "skeleton") {
                // 与真实网格同列数、同边距、同卡片几何（海报 + 14dp 标题条 + 10dp 元信息条），
                // 数据落位时不发生列数/高度重排
                Column {
                    repeat(3) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Space.lg, vertical = Space.md),
                            horizontalArrangement = Arrangement.spacedBy(Space.md)
                        ) {
                            repeat(columns) {
                                Column(Modifier.weight(1f)) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(2f / 3f)
                                            .clip(RoundedCornerShape(Radius.md))
                                            .background(pal.surfaceRaised.copy(alpha = 0.55f))
                                    )
                                    Spacer(Modifier.height(Space.sm))
                                    Box(
                                        Modifier
                                            .fillMaxWidth(0.7f)
                                            .height(14.dp)
                                            .clip(RoundedCornerShape(Radius.sm))
                                            .background(pal.surfaceRaised.copy(alpha = 0.45f))
                                    )
                                    Spacer(Modifier.height(Space.xs))
                                    Box(
                                        Modifier
                                            .fillMaxWidth(0.45f)
                                            .height(10.dp)
                                            .clip(RoundedCornerShape(Radius.sm))
                                            .background(pal.surfaceRaised.copy(alpha = 0.35f))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else if (list.isEmpty()) {
            item(key = "empty") { EmptyState("该分类暂时没有内容") }
        } else {
            itemsIndexed(
                // 用传入的 columns（手机 3 / 平板 6）。原来是硬编码 chunked(2)，
                // 于是首页只有两列巨卡，跟分类/搜索/筛选页的密度完全不一致
                gridItems.chunked(columns),
                // key 用行号：追加式列表里行号恒定，且便于预热逻辑解析「最后可见行」
                key = { rowIndex, _ -> "grid:$rowIndex" }
            ) { rowIndex, rowItems ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.lg, vertical = Space.md),
                    horizontalArrangement = Arrangement.spacedBy(Space.md)
                ) {
                    rowItems.forEach { item ->
                        BigCard(
                            item = item,
                            modifier = Modifier.weight(1f),
                            onClick = { onOpenDetail(item.toMerged()) }
                        )
                    }
                    // 末行不满时补占位，否则最后一张卡会被拉宽
                    repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            // 流仍在收集时给一条不挡内容的提示，让用户知道后面还会长出来
            if (loadingMore && !endReached) {
                item(key = "loadingmore") {
                    Text(
                        "还在加载更多源…",
                        style = MaterialTheme.typography.labelSmall,
                        color = pal.inkMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Space.md)
                    )
                }
            }
            // 分页触发：必须等本页流收完（!loadingMore）再翻下一页，避免流被中途取消
            if (!endReached && !loadingMore) {
                item(key = "loadmore") {
                    LaunchedEffect(Unit) { page++ }
                    Box(Modifier.fillMaxWidth().height(1.dp))
                }
            }
        }
    }
}

/** 这些备注每张卡都有，显示出来等于没信息，直接不显示。 */
private val GENERIC_REMARKS = setOf("正片", "高清", "HD", "BD", "TC", "TS", "全集")

private fun VodItem.toMerged(): MergedVod = MergedVod(
    key = Aggregator.mergeKey(name, year),
    name = name,
    year = year,
    pic = pic,
    hits = mutableListOf(SourceHit(source, vodId, remarks))
)

/**
 * 封面「正在加载」占位：与 FilmTile（失败占位）刻意做出区分 ——
 * 加载中是渐变的空面（可选转圈），失败是带影片图形的实心砖。
 * 否则用户看到一块灰，分不清是在加载还是图挂了。
 */
@Composable
private fun LoadingPoster(modifier: Modifier = Modifier, spinner: Boolean = false) {
    val pal = LocalIVAN.current
    Box(
        modifier.background(
            Brush.verticalGradient(
                listOf(pal.surfaceRaised, pal.surfaceRaised.copy(alpha = 0.68f))
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        if (spinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = pal.inkMuted,
                strokeWidth = 2.dp
            )
        }
    }
}

/** 腾讯视频式大卡：竖版封面 + 左上角标 + 封面底部更新信息 + 片名 + 一句话。 */
@Composable
private fun BigCard(item: VodItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val pal = LocalIVAN.current
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .pressDip(interaction, to = 0.97f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(Radius.md))
                // 封面加载期间不能只是一块死灰，否则满屏空盒子像坏了；
                // 用浅渐变让它读起来是「正在加载」
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
                    // loading 槽留空 → 露出下层渐变占位；失败退到可见的 FilmTile
                    error = { FilmTile(Modifier.fillMaxSize()) }
                )
            } else {
                FilmTile(Modifier.fillMaxSize())
            }
            val remark = item.remarks
            // 泛化备注（几乎每张卡都写「正片」「高清」）等于没有信息，是纯噪音；
            // 只显示真正有信息量的（更新至第N集 / 完结 / 抢先版 …）
            val showRemark = remark.isNotBlank() &&
                GENERIC_REMARKS.none { it.equals(remark.trim(), ignoreCase = true) }
            if (showRemark) {
                Text(
                    remark,
                    style = MaterialTheme.typography.labelSmall,
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
        Spacer(Modifier.height(Space.sm))
        Text(
            item.name,
            style = MaterialTheme.typography.titleMedium,
            color = pal.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (item.blurb.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                item.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = pal.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 继续观看横滚。 */
@Composable
fun WatchRow(entries: List<WatchEntry>, onOpen: (WatchEntry) -> Unit) {
    val pal = LocalIVAN.current
    Column(Modifier.fillMaxWidth().padding(top = Space.sm)) {
        Text(
            "继续观看",
            style = MaterialTheme.typography.titleMedium,
            color = pal.ink,
            modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Space.lg),
            horizontalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            items(entries, key = { it.vodKey }) { e ->
                val interaction = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .width(120.dp)
                        .pressDip(interaction, to = 0.96f)
                        .clip(RoundedCornerShape(Radius.md))
                        .clickable(interactionSource = interaction, indication = null) { onOpen(e) }
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(Radius.md))
                            .background(pal.surfaceRaised)
                    ) {
                        SubcomposeAsyncImage(
                            model = e.pic,
                            contentDescription = e.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            // 加载中走渐变占位，失败退到 FilmTile，不再是「和空白一样」的灰块
                            loading = { LoadingPoster(Modifier.fillMaxSize()) },
                            error = { FilmTile(Modifier.fillMaxSize()) }
                        )
                        ThinProgress(
                            fraction = { if (e.durationMs > 0) e.positionMs.toFloat() / e.durationMs else 0f },
                            modifier = Modifier.align(Alignment.BottomStart)
                        )
                    }
                    Text(
                        e.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = pal.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = Space.xs, end = Space.xs, top = Space.xs + 2.dp)
                    )
                    Text(
                        "${e.episodeName} · ${fmtPos(e.positionMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = pal.inkMuted.copy(alpha = 0.85f),
                        maxLines = 1,
                        modifier = Modifier.padding(start = Space.xs, end = Space.xs, bottom = Space.xs)
                    )
                }
            }
        }
    }
}

private fun fmtPos(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}
