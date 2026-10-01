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
    val sources = remember { com.ivan.cinema.data.SourceHealth.sources(ctx) }
    var classMap by remember(tab) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var items by remember(tab) { mutableStateOf<List<VodItem>>(emptyList()) }
    var loading by remember(tab) { mutableStateOf(true) }
    var page by remember(tab) { mutableStateOf(1) }
    var endReached by remember(tab) { mutableStateOf(false) }

    LaunchedEffect(tab) {
        val cached = ClassCache.read(ctx, tab.name)
        classMap = if (cached.isNotEmpty()) cached else {
            val r = Aggregator.resolveClassMap(sources, tab)
            ClassCache.write(ctx, tab.name, r.tidMap)
            r.tidMap
        }
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
            EmptyState("该分类暂无内容")
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
