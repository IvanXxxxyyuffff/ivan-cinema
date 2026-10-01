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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivan.cinema.IVANApp
import com.ivan.cinema.data.Aggregator
import com.ivan.cinema.data.ClassCache
import com.ivan.cinema.data.HomeTab
import com.ivan.cinema.data.MergedVod
import com.ivan.cinema.data.SourcePool
import com.ivan.cinema.db.AppDb
import com.ivan.cinema.db.SearchEntry
import com.ivan.cinema.ui.components.DefaultLiquid
import com.ivan.cinema.ui.components.LiquidBar
import com.ivan.cinema.ui.components.PortalTo
import com.ivan.cinema.ui.components.StaggerIn
import com.ivan.cinema.ui.components.liquidGlass
import com.ivan.cinema.ui.components.portalIn
import com.ivan.cinema.ui.components.portalReveal
import com.ivan.cinema.ui.components.portalRevealProgress
import com.ivan.cinema.ui.components.pressDip
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索页 —— 由首页搜索图标通过 Portal 时间轴展开而来：
 *   搜索框裁剪揭示（右缘固定、向左展开）→ 历史/热门两列从两侧依次落位。
 * 进入不弹键盘；点搜索框才聚焦。
 */
@Composable
fun SearchScreen(
    columns: Int,
    onOpenDetail: (MergedVod) -> Unit,
    onFilter: () -> Unit = {},
    contentBottomPadding: Dp = 0.dp
) {
    val pal = LocalIVAN.current
    val scope = rememberCoroutineScope()
    val ctx = IVANApp.ctx()
    val sources = remember { com.ivan.cinema.data.SourceHealth.sources(ctx) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<LinkedHashMap<String, MergedVod>>(LinkedHashMap()) }
    var searching by remember { mutableStateOf(false) }
    var doneCount by remember { mutableStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var debounceJob by remember { mutableStateOf<Job?>(null) }
    var hotWords by remember { mutableStateOf<List<String>>(emptyList()) }

    // 搜索代次：被取消的旧任务完成时不能把新一轮的 searching 提前置 false
    val searchGen = remember { java.util.concurrent.atomic.AtomicInteger(0) }

    val history by AppDb.get(IVANApp.app).searchDao().recent()
        .collectAsState(initial = emptyList<SearchEntry>())

    // 热门搜索：无历史时用各源「最近更新」的片名充当（采集源没有热词接口）
    LaunchedEffect(Unit) {
        if (hotWords.isNotEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val cached = ClassCache.read(ctx, HomeTab.MOVIE.name)
            val map = if (cached.isNotEmpty()) cached else {
                val r = Aggregator.resolveClassMap(sources, HomeTab.MOVIE)
                ClassCache.write(ctx, HomeTab.MOVIE.name, r.tidMap)
                r.tidMap
            }
            if (map.isEmpty()) return@withContext
            val fresh = Aggregator.category(sources, map, 1)
            hotWords = fresh.map { it.name }.filter { it.isNotBlank() }.distinct().take(12)
        }
    }

    fun startSearch(q: String) {
        job?.cancel()
        results = LinkedHashMap()
        doneCount = 0
        if (q.isBlank()) return
        val gen = searchGen.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            AppDb.get(IVANApp.app).searchDao().upsert(
                SearchEntry(q.trim(), System.currentTimeMillis())
            )
        }
        searching = true
        job = scope.launch {
            val map = LinkedHashMap<String, MergedVod>()
            Aggregator.searchAll(sources, q)
                .onEach { m ->
                    withContext(Dispatchers.Main.immediate) {
                        // Aggregator 对同一个 key 重发的始终是同一个实例，且该实例已经
                        // 累积了全部来源的 hits —— 直接覆盖即可。原实现
                        // exist.hits.clear(); exist.hits.addAll(m.hits) 在 exist === m
                        // 时等于清空自己，会让所有多源片子的 hits 全部归零。
                        map[m.key] = m
                        results = LinkedHashMap(map)
                        doneCount++
                    }
                }
                .launchIn(this)
        }
        job?.invokeOnCompletion { if (searchGen.get() == gen) searching = false }
    }

    Column(Modifier.fillMaxSize()) {
        // ── 搜索框：裁剪揭示（右缘固定，向左展开）──
        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Space.lg, vertical = Space.md)
                .portalReveal()
        ) {
            val innerAlpha = portalRevealProgress()
            LiquidBar(modifier = Modifier.fillMaxWidth(), radius = Radius.pill, elevated = true) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = innerAlpha }
                        .padding(horizontal = Space.md + 2.dp, vertical = Space.sm + 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        tint = pal.accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(Space.sm + 2.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { q ->
                            query = q
                            job?.cancel()
                            debounceJob?.cancel()
                            searching = false
                            // 输入即搜：防抖 320ms，不用按回车
                            if (q.isNotBlank()) {
                                debounceJob = scope.launch {
                                    delay(320)
                                    startSearch(q)
                                }
                            } else {
                                results = LinkedHashMap()
                            }
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = pal.ink, fontSize = 15.sp),
                        cursorBrush = SolidColor(pal.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            // 用户主动回车：取消防抖并收起键盘
                            debounceJob?.cancel()
                            keyboard?.hide()
                            startSearch(query)
                        }),
                        decorationBox = { inner ->
                            Box(
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    // 只有用户主动点框才聚焦弹键盘
                                    focus.requestFocus()
                                }
                            ) {
                                if (query.isEmpty()) {
                                    Text(
                                        "搜索片名，20 个源同时出动",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = pal.inkMuted
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focus)
                    )
                    if (query.isNotEmpty()) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "清空",
                            tint = pal.inkMuted,
                            modifier = Modifier
                                .size(48.dp)
                                .clickable {
                                    // 必须取消防抖：否则清空后 320ms，旧关键词的结果会自己冒出来
                                    debounceJob?.cancel()
                                    query = ""
                                    results = LinkedHashMap()
                                    job?.cancel()
                                    searching = false
                                }
                                .padding(14.dp)
                        )
                        Spacer(Modifier.width(Space.xs))
                    }
                }
            }
        }

        if (searching) {
            Text(
                "正在聚合 $doneCount 个源的结果…",
                style = MaterialTheme.typography.labelSmall,
                color = pal.inkMuted,
                modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.xs)
            )
        }

        // 筛选入口（腾讯视频形态：搜不到就按条件筛）
        if (query.isBlank()) {
            val filterInteraction = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg, vertical = Space.xs)
                    .pressDip(filterInteraction, to = 0.96f)
                    .clip(RoundedCornerShape(Radius.md))
                    .clickable(interactionSource = filterInteraction, indication = null) { onFilter() }
                    .padding(vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "筛选",
                    style = MaterialTheme.typography.titleMedium,
                    color = pal.ink,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "按分类 / 地区 / 类型 / 年份 / 排序找片  ›",
                    style = MaterialTheme.typography.bodyMedium,
                    color = pal.inkMuted
                )
            }
        }

        val list = results.values.toList().let { l ->
            // 搜索完成后按命中源数降序（多源都有的片更可靠、更可能是用户要找的）
            if (!searching) l.sortedByDescending { it.hits.size } else l
        }
        val hasQuery = query.isNotBlank()
        if (list.isEmpty() && !searching && hasQuery) {
            // 搜过了但一条都没命中。原来这种情况会退回「历史/热词」，
            // 看起来就像搜索根本没生效，所以单独给一个无结果态。
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = contentBottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(Space.xxl))
                Text(
                    "没有找到「$query」",
                    style = MaterialTheme.typography.titleMedium,
                    color = pal.ink
                )
                Spacer(Modifier.height(Space.sm))
                Text(
                    "换个片名试试，或用「筛选」按分类找",
                    style = MaterialTheme.typography.bodyMedium,
                    color = pal.inkMutedOnGlass
                )
            }
        } else if (list.isEmpty() && !searching) {
            // ── 历史 / 热门：两列，左列从左进、右列从右进，依次落位 ──
            val entries: List<Pair<String, Boolean>> = if (history.isNotEmpty()) {
                history.map { it.keyword to true }
            } else {
                hotWords.map { it to false }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    // 历史最多 20 条（10 行），原来既不能滚动也没有底部留白，
                    // 会溢出屏幕并被底栏压住
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = contentBottomPadding)
            ) {
                Text(
                    if (history.isNotEmpty()) "搜索历史" else "热门搜索",
                    style = MaterialTheme.typography.titleMedium,
                    color = pal.ink,
                    modifier = Modifier
                        .portalIn(PortalTo.Right, 1)
                        .padding(horizontal = Space.lg, vertical = Space.sm)
                )
                if (entries.isEmpty()) {
                    Text(
                        "输入片名开始搜索",
                        style = MaterialTheme.typography.bodyMedium,
                        color = pal.inkMuted,
                        modifier = Modifier
                            .portalIn(PortalTo.Right, 2)
                            .padding(horizontal = Space.lg)
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Space.lg),
                        verticalArrangement = Arrangement.spacedBy(Space.sm)
                    ) {
                        entries.chunked(2).forEachIndexed { rowIdx, rowItems ->
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                                rowItems.forEachIndexed { col, pair ->
                                    val word = pair.first
                                    val isHistory = pair.second
                                    val index = rowIdx * 2 + col
                                    val fromLeft = col == 0
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .then(
                                                if (fromLeft) Modifier.portalIn(PortalTo.Left, index % 9 + 2)
                                                else Modifier.portalIn(PortalTo.Right, index % 9 + 2)
                                            )
                                    ) {
                                        val wordInteraction = remember { MutableInteractionSource() }
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .pressDip(wordInteraction, to = 0.96f)
                                                .liquidGlass(
                                                    RoundedCornerShape(Radius.md),
                                                    DefaultLiquid,
                                                    elevated = false
                                                )
                                                .clickable(interactionSource = wordInteraction, indication = null) {
                                                    query = word
                                                    startSearch(word)
                                                }
                                                .padding(horizontal = Space.md, vertical = Space.sm + 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                word,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = pal.ink,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (isHistory) {
                                                Icon(
                                                    Icons.Rounded.Close,
                                                    contentDescription = "删除",
                                                    tint = pal.inkMuted.copy(alpha = 0.7f),
                                                    modifier = Modifier
                                                        .size(18.dp)
                                                        .clickable {
                                                            scope.launch(Dispatchers.IO) {
                                                                AppDb.get(IVANApp.app).searchDao().delete(word)
                                                            }
                                                        }
                                                )
                                            }
                                        }
                                    }
                                }
                                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    if (history.isNotEmpty()) {
                        Spacer(Modifier.height(Space.block))
                        val clearInteraction = remember { MutableInteractionSource() }
                        Text(
                            "清空历史",
                            style = MaterialTheme.typography.labelSmall,
                            color = pal.inkMuted,
                            modifier = Modifier
                                .padding(horizontal = Space.lg)
                                .pressDip(clearInteraction, to = 0.94f)
                                .clip(RoundedCornerShape(Radius.pill))
                                .clickable(interactionSource = clearInteraction, indication = null) {
                                    scope.launch(Dispatchers.IO) {
                                        AppDb.get(IVANApp.app).searchDao().clear()
                                    }
                                }
                                .padding(horizontal = Space.sm, vertical = Space.xs)
                        )
                    }
                }
            }
        } else if (list.isEmpty() && searching) {
            PosterGridSkeleton(columns = columns, count = columns * 2)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(
                    start = Space.lg, end = Space.lg, top = Space.sm, bottom = contentBottomPadding
                ),
                horizontalArrangement = Arrangement.spacedBy(Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.block),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(list, key = { _, it -> it.key }) { index, m ->
                    StaggerIn(index = index % 12, identity = m.key) {
                        PosterCard(item = m, onClick = { onOpenDetail(m) })
                    }
                }
            }
        }
    }
}
