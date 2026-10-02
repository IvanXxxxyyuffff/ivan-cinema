package com.ivan.cinema.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ivan.cinema.IVANApp
import com.ivan.cinema.prefetch
import com.ivan.cinema.data.Aggregator
import com.ivan.cinema.data.ClassCache
import com.ivan.cinema.data.HomeTab
import com.ivan.cinema.data.MergedVod
import com.ivan.cinema.data.SourceHealth
import com.ivan.cinema.data.SourceHit
import com.ivan.cinema.data.VodItem
import com.ivan.cinema.ui.components.StaggerIn
import com.ivan.cinema.ui.components.pressDip
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.Space
import kotlinx.coroutines.flow.collect

private data class FilterDim(val label: String, val options: List<String>)

/**
 * 筛选页（腾讯视频形态）：每行一个维度 —— 左侧灰色标签 + 右侧可横滚选项；
 * 选中即刷新下方结果网格。筛选参数直接透传给 MacCMS（area/class/year/by）。
 */
@Composable
fun FilterScreen(
    columns: Int,
    onOpenDetail: (MergedVod) -> Unit,
    onSearch: () -> Unit,
    // 底栏在推入页隐藏，本页没有系统返回键以外的出口 —— 必须由调用方接上 popPage()
    onBack: () -> Unit = {}
) {
    val pal = LocalIVAN.current
    val ctx = IVANApp.ctx()
    val sources = remember { SourceHealth.sources(ctx) }

    var tab by remember { mutableStateOf(HomeTab.MOVIE) }
    var area by remember { mutableStateOf("") }
    var cls by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf("time") }

    var items by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(1) }
    var endReached by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    // 当前页的流是否仍在收集：驱动「还在加载更多源…」与分页守卫
    var loadingMore by remember { mutableStateOf(false) }

    // 源权重：sources 已按可信度排序（SourceHealth 内已折入 SharedHealth.isBad 降权），
    // 越靠前权重越高 → categoryStream 先查先落位。纯本地换算，不发任何请求。
    val sourceWeight: (String) -> Int = remember(sources) {
        val rank = HashMap<String, Int>(sources.size)
        sources.forEachIndexed { i, s -> rank[s.api] = sources.size - i }
        fun(api: String): Int = rank[api] ?: 0
    }

    val areas = listOf("全部", "内地", "香港", "台湾", "美国", "韩国", "日本", "泰国", "英国", "法国", "印度", "其他")
    val classes = listOf(
        "全部", "动作", "喜剧", "爱情", "科幻", "恐怖", "剧情", "战争", "犯罪",
        "动画", "悬疑", "冒险", "奇幻", "古装", "家庭", "历史", "运动"
    )
    val years = listOf("全部", "2026", "2025", "2024", "2023", "2022", "2021", "2020", "2019", "2018", "2015")
    val sorts = listOf("最新" to "time", "最热" to "hits", "最好评" to "score")

    fun reload() {
        page = 1
        endReached = false
        loading = true
        items = emptyList()
        // 重新选中同一个条件时 LaunchedEffect 的 key 不变、effect 不会重启 ——
        // 必须靠 reloadKey 强制重拉，否则列表被清空后永远停在骨架屏
        reloadKey++
    }

    // 任一条件变化即重拉。全程 runCatching —— 类目表或请求抛异常时原来 loading 永远是 true，
    // 骨架屏变成永久假象，既无失败提示也无重试入口。
    LaunchedEffect(tab, area, cls, year, sortBy, page, reloadKey) {
        if (endReached && page > 1) return@LaunchedEffect
        if (page > 1 && items.isEmpty()) return@LaunchedEffect
        loading = true
        failed = false
        val map = runCatching {
            val cached = ClassCache.read(ctx, tab.name)
            if (cached.isNotEmpty()) cached else {
                val r = Aggregator.resolveClassMap(sources, tab)
                ClassCache.write(ctx, tab.name, r.tidMap)
                r.tidMap
            }
        }.getOrNull()
        if (map.isNullOrEmpty()) {
            failed = true
            loading = false
            return@LaunchedEffect
        }
        // 分页基准：已上屏条目视为已存在，新批次只追加，绝不重排/丢项
        val base = if (page == 1) emptyList() else items
        val baseKeys = base.map { Aggregator.mergeKey(it.name, it.year) }.toHashSet()
        var addedAny = false
        loadingMore = true
        runCatching {
            Aggregator.categoryStream(
                sources, map, page,
                maxSources = 12,
                by = sortBy,
                area = area.ifEmpty { null },
                cls = cls.ifEmpty { null },
                year = year.ifEmpty { null },
                sourceWeight = sourceWeight
            ).collect { batch ->
                // batch 是本页「已到齐的全集」（内部已去重、只增不改）。
                // 必须整体替换本页部分，不能只取「本批新增」——否则会丢掉前几批
                val pagePart = if (page == 1) batch
                else base + batch.filter { Aggregator.mergeKey(it.name, it.year) !in baseKeys }
                if (pagePart.size > base.size) {
                    addedAny = true
                    items = pagePart
                    loading = false
                }
            }
        }.onFailure { failed = true }
        loadingMore = false
        loading = false
        if (!addedAny) endReached = true
    }

    val gridState = rememberLazyGridState()
    val nearEnd by derivedStateOf {
        val info = gridState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
    }
    LaunchedEffect(nearEnd, loadingMore) {
        // 必须等本页流收完再翻页，否则会中途取消仍在收集的流
        if (nearEnd && !loading && !endReached && !loadingMore) page++
    }
    // 预热「即将进入视口」的封面：视口最后一项之后 9 张，上限由 prefetch 兜底
    val lastVisible by remember {
        derivedStateOf { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
    }
    LaunchedEffect(items.size, lastVisible) {
        val start = lastVisible + 1
        if (start >= items.size) return@LaunchedEffect
        prefetch(ctx, items.drop(start).take(9).map { it.pic }.filter { it.isNotEmpty() })
    }

    Box(Modifier.fillMaxSize()) {
        // 不透明背景（必须遮住下层：搜索覆盖层 / 上一页）
        PosterBlurBackdrop(items.firstOrNull { it.pic.isNotEmpty() }?.pic, posterAlpha = 0.26f)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // 顶栏：标题 + 搜索
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回入口：和详情页返回胶囊同款（48dp / 黑 55% / 1px 白描边）
            val backInteraction = remember { MutableInteractionSource() }
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
                "筛选",
                style = MaterialTheme.typography.headlineSmall,
                color = pal.ink,
                modifier = Modifier.weight(1f)
            )
            val interaction = remember { MutableInteractionSource() }
            Icon(
                Icons.Rounded.Search,
                contentDescription = "搜索",
                tint = pal.ink,
                modifier = Modifier
                    .size(44.dp)
                    .pressDip(interaction, to = 0.92f)
                    .clip(RoundedCornerShape(Radius.pill))
                    .clickable(interactionSource = interaction, indication = null) { onSearch() }
                    .padding(10.dp)
            )
        }

        // 维度行
        FilterRow("分类", HomeTab.entries.map { it.title }, tab.title) { picked ->
            tab = HomeTab.entries.firstOrNull { it.title == picked } ?: HomeTab.MOVIE
            reload()
        }
        FilterRow("地区", areas, if (area.isEmpty()) "全部" else area) { picked ->
            area = if (picked == "全部") "" else picked
            reload()
        }
        FilterRow("类型", classes, if (cls.isEmpty()) "全部" else cls) { picked ->
            cls = if (picked == "全部") "" else picked
            reload()
        }
        FilterRow("年份", years, if (year.isEmpty()) "全部" else year) { picked ->
            year = if (picked == "全部") "" else picked
            reload()
        }
        FilterRow("排序", sorts.map { it.first }, sorts.firstOrNull { it.second == sortBy }?.first ?: "最新") { picked ->
            sortBy = sorts.firstOrNull { it.first == picked }?.second ?: "time"
            reload()
        }

        Spacer(Modifier.height(Space.sm))

        // 结果区：weight(1f) 占「剩余空间」——
        // 用 fillMaxSize 会尝试占满整屏并与上方维度行重叠（真机实锤过）
        if (loading && items.isEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(
                    start = Space.lg, end = Space.lg, top = 0.dp, bottom = Space.xxxl
                ),
                horizontalArrangement = Arrangement.spacedBy(Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.block),
                userScrollEnabled = false,
                modifier = Modifier.weight(1f)
            ) {
                items(9) {
                    Box(
                        Modifier
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(Radius.md))
                            .background(pal.surfaceRaised.copy(alpha = 0.55f))
                    )
                }
            }
        } else if (items.isEmpty()) {
            // 失败态整块可点重试（与分类页同一套），不再把异常藏进永久骨架
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable {
                        // reload() 内部已 reloadKey++，这里不再重复自增
                        if (failed) reload()
                    }
            ) {
                EmptyState(
                    if (failed) "加载失败，点一下重试" else "这个条件下没有找到内容，换个条件试试"
                )
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(
                    start = Space.lg, end = Space.lg, top = 0.dp, bottom = Space.xxxl
                ),
                horizontalArrangement = Arrangement.spacedBy(Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.block),
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(items, key = { _, it -> it.source.api + it.vodId }) { index, item ->
                    val merged = MergedVod(
                        key = Aggregator.mergeKey(item.name, item.year),
                        name = item.name,
                        year = item.year,
                        pic = item.pic,
                        hits = mutableListOf(SourceHit(item.source, item.vodId, item.remarks))
                    )
                    StaggerIn(index = index, identity = item.source.api + item.vodId) {
                        PosterCard(item = merged, onClick = { onOpenDetail(merged) })
                    }
                }
                // 流仍在收集时给一条不挡内容的整行提示，让用户知道后面还会长出来
                if (loadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "loadingmore") {
                        Text(
                            "还在加载更多源…",
                            style = MaterialTheme.typography.labelSmall,
                            color = pal.inkMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Space.md)
                        )
                    }
                }
            }
        }
    }
    }
}

/** 一行筛选：左侧灰色圆角标签 + 右侧可横滚选项（腾讯视频形态）。 */
@Composable
private fun FilterRow(
    label: String,
    options: List<String>,
    selected: String,
    onPick: (String) -> Unit
) {
    val pal = LocalIVAN.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Space.xs + 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .padding(start = Space.lg, end = Space.sm)
                .clip(RoundedCornerShape(Radius.pill))
                .background(pal.surfaceRaised)
                .padding(horizontal = Space.md, vertical = 6.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = pal.inkMuted
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            items(options) { opt ->
                val on = opt == selected
                val interaction = remember { MutableInteractionSource() }
                Text(
                    opt,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (on) pal.accent else pal.inkMuted,
                    modifier = Modifier
                        .pressDip(interaction, to = 0.94f)
                        .clip(RoundedCornerShape(Radius.pill))
                        .clickable(interactionSource = interaction, indication = null) { onPick(opt) }
                        // bodyLarge 21dp + 14*2 = 49dp，达最小触摸目标（原来 4dp → 29dp）
                        .padding(horizontal = Space.sm, vertical = 14.dp)
                )
            }
            item { Spacer(Modifier.width(Space.lg)) }
        }
    }
}
