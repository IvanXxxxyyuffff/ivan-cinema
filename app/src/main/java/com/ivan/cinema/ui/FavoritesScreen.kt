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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ivan.cinema.IVANApp
import com.ivan.cinema.data.FavStore
import com.ivan.cinema.data.MergedVod
import com.ivan.cinema.data.SourceHit
import com.ivan.cinema.data.VodSource
import com.ivan.cinema.db.FavEntry
import com.ivan.cinema.prefetch
import com.ivan.cinema.ui.components.EmptyState
import com.ivan.cinema.ui.components.StaggerIn
import com.ivan.cinema.ui.components.press
import com.ivan.cinema.ui.components.pressDip
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.Space
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 「我的片单」——收藏（想看）列表。
 *
 * 底栏在推入页隐藏，所以本页必须自带返回出口（与详情/分类/筛选页同款返回胶囊）。
 * 数据直接读 [FavStore.allFor] 这个 Flow：在别处收藏/移除后本页自动增删，无需手动刷新。
 */
@Composable
fun FavoritesScreen(
    columns: Int,
    onOpenDetail: (MergedVod) -> Unit,
    onBack: () -> Unit = {},
    contentBottomPadding: Dp = 0.dp
) {
    val pal = LocalIVAN.current
    val ctx = IVANApp.ctx()
    val scope = rememberCoroutineScope()

    var favs by remember { mutableStateOf<List<FavEntry>>(emptyList()) }
    // 首次读到 DB 之前 favs 必为空，直接渲染空态会闪一下「片单还是空的」
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(ctx) {
        FavStore.allFor(ctx).collect { list ->
            favs = list
            loaded = true
        }
    }

    val gridState = rememberLazyGridState()
    // 预热「即将进入视口」的封面：视口最后一项之后 9 张，条数上限由 prefetch 兜底
    val lastVisible by remember {
        derivedStateOf { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
    }
    LaunchedEffect(favs.size, lastVisible) {
        val start = lastVisible + 1
        if (start >= favs.size) return@LaunchedEffect
        prefetch(ctx, favs.drop(start).take(9).map { it.pic }.filter { it.isNotEmpty() })
    }

    Box(Modifier.fillMaxSize()) {
        // 不透明背景（遮住下层上一页）
        PosterBlurBackdrop(favs.firstOrNull { it.pic.isNotEmpty() }?.pic, posterAlpha = 0.28f)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // 顶栏：返回胶囊 + 标题 + 计数
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg, vertical = Space.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 与详情页返回胶囊同款（48dp / 黑 55% / 1px 白描边）
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
                    "我的片单",
                    style = MaterialTheme.typography.headlineSmall,
                    color = pal.ink,
                    modifier = Modifier.weight(1f)
                )
                if (favs.isNotEmpty()) {
                    Text(
                        "${favs.size} 部",
                        style = MaterialTheme.typography.labelSmall,
                        color = pal.inkMutedOnGlass
                    )
                }
            }

            when {
                !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "正在读取片单…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = pal.inkMuted
                    )
                }

                favs.isEmpty() -> Box(
                    Modifier
                        .fillMaxSize()
                        .padding(bottom = contentBottomPadding),
                    contentAlignment = Alignment.Center
                ) {
                    // 空态必须告诉用户「去哪加」——否则这片单看起来像坏了
                    EmptyState(
                        eyebrow = "片单",
                        title = "片单还是空的",
                        hint = "在详情页点「收藏」，想看的片都会出现在这里"
                    )
                }

                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(
                        start = Space.lg, end = Space.lg, top = Space.sm, bottom = contentBottomPadding
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                    verticalArrangement = Arrangement.spacedBy(Space.block),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(favs, key = { _, it -> it.vodKey }) { index, f ->
                        val merged = f.toMerged()
                        StaggerIn(index = index, identity = f.vodKey) {
                            Box(Modifier.fillMaxWidth()) {
                                // 复用统一海报卡：海报 + 片名 + 年份，点开详情
                                PosterCard(item = merged, onClick = { onOpenDetail(merged) })
                                // 移除：覆盖在卡片右上角，48dp 命中区；自身消费点击，不会误开详情
                                val rmInteraction = remember { MutableInteractionSource() }
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .size(48.dp)
                                        .pressDip(rmInteraction, to = press.control)
                                        .clickable(interactionSource = rmInteraction, indication = null) {
                                            scope.launch { FavStore.remove(ctx, f.vodKey) }
                                        },
                                    contentAlignment = Alignment.TopEnd
                                ) {
                                    Box(
                                        Modifier
                                            .padding(top = 4.dp, end = 4.dp)
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.55f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Rounded.Close,
                                            contentDescription = "移除收藏",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * FavEntry → MergedVod：详情页用 merged.key 作为观看记录/追剧的主键，
 * 所以直接用落库的 vodKey，而不是按片名重算 mergeKey（二者对同一部片应一致，
 * 但落库值才是权威）。hits 至少带一条，保证详情页能补全多源线路。
 */
private fun FavEntry.toMerged(): MergedVod = MergedVod(
    key = vodKey,
    name = name,
    year = year,
    pic = pic,
    hits = mutableListOf(SourceHit(VodSource(sourceName, sourceApi), vodId, ""))
)
