package com.ivan.cinema

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.ivan.cinema.data.HomeTab
import com.ivan.cinema.data.MergedVod
import com.ivan.cinema.data.VodDetail
import com.ivan.cinema.db.AppDb
import com.ivan.cinema.db.WatchEntry
import com.ivan.cinema.player.DownloadCenter
import com.ivan.cinema.player.PlayerActivity
import com.ivan.cinema.ui.AmbientPosterBackdrop
import com.ivan.cinema.ui.CategoryScreen
import com.ivan.cinema.ui.DetailScreen
import com.ivan.cinema.ui.DownloadScreen
import com.ivan.cinema.ui.FilterScreen
import com.ivan.cinema.ui.HomeScreen
import com.ivan.cinema.ui.LoginScreen
import com.ivan.cinema.ui.SearchScreen
import com.ivan.cinema.ui.SettingsScreen
import com.ivan.cinema.ui.components.LiquidNavBar
import com.ivan.cinema.ui.components.LocalPortal
import com.ivan.cinema.ui.components.NavItem
import com.ivan.cinema.ui.runtime.RefreshRate
import com.ivan.cinema.ui.theme.DarkPalette
import com.ivan.cinema.ui.theme.IVANTheme
import com.ivan.cinema.ui.theme.MotionPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 推入栈的一页（搜索是覆盖层，不在此栈）。 */
sealed class Page {
    data class Category(val tab: HomeTab) : Page()
    data class Detail(val vod: MergedVod) : Page()
    data object Filter : Page()
    data object Login : Page()
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全屏沉浸：内容延伸到状态栏/导航栏之后，背景（海报模糊）才会铺满整屏；
        // 各屏用 statusBarsPadding 把内容推离状态栏文字即可（不露黑框）。
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        MotionPrefs.init(this)
        DownloadCenter.ensureInit(this)
        setContent {
            IVANTheme { Root() }
        }
    }

    override fun onResume() {
        super.onResume()
        MotionPrefs.refreshSystem(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) RefreshRate.apply(this, true)
    }

    @Composable
    private fun Root() {
        val scope = rememberCoroutineScope()

        // 根页（底栏三个平级页）
        var rootTab by remember { mutableStateOf(0) }   // 0 首页 / 1 下载 / 2 设置
        // 推入栈（详情 / 分类）
        val stack = remember { mutableStateListOf<Page>() }
        val push = remember { Animatable(1f) }
        val pushState = remember { mutableStateOf(1f) }
        var pushAnimating by remember { mutableStateOf(false) }
        // 搜索覆盖层（Portal 专属转场：搜索框裁剪揭示 + 内容退散 + 历史进场）
        val portalAnim = remember { Animatable(0f) }
        val portalState = remember { mutableStateOf(0f) }
        var searchOpen by remember { mutableStateOf(false) }
        var searchAnimating by remember { mutableStateOf(false) }

        var ambientPoster by remember { mutableStateOf<String?>(null) }
        var watchEntry by remember { mutableStateOf<WatchEntry?>(null) }

        val wide = LocalConfiguration.current.screenWidthDp >= 840
        val columns = if (wide) 6 else 3

        fun pushPage(p: Page) {
            if (pushAnimating) return
            stack.add(p)
            scope.launch {
                pushAnimating = true
                push.snapTo(0f)
                pushState.value = 0f
                push.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                    block = { pushState.value = value }
                )
                pushAnimating = false
            }
        }

        fun popPage() {
            if (stack.isEmpty() || pushAnimating) return
            scope.launch {
                pushAnimating = true
                push.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(200, easing = FastOutLinearInEasing),
                    block = { pushState.value = value }
                )
                stack.removeAt(stack.lastIndex)
                push.snapTo(1f)
                pushState.value = 1f
                pushAnimating = false
            }
        }

        fun openSearch() {
            if (searchOpen) return
            searchOpen = true
            scope.launch {
                searchAnimating = true
                portalAnim.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(560, easing = LinearEasing),
                    block = { portalState.value = value }
                )
                searchAnimating = false
            }
        }

        fun closeSearch() {
            if (!searchOpen) return
            scope.launch {
                searchAnimating = true
                portalAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(480, easing = LinearEasing),
                    block = { portalState.value = value }
                )
                searchOpen = false
                searchAnimating = false
            }
        }

        fun openDetail(m: MergedVod) {
            watchEntry = null
            scope.launch(Dispatchers.IO) {
                watchEntry = AppDb.get(applicationContext).watchDao().get(m.key)
            }
            pushPage(Page.Detail(m))
        }

        fun play(detail: VodDetail, lineIndex: Int, episodeIndex: Int) {
            val topPage = stack.lastOrNull() as? Page.Detail
            val topKey = topPage?.vod?.key
                ?: com.ivan.cinema.data.Aggregator.mergeKey(detail.name, detail.year)
            // 把该片命中的全部源一起交给播放器 —— 播放页才能一键换源
            val specs = ArrayList<String>()
            topPage?.vod?.hits?.forEach { hit ->
                specs.add("${hit.source.api}|${hit.source.name}|${hit.vodId}")
            }
            val it = Intent(this, PlayerActivity::class.java)
                .putExtra("vodKey", topKey)
                .putExtra("name", detail.name)
                .putExtra("pic", detail.pic)
                .putExtra("sourceApi", detail.source.api)
                .putExtra("sourceName", detail.source.name)
                .putExtra("vodId", detail.vodId)
                .putExtra("lineIndex", lineIndex)
                .putExtra("episodeIndex", episodeIndex)
                .putStringArrayListExtra("sources", specs)
                .putExtra("resume", true)
            startActivity(it)
        }

        fun playFromWatch(e: WatchEntry) {
            val it = Intent(this, PlayerActivity::class.java)
                .putExtra("vodKey", e.vodKey)
                .putExtra("name", e.name)
                .putExtra("pic", e.pic)
                .putExtra("sourceApi", e.sourceApi)
                .putExtra("sourceName", e.sourceName)
                .putExtra("lineIndex", e.lineIndex)
                .putExtra("episodeIndex", e.episodeIndex)
                .putExtra("resume", true)
            startActivity(it)
        }

        fun download(detail: VodDetail, lineIndex: Int, episodeIndex: Int) {
            // lineIndex 是聚合列表下标，而 detail.lines 是该源内部的线路列表 —— 恒用 0，
            // 否则 selectedLine≥1 时 getOrNull 返回 null，下载静默失败
            val ep = detail.lines.getOrNull(0)?.episodes?.getOrNull(episodeIndex) ?: return
            DownloadCenter.add(this, ep.url, detail.name + " · " + ep.name)
            Toast.makeText(this, "已加入下载队列", Toast.LENGTH_SHORT).show()
        }

        // 返回优先级：搜索 → 推入栈 → 放行给系统（栈底再返回 = 退出 APP）
        BackHandler(enabled = searchOpen) { closeSearch() }
        BackHandler(enabled = !searchOpen && stack.isNotEmpty()) { popPage() }

        Box(Modifier.fillMaxSize()) {
            AmbientPosterBackdrop(ambientPoster)

            // ── 根页层：搜索打开时按 Portal 淡出让位；有推入页时左移 ──
            CompositionLocalProvider(LocalPortal provides portalState) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val t = portalState.value
                            alpha = if (searchOpen || t > 0f) (1f - t) * (1f - t) else 1f
                            if (stack.isNotEmpty()) {
                                translationX = -pushState.value * size.width * 0.3f
                            }
                        }
                ) {
                    when (rootTab) {
                        0 -> HomeScreen(
                            columns = columns,
                            onOpenDetail = ::openDetail,
                            onOpenWatch = ::playFromWatch,
                            onAmbient = { url -> if (ambientPoster == null) ambientPoster = url },
                            onMore = { t -> pushPage(Page.Category(t)) },
                            onSearch = ::openSearch,
                            onFilter = { pushPage(Page.Filter) },
                            contentBottomPadding = 168.dp
                        )
                        1 -> DownloadScreen(
                            onPlayLocal = { url, title ->
                                val it = Intent(this@MainActivity, PlayerActivity::class.java)
                                    .putExtra("directUrl", url)
                                    .putExtra("name", title)
                                startActivity(it)
                            },
                            contentBottomPadding = 168.dp
                        )
                        else -> SettingsScreen(
                            contentBottomPadding = 168.dp,
                            onLogin = { pushPage(Page.Login) }
                        )
                    }
                }
            }

            // ── 搜索覆盖层（Portal 专属动画）──
            // ⚠️ 必须在推入栈**之前**绘制：搜索页 → 筛选页/详情页时，栈页要盖住它，
            // 否则两层同时可见（真机实锤过的重叠 bug）。
            if (searchOpen || portalState.value > 0f) {
                CompositionLocalProvider(LocalPortal provides portalState) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = portalState.value }
                    ) {
                        SearchScreen(
                            columns = columns,
                            onOpenDetail = ::openDetail,
                            onFilter = { pushPage(Page.Filter) },
                            contentBottomPadding = 168.dp
                        )
                    }
                }
            }

            // ── 推入栈：栈中所有页保留在组合树（滚动位置/数据/封面不丢），横向推入 ──
            stack.forEachIndexed { index, page ->
                val depthFromTop = stack.lastIndex - index
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (depthFromTop == 0) {
                                translationX = (1f - pushState.value) * size.width
                            } else {
                                translationX = -size.width
                            }
                        }
                ) {
                    key(pageKey(page)) {
                        when (page) {
                            is Page.Category -> CategoryScreen(
                                tab = page.tab,
                                columns = columns,
                                onOpenDetail = ::openDetail
                            )
                            is Page.Detail -> DetailScreen(
                                merged = page.vod,
                                watchEntry = watchEntry,
                                onPlay = ::play,
                                onDownload = ::download
                            )
                            is Page.Filter -> FilterScreen(
                                columns = columns,
                                onOpenDetail = ::openDetail,
                                onSearch = ::openSearch
                            )
                            is Page.Login -> LoginScreen(onDone = { popPage() })
                        }
                    }
                }
            }

            // ── 底部渐隐 + 底栏：根页时显示（搜索打开时也在，可跳其他页）──
            if (stack.isEmpty()) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(168.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, DarkPalette.canvas.copy(alpha = 0.92f))
                            )
                        )
                )
                LiquidNavBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
                        .fillMaxWidth(),
                    items = listOf(
                        NavItem("首页", Icons.Rounded.Home),
                        NavItem("下载", Icons.Rounded.Download),
                        NavItem("设置", Icons.Rounded.Settings),
                    ),
                    selected = rootTab,
                    onSelect = { i ->
                        if (searchOpen) {
                            scope.launch { portalAnim.snapTo(0f) }
                            portalState.value = 0f
                            searchOpen = false
                        }
                        rootTab = i
                    }
                )
            }
        }
    }

    private fun pageKey(page: Page): String = when (page) {
        is Page.Category -> "cat_${page.tab.name}"
        is Page.Detail -> "detail_${page.vod.key}"
        is Page.Filter -> "filter"
        is Page.Login -> "login"
    }
}
