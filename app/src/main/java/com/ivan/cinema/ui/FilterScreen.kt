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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import com.ivan.cinema.IVANApp
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

private data class FilterDim(val label: String, val options: List<String>)

/**
 * 筛选页（腾讯视频形态）：每行一个维度 —— 左侧灰色标签 + 右侧可横滚选项；
 * 选中即刷新下方结果网格。筛选参数直接透传给 MacCMS（area/class/year/by）。
 */
@Composable
fun FilterScreen(
    columns: Int,
    onOpenDetail: (MergedVod) -> Unit,
    onSearch: () -> Unit
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
    var page by remember { mutableStateOf(1) }
    var endReached by remember { mutableStateOf(false) }

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
    }

    // 任一条件变化即重拉
    LaunchedEffect(tab, area, cls, year, sortBy, page) {
        if (endReached && page > 1) return@LaunchedEffect
        val cached = ClassCache.read(ctx, tab.name)
        val map = if (cached.isNotEmpty()) cached else {
            val r = Aggregator.resolveClassMap(sources, tab)
            ClassCache.write(ctx, tab.name, r.tidMap)
            r.tidMap
        }
        if (map.isEmpty()) {
            loading = false
            return@LaunchedEffect
        }
        val fresh = Aggregator.category(
            sources, map, page,
            maxSources = 12,
            by = sortBy,
            area = area.ifEmpty { null },
            cls = cls.ifEmpty { null },
            year = year.ifEmpty { null }
        )
        items = if (page == 1) fresh else items + fresh
        loading = false
        if (fresh.isEmpty()) endReached = true
    }

    val gridState = rememberLazyGridState()
    val nearEnd by derivedStateOf {
        val info = gridState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd && !loading && !endReached) page++
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
            Box(Modifier.weight(1f)) {
                EmptyState("这个条件下没有找到内容，换个条件试试")
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
                    StaggerIn(index = index % 12, identity = item.source.api + item.vodId) {
                        PosterCard(item = merged, onClick = { onOpenDetail(merged) })
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
                        .padding(horizontal = Space.sm, vertical = 4.dp)
                )
            }
            item { Spacer(Modifier.width(Space.lg)) }
        }
    }
}
