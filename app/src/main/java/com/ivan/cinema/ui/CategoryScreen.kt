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
import com.ivan.cinema.data.AnimeSub
import com.ivan.cinema.data.ClassCache
import com.ivan.cinema.data.HeatRank
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 分类页：网格 + 分页（「找具体片」的效率场景）。 */
@Composable
fun CategoryScreen(
    tab: HomeTab,
    columns: Int,
    onOpenDetail: (MergedVod) -> Unit,
    // 底栏在推入页隐藏，本页没有系统返回键以外的出口 —— 必须由调用方接上 popPage()
    onBack: () -> Unit = {}
) {
    val pal = LocalIVAN.current
    val ctx = IVANApp.ctx()
    // 观察探测结果：原来 remember{} 只读一次且不是快照读，
    // 启动后台核验完成后「只取前 10 个可信源」的排序在本会话内永远不生效
    val verified by com.ivan.cinema.data.SourceHealth.verified
    val sources = remember(verified) { com.ivan.cinema.data.SourceHealth.sources(ctx) }
    // 动漫有子专栏（国漫/日漫/欧美/港台）；其他分类没有
    val subs = remember(tab) {
        if (tab == HomeTab.ANIME) AnimeSub.entries.toList() else emptyList()
    }
    var sub by remember(tab) { mutableStateOf(AnimeSub.ALL) }
    // 解析用的三件套：分类名匹配规则、兜底 tid、缓存 key。
    // 子专栏的 key 必须和主分类分开（ANIME_ALL vs ANIME），否则「国漫」会读到「动漫」的映射
    val matchers = if (subs.isEmpty()) tab.matchers else sub.matchers
    val defaultTid = if (subs.isEmpty()) tab.defaultTid else sub.defaultTid
    val cacheKey = if (subs.isEmpty()) tab.name else sub.cacheKey
    var classMap by remember(cacheKey) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var items by remember(cacheKey) { mutableStateOf<List<VodItem>>(emptyList()) }
    var loading by remember(cacheKey) { mutableStateOf(true) }
    var failed by remember(cacheKey) { mutableStateOf(false) }
    var page by remember(cacheKey) { mutableStateOf(1) }
    var endReached by remember(cacheKey) { mutableStateOf(false) }
    var reloadKey by remember(cacheKey) { mutableStateOf(0) }
    // 当前页的流是否仍在收集：驱动「还在加载更多源…」与分页守卫
    var loadingMore by remember(tab) { mutableStateOf(false) }

    // 国漫/日漫按热度排。榜单来自 B 站国创/番剧榜（为什么只有 B 站，见 HeatRank 注释）。
    // 其他分类 heatKind 为 null，榜单为空，下面的排序原样返回 —— 等于没做任何事。
    val heatKind = when {
        tab != HomeTab.ANIME -> null
        sub == AnimeSub.CN -> HeatRank.Kind.GUOCHUANG
        sub == AnimeSub.JP -> HeatRank.Kind.FANJU
        else -> null
    }
    var heat by remember(heatKind) { mutableStateOf<List<String>>(emptyList()) }
    /**
     * 榜单这条线是否已经落定（成功或失败都算）。
     *
     * 必须在榜单落定**之前**压住网格不上屏：先按源站顺序上屏、1 秒后再重排，
     * LazyGrid 会按 item key 锚定滚动位置，重排后第一条被锚在原处，
     * 视觉上就是"顺序根本没变"（第一版正是这样，看着像功能没生效）。
     * 但也不能无限等，所以并行放一个 2.5s 的兜底 —— 榜单拿不到就照常渲染。
     */
    var heatSettled by remember(heatKind) { mutableStateOf(heatKind == null) }
    LaunchedEffect(heatKind) {
        if (heatKind == null) {
            heatSettled = true
            return@LaunchedEffect
        }
        launch {
            delay(2_500)
            heatSettled = true
        }
        heat = HeatRank.load(ctx, heatKind)
        heatSettled = true
    }

    // 源权重：sources 已按可信度排序（SourceHealth 内已折入 SharedHealth.isBad 降权），
    // 越靠前权重越高 → categoryStream 先查先落位。纯本地换算，不发任何请求。
    val sourceWeight: (String) -> Int = remember(sources) {
        val rank = HashMap<String, Int>(sources.size)
        sources.forEachIndexed { i, s -> rank[s.api] = sources.size - i }
        fun(api: String): Int = rank[api] ?: 0
    }

    LaunchedEffect(cacheKey, reloadKey) {
        failed = false
        loading = true
        val cached = ClassCache.read(ctx, cacheKey)
        val resolved = if (cached.isNotEmpty()) cached else {
            runCatching { Aggregator.resolveClassMap(sources, matchers, defaultTid) }.getOrNull()?.tidMap
        }
        if (resolved.isNullOrEmpty()) {
            // 原来这里直接 return，loading 永远是 true，界面永久停在骨架屏上，
            // 既没有失败提示也没有重试入口
            classMap = emptyMap()
            failed = true
            loading = false
            return@LaunchedEffect
        }
        if (cached.isEmpty()) ClassCache.write(ctx, cacheKey, resolved)
        classMap = resolved
    }

    LaunchedEffect(classMap, page) {
        if (classMap.isEmpty() || endReached) return@LaunchedEffect
        if (page > 1 && items.isEmpty()) return@LaunchedEffect
        // 分页基准：已上屏条目视为已存在，新批次只追加，绝不重排/丢项
        val base = if (page == 1) emptyList() else items
        val baseKeys = base.map { Aggregator.mergeKey(it.name, it.year) }.toHashSet()
        var addedAny = false
        loadingMore = true
        runCatching {
            Aggregator.categoryStream(sources, classMap, page, sourceWeight = sourceWeight)
                .collect { batch ->
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
    // 上屏顺序：国漫/日漫按热度排，其余分类原样。
    //
    // 热度是两段合成，因为单靠任何一段都不够：
    //   ① B 站国创/番剧榜位次 —— 权威，但只覆盖到 5~15% 的条目（采集源的动漫分类里
    //      大量是《开心锤锤》这类动态漫/短动画，跟 B 站精品国创根本不是同一批片库）；
    //   ② 源站 vod_hits 播放量 —— 每条都有机会拿到，作为未上榜条目的依据。
    // 所以：上榜的按榜位在前，未上榜的按播放量降序跟在后面。
    // sortedWith 是稳定排序，两段内部的并列项保持源站原顺序，分页追加不会搅乱已有条目。
    val shown = remember(items, heat) {
        if (heat.isEmpty() && items.none { it.hits > 0 }) items
        else items.sortedWith(
            compareBy({ HeatRank.rankOf(heat, it.name) }, { -it.hits })
        )
    }

    // 预热「即将进入视口」的封面：视口最后一项之后 9 张，上限由 prefetch 兜底
    val lastVisible by remember {
        derivedStateOf { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
    }
    LaunchedEffect(shown.size, lastVisible) {
        val start = lastVisible + 1
        if (start >= shown.size) return@LaunchedEffect
        prefetch(ctx, shown.drop(start).take(9).map { it.pic }.filter { it.isNotEmpty() })
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
                tab.title,
                style = MaterialTheme.typography.headlineSmall,
                color = pal.ink,
                modifier = Modifier.weight(1f)
            )
        }

        // 动漫子专栏（国漫 / 日漫 / 欧美 / 港台）。
        // 放在标题行下面而不是塞进首页那排 tab：首页 tab 已经有 8 个、右侧已被裁切，
        // 再加 2 个只会让那一行更难用；而且子专栏只在动漫下有意义。
        if (subs.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Space.lg),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                modifier = Modifier.padding(bottom = Space.md)
            ) {
                items(subs, key = { it.name }) { s ->
                    val on = s == sub
                    Text(
                        s.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) pal.accentInk else pal.inkMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(if (on) pal.accent else Color.White.copy(alpha = 0.07f))
                            .clickable { sub = s }
                            // 12dp 让 chip 的点击目标到 48dp
                            .padding(horizontal = Space.md, vertical = 12.dp)
                    )
                }
            }
            // 说清楚这一页按什么排：不写的话，"为什么顺序跟源站不一样"会变成疑问
            if (heatKind != null) {
                Text(
                    if (heat.isEmpty()) "热度榜暂时取不到，按源站播放量排序"
                    else "按热度排序 · ${heatKind.label}榜取自 B 站，未上榜的按源站播放量",
                    style = MaterialTheme.typography.labelSmall,
                    color = pal.inkMuted,
                    modifier = Modifier.padding(start = Space.lg, bottom = Space.sm)
                )
            }
        }

        // 榜单没落定就先别上屏（理由见 heatSettled 的注释）；其余情况沿用原来的骨架屏条件
        if (!heatSettled || (loading && items.isEmpty())) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(Space.lg),
                horizontalArrangement = Arrangement.spacedBy(Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.block),
                userScrollEnabled = false,
                modifier = Modifier.fillMaxSize()
            ) {
                items(12) { CardSkeleton() }
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
                itemsIndexed(shown, key = { _, it -> it.source.api + it.vodId }) { index, item ->
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

/**
 * 骨架卡：与 [PosterCard] 同几何 —— 2:3 海报 + 22dp 标题条 + 14dp 元信息条
 * （含 Space.sm / 2dp / Space.sm 的间距）。原来只有一个裸灰盒，每行比真卡矮约 44dp，
 * 数据一到网格就整体跳动。
 */
@Composable
private fun CardSkeleton() {
    val pal = LocalIVAN.current
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(Radius.md))
                .background(pal.surfaceRaised.copy(alpha = 0.55f))
        )
        Box(
            Modifier
                .padding(start = 2.dp, end = 2.dp, top = Space.sm)
                .fillMaxWidth(0.78f)
                .height(22.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(pal.surfaceRaised.copy(alpha = 0.45f))
        )
        Box(
            Modifier
                .padding(start = 2.dp, end = 2.dp, top = 2.dp, bottom = Space.sm)
                .fillMaxWidth(0.45f)
                .height(14.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(pal.surfaceRaised.copy(alpha = 0.45f))
        )
    }
}
