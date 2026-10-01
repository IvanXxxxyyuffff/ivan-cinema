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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.ivan.cinema.IVANApp
import com.ivan.cinema.data.Aggregator
import com.ivan.cinema.data.ClassCache
import com.ivan.cinema.data.HomeTab
import com.ivan.cinema.data.MergedVod
import com.ivan.cinema.data.SourceHit
import com.ivan.cinema.data.SourcePool
import com.ivan.cinema.data.VodItem
import com.ivan.cinema.ui.components.LiquidPill
import com.ivan.cinema.ui.components.StaggerIn
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.Space

/** 分类页：网格 + 分页（「找具体片」的效率场景）。 */
@Composable
fun CategoryScreen(
    tab: HomeTab,
    columns: Int,
    onOpenDetail: (MergedVod) -> Unit
) {
    val pal = LocalIVAN.current
    val ctx = IVANApp.ctx()
    // 观察探测结果：原来 remember{} 只读一次且不是快照读，
    // 启动后台核验完成后「只取前 10 个可信源」的排序在本会话内永远不生效
    val verified by com.ivan.cinema.data.SourceHealth.verified
    val sources = remember(verified) { com.ivan.cinema.data.SourceHealth.sources(ctx) }
    var classMap by remember(tab) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var items by remember(tab) { mutableStateOf<List<VodItem>>(emptyList()) }
    var loading by remember(tab) { mutableStateOf(true) }
    var failed by remember(tab) { mutableStateOf(false) }
    var page by remember(tab) { mutableStateOf(1) }
    var endReached by remember(tab) { mutableStateOf(false) }
    var reloadKey by remember(tab) { mutableStateOf(0) }

    LaunchedEffect(tab, reloadKey) {
        failed = false
        loading = true
        val cached = ClassCache.read(ctx, tab.name)
        val resolved = if (cached.isNotEmpty()) cached else {
            runCatching { Aggregator.resolveClassMap(sources, tab) }.getOrNull()?.tidMap
        }
        if (resolved.isNullOrEmpty()) {
            // 原来这里直接 return，loading 永远是 true，界面永久停在骨架屏上，
            // 既没有失败提示也没有重试入口
            classMap = emptyMap()
            failed = true
            loading = false
            return@LaunchedEffect
        }
        if (cached.isEmpty()) ClassCache.write(ctx, tab.name, resolved)
        classMap = resolved
    }

    LaunchedEffect(classMap, page) {
        if (classMap.isEmpty() || endReached) return@LaunchedEffect
        val fresh = Aggregator.category(sources, classMap, page)
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
        // 不透明背景（遮住下层上一页）
        com.ivan.cinema.ui.PosterBlurBackdrop(
            items.firstOrNull { it.pic.isNotEmpty() }?.pic,
            posterAlpha = 0.30f
        )
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                tab.title,
                style = MaterialTheme.typography.headlineSmall,
                color = pal.ink,
                modifier = Modifier.weight(1f)
            )
        }

        if (loading && items.isEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(Space.lg),
                horizontalArrangement = Arrangement.spacedBy(Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.block),
                userScrollEnabled = false,
                modifier = Modifier.fillMaxSize()
            ) {
                items(12) {
                    Box(
                        Modifier
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(Radius.md))
                            .background(pal.surfaceRaised.copy(alpha = 0.55f))
                    )
                }
            }
        } else if (items.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable { if (failed) reloadKey++ }
            ) {
                EmptyState(if (failed) "这个分类暂时打不开，点一下重试" else "该分类暂无内容")
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(Space.lg),
                horizontalArrangement = Arrangement.spacedBy(Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.block),
                modifier = Modifier.fillMaxSize()
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
