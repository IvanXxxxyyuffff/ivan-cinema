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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ivan.cinema.IVANApp
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
    val sources = remember { SourceHealth.sources(ctx) }
    val watch by AppDb.get(IVANApp.app).watchDao().recent().collectAsState(initial = emptyList())

    var tab by remember { mutableStateOf(HomeTab.MOVIE) }
    var items by remember { mutableStateOf<List<VodItem>?>(null) }
    var page by remember { mutableStateOf(1) }
    var endReached by remember { mutableStateOf(false) }

    LaunchedEffect(tab) {
        items = null
        page = 1
        endReached = false
    }

    LaunchedEffect(tab, page) {
        if (endReached && page > 1) return@LaunchedEffect
        val cached = ClassCache.read(ctx, tab.name)
        val map = if (cached.isNotEmpty()) cached else {
            val r = Aggregator.resolveClassMap(sources, tab)
            ClassCache.write(ctx, tab.name, r.tidMap)
            r.tidMap
        }
        if (map.isEmpty()) {
            items = emptyList()
            return@LaunchedEffect
        }
        val fresh = Aggregator.category(sources, map, page, maxSources = 8, by = "time")
        items = if (page == 1) fresh else (items ?: emptyList()) + fresh
        if (fresh.isEmpty()) endReached = true
        fresh.firstOrNull { it.pic.isNotEmpty() }?.pic?.let(onAmbient)
    }

    val list = items
    val banner = list?.firstOrNull { it.pic.isNotEmpty() }
    val gridItems = list?.drop(1)?.take(24) ?: emptyList()

    LazyColumn(
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
                            .padding(horizontal = Space.md + 2.dp, vertical = Space.sm + 3.dp),
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
                            color = pal.inkMuted,
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
                            color = pal.inkMuted,
                            modifier = Modifier.clickable { onFilter() }
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
                        fontSize = if (on) 20.sp else 17.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) pal.ink else pal.inkMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.sm))
                            .clickable { tab = t }
                            .padding(vertical = Space.xs)
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
                    AsyncImage(
                        model = banner.pic,
                        contentDescription = banner.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
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
                            .padding(Space.md + 2.dp)
                    ) {
                        Text(
                            banner.name,
                            style = MaterialTheme.typography.titleLarge,
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
                    Row(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(Space.md + 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        repeat(4) { i ->
                            Box(
                                Modifier
                                    .size(if (i == 0) 7.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (i == 0) Color.White else Color.White.copy(alpha = 0.42f)
                                    )
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
                        "热播推荐",
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
                            .padding(horizontal = Space.sm, vertical = Space.xs)
                    )
                }
            }
        }

        if (list == null) {
            item(key = "skeleton") {
                Column(Modifier.padding(horizontal = Space.lg, vertical = Space.sm)) {
                    repeat(3) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                            repeat(2) {
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
                                }
                            }
                        }
                        Spacer(Modifier.height(Space.block))
                    }
                }
            }
        } else if (list.isEmpty()) {
            item(key = "empty") { EmptyState("该分类暂时没有内容") }
        } else {
            items(
                gridItems.chunked(2),
                key = { row -> row.joinToString("|") { it.source.api + it.vodId } }
            ) { rowItems ->
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
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (!endReached) {
                item(key = "loadmore") {
                    LaunchedEffect(Unit) { page++ }
                    Box(Modifier.fillMaxWidth().height(1.dp))
                }
            }
        }
    }
}

private fun VodItem.toMerged(): MergedVod = MergedVod(
    key = Aggregator.mergeKey(name, year),
    name = name,
    year = year,
    pic = pic,
    hits = mutableListOf(SourceHit(source, vodId, remarks))
)

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
                .background(pal.surfaceRaised)
        ) {
            if (item.pic.isNotEmpty()) {
                AsyncImage(
                    model = item.pic,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                FilmTile(Modifier.fillMaxSize())
            }
            val remark = item.remarks
            if (remark.isNotEmpty()) {
                val isBadge = remark.length <= 4
                Text(
                    remark,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(if (isBadge) Alignment.TopStart else Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(if (isBadge) Color(0xE6F04E23) else Color(0x99000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(Space.sm))
        Text(
            item.name,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
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
                        AsyncImage(
                            model = e.pic,
                            contentDescription = e.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
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
