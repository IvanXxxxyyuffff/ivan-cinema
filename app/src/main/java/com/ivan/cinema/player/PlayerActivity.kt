package com.ivan.cinema.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.ivan.cinema.IVANApp
import com.ivan.cinema.data.Account
import com.ivan.cinema.data.ErrorReporter
import com.ivan.cinema.data.MacCmsApi
import com.ivan.cinema.data.SharedHealth
import com.ivan.cinema.data.VodSource
import com.ivan.cinema.db.AppDb
import com.ivan.cinema.db.WatchEntry
import com.ivan.cinema.ui.components.MotionIconSwap
import com.ivan.cinema.ui.components.MotionTextSwap
import com.ivan.cinema.ui.theme.IVANTheme
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.MetaMono
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.Space
import com.ivan.cinema.ui.theme.motionExit
import com.ivan.cinema.ui.theme.motionFade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.abs

object PlayPrefs {
    private const val KEY_SPEED = "speed"
    fun speed(ctx: Context): Float =
        ctx.getSharedPreferences("ivan_play", Context.MODE_PRIVATE).getFloat(KEY_SPEED, 1f)
    fun setSpeed(ctx: Context, v: Float) {
        ctx.getSharedPreferences("ivan_play", Context.MODE_PRIVATE).edit().putFloat(KEY_SPEED, v).apply()
    }

    /**
     * 默认清晰度策略：false = 自动（交给播放器按带宽选），true = 尽量最高档。
     * 默认**最高**：这是个 20 人的自用 App，主人明确要的是画质；网络差时用户自己在面板里
     * 切回「自动」即可，选择会记住。
     */
    private const val KEY_QUALITY = "quality_highest"
    fun preferHighest(ctx: Context): Boolean =
        ctx.getSharedPreferences("ivan_play", Context.MODE_PRIVATE).getBoolean(KEY_QUALITY, true)
    fun setPreferHighest(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences("ivan_play", Context.MODE_PRIVATE).edit().putBoolean(KEY_QUALITY, v).apply()
    }

    /** 画质增强（锐化超分）。默认关：GPU 开销随分辨率上升，中端机可能掉帧。 */
    private const val KEY_ENHANCE = "quality_enhance"
    fun enhance(ctx: Context): Boolean =
        ctx.getSharedPreferences("ivan_play", Context.MODE_PRIVATE).getBoolean(KEY_ENHANCE, false)
    fun setEnhance(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences("ivan_play", Context.MODE_PRIVATE).edit().putBoolean(KEY_ENHANCE, v).apply()
    }
}

/** 一条可用线路 = 一个源（含该源该片的集数列表）。 */
private data class SourceLine(
    val api: String,
    val name: String,
    val vodId: String,
    val episodes: List<com.ivan.cinema.data.Episode>
)

@UnstableApi
class PlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()
        setContent {
            IVANTheme {
                PlayerScreen(
                    vodKey = intent.getStringExtra("vodKey") ?: "",
                    name = intent.getStringExtra("name") ?: "",
                    pic = intent.getStringExtra("pic") ?: "",
                    sourceApi = intent.getStringExtra("sourceApi") ?: "",
                    sourceName = intent.getStringExtra("sourceName") ?: "",
                    vodId = intent.getStringExtra("vodId") ?: "",
                    sourceSpecs = intent.getStringArrayListExtra("sources") ?: arrayListOf(),
                    initialEpisode = intent.getIntExtra("episodeIndex", 0),
                    resume = intent.getBooleanExtra("resume", false),
                    directUrl = intent.getStringExtra("directUrl"),
                    onExit = { finish() }
                )
            }
        }
    }

    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    @Composable
    private fun PlayerScreen(
        vodKey: String, name: String, pic: String,
        sourceApi: String, sourceName: String, vodId: String,
        sourceSpecs: List<String>,
        initialEpisode: Int,
        resume: Boolean, directUrl: String?,
        onExit: () -> Unit
    ) {
        val pal = LocalIVAN.current
        val scope = rememberCoroutineScope()
        val context = this

        var lines by remember { mutableStateOf<List<SourceLine>>(emptyList()) }
        var lineIndex by remember { mutableStateOf(0) }
        var currentEpisode by remember { mutableStateOf(initialEpisode) }
        var resolvedUrl by remember { mutableStateOf<String?>(null) }
        var resolving by remember { mutableStateOf(true) }
        var controlsVisible by remember { mutableStateOf(true) }
        var isPlaying by remember { mutableStateOf(false) }
        var isBuffering by remember { mutableStateOf(true) }
        var ended by remember { mutableStateOf(false) }
        var positionMs by remember { mutableStateOf(0L) }
        var durationMs by remember { mutableStateOf(0L) }
        var dragging by remember { mutableStateOf(false) }
        var dragPos by remember { mutableStateOf(0L) }
        var speed by remember { mutableStateOf(PlayPrefs.speed(context)) }
        var showEpisodes by remember { mutableStateOf(false) }
        var showSpeedMenu by remember { mutableStateOf(false) }
        var showSubtitleMenu by remember { mutableStateOf(false) }
        var hasSubtitle by remember { mutableStateOf(false) }
        // 点播放即全屏：进入播放页直接横屏 + 沉浸，不用再手动点一次全屏
        var fullscreen by remember { mutableStateOf(true) }
        var gestureHint by remember { mutableStateOf<String?>(null) }
        var playbackError by remember { mutableStateOf<String?>(null) }
        var switchingSource by remember { mutableStateOf(false) }
        // 换源面板 / 清晰度面板
        var showSources by remember { mutableStateOf(false) }
        var showQuality by remember { mutableStateOf(false) }
        // 推荐源排序结果（api 列表，最好的在前）。null = 还没算出来
        var rankedApis by remember { mutableStateOf<List<String>?>(null) }
        /**
         * 播放器**实际拿到**的视频轨高度（去重、降序）。
         *
         * 清晰度只能从这里出：采集源的 vod_play_from 只有 CDN 名（mtm3u8 / mtyun / lzm3u8…），
         * 一个 1080/720 标记都没有（5 个真实源全探过）。硬编一份「标清/高清/超清」列表
         * 点下去什么都不会变，那是假的。HLS 多码率变体才是真实存在的档位，
         * 所以档位来自轨道，没有变体时就只有一个「自动」。
         */
        var qualityHeights by remember { mutableStateOf<List<Int>>(emptyList()) }
        // 选中的上限高度，0 = 自动（不限）
        var maxHeight by remember { mutableStateOf(0) }
        // 默认清晰度策略：尽量最高档（自用 App，默认开；面板里可切回自动，选择会记住）
        var preferHighest by remember { mutableStateOf(PlayPrefs.preferHighest(context)) }
        // 画质增强（锐化超分），默认关
        var enhance by remember { mutableStateOf(PlayPrefs.enhance(context)) }

        // 当前真正生效的源与影片 id：换源后会变，保存观看进度必须用它们，
        // 否则切过源之后记录里存的还是进来时那一条线路
        var currentSourceApi by remember { mutableStateOf(sourceApi) }
        var currentSourceName by remember { mutableStateOf(sourceName) }
        var currentVodId by remember { mutableStateOf(vodId) }

        // ── 自动容错：一条线路失败就静默换下一条，最多 3 次 ──
        // 无限轮询会让 App 像在自己翻台，比老实报错更糟；所以硬上限 + 去重。
        val maxAutoFailover = 3
        // 本次「集/选源」序列里已自动尝试过几次（手动选集/换源/播放成功后清零）
        var autoAttempts by remember { mutableStateOf(0) }
        // 本次序列里已经失败过的源 api，避免在两个死源之间来回弹
        var autoTriedApis by remember { mutableStateOf<Set<String>>(emptySet()) }
        // 一次自动换源正在路上：挡住同一失败的重入，避免重复消耗尝试次数
        var failoverInFlight by remember { mutableStateOf(false) }

        /**
         * 播放失败统一入口：静默上报 + 自动换源。
         * 解析失败与 onPlayerError 都走这里，保证两条路径行为一致。
         * 不做任何可能抛异常的事；换源走的是与手动换源相同的状态变量，
         * 因此 currentEpisode / 进度保持逻辑完全复用。
         */
        fun handlePlaybackFailure(baseMessage: String) {
            if (failoverInFlight) return
            // 直链/离线播放失败与线路无关，换源也救不了本地文件：不上报、不换源
            if (!directUrl.isNullOrEmpty()) {
                switchingSource = false
                playbackError = baseMessage
                return
            }
            // 静默上报：能否自动换源都记一次；fire-and-forget，绝不阻塞播放、绝不弹提示。
            // 两路都发：SharedHealth 走自增计数（供排序降权），ErrorReporter 留一条带上下文的记录
            // （片名 + 源 + 版本 + 机型），20 个用户共享同一个池子，用来分辨是源的问题还是版本/机型的问题。
            val failedApi = currentSourceApi
            scope.launch { runCatching { SharedHealth.reportPlayFailure(context, failedApi, vodKey) } }
            scope.launch {
                runCatching {
                    ErrorReporter.report(
                        context,
                        kind = "play_fail",
                        sourceApi = failedApi,
                        vodKey = vodKey,
                        message = "$name · $baseMessage"
                    )
                }
            }

            // 只有一条线路时无从换起 —— 保持原有错误浮层
            if (lines.size < 2) {
                switchingSource = false
                playbackError = baseMessage
                return
            }

            val tried = autoTriedApis + failedApi
            autoTriedApis = tried

            // 从当前线路往后找第一条没试过的线路
            var nextIndex = -1
            for (offset in 1 until lines.size) {
                val idx = (lineIndex + offset) % lines.size
                if (lines[idx].api !in tried) { nextIndex = idx; break }
            }
            if (nextIndex < 0 || autoAttempts >= maxAutoFailover) {
                // 试无可试：给出诚实的错误，而不是笼统的「这条线路失效了」
                switchingSource = false
                playbackError = "已尝试 ${tried.size} 条线路都不行"
                return
            }

            autoAttempts += 1
            failoverInFlight = true
            val keepEp = currentEpisode
            val fromName = currentSourceName
            playbackError = null
            // switchingSource=true 会让下面的 resolvedUrl effect 保留当前进度，与手动换源一致
            switchingSource = true
            lineIndex = nextIndex
            currentSourceApi = lines[nextIndex].api
            currentSourceName = lines[nextIndex].name
            currentVodId = lines[nextIndex].vodId
            currentEpisode = keepEp.coerceAtMost((lines[nextIndex].episodes.size - 1).coerceAtLeast(0))
            gestureHint = "线路 $fromName 不可用，已自动切到 ${lines[nextIndex].name}"
        }

        val dao = remember { AppDb.get(this).watchDao() }
        val audio = remember { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

        val exo = remember {
            val upstream = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 15) Chrome/124.0 Mobile Safari/537.36")
                .setAllowCrossProtocolRedirects(true)
            val factory = CacheDataSource.Factory()
                .setCache(IVANApp.app.downloadCache)
                .setUpstreamDataSourceFactory(upstream)
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(30_000)
                .build()
        }
        DisposableEffect(Unit) {
            onDispose {
                // 退出播放页：落盘最后进度并强制上云（跳过节流），保证「看到哪」不丢
                val dur = exo.duration
                val pos = exo.currentPosition
                if (vodKey.isNotEmpty() && dur > 0) {
                    val entry = WatchEntry(
                        vodKey = vodKey, name = name, year = "", pic = pic,
                        sourceApi = currentSourceApi, sourceName = currentSourceName,
                        vodId = currentVodId,
                        lineIndex = lineIndex, episodeIndex = currentEpisode,
                        episodeName = "第${currentEpisode + 1}集",
                        positionMs = pos, durationMs = dur,
                        updatedAt = System.currentTimeMillis()
                    )
                    runBlocking { runCatching { dao.upsert(Account.stamp(entry)) } }
                    Account.pushWatch(context, entry, force = true)
                    // 记下「正在播放」，根页据此显示迷你播放条
                    com.ivan.cinema.data.NowPlaying.update(entry)
                }
                exo.release()
            }
        }

        // ── 构建全部线路：并发拉每个源的详情（换源的底气）──
        LaunchedEffect(vodKey) {
            if (!directUrl.isNullOrEmpty()) {
                lines = emptyList()
                resolvedUrl = directUrl
                resolving = false
                return@LaunchedEffect
            }
            // 老记录 / 云端同步过来的记录没有 vodId，用片名在该源里回查一次。
            // 拿空 id 去请求详情只会得到空结果，表现为「继续观看」永远播不了。
            if (currentVodId.isBlank()) {
                val found = withContext(Dispatchers.IO) {
                    runCatching {
                        MacCmsApi(VodSource(currentSourceName, currentSourceApi))
                            .search(name)
                            .firstOrNull { it.vodId.isNotBlank() }
                            ?.vodId
                    }.getOrNull()
                }
                if (!found.isNullOrBlank()) currentVodId = found
            }
            val specs = if (sourceSpecs.isNotEmpty()) sourceSpecs
            else listOf("$currentSourceApi|$currentSourceName|$currentVodId")
            val fetched = coroutineScope {
                specs.map { spec ->
                    async(Dispatchers.IO) {
                        val parts = spec.split("|")
                        if (parts.size < 3) return@async null
                        val api = parts[0]
                        val sname = parts[1]
                        val vid = parts[2]
                        val d = MacCmsApi(VodSource(sname, api)).detail(vid) ?: return@async null
                        val eps = d.lines.firstOrNull()?.episodes ?: emptyList()
                        if (eps.isEmpty()) null else SourceLine(api, sname, vid, eps)
                    }
                }.awaitAll().filterNotNull()
            }
            lines = fetched
            lineIndex = fetched.indexOfFirst { it.api == currentSourceApi }.takeIf { it >= 0 } ?: 0
            if (fetched.isEmpty()) {
                playbackError = "所有线路都拿不到播放地址"
                resolving = false
            }
        }

        LaunchedEffect(lines, lineIndex, currentEpisode) {
            if (!directUrl.isNullOrEmpty()) return@LaunchedEffect
            // 开始解析新线路：上一轮的自动换源已经落地，放行下一次失败处理
            failoverInFlight = false
            val line = lines.getOrNull(lineIndex)
            if (line == null) {
                // 换源后若拿不到线路，这次切换尝试已经结束，必须把按钮放出来
                switchingSource = false
                return@LaunchedEffect
            }
            // 原来这里用 `?: return@LaunchedEffect` 静默退出，resolving 永远停在 true，
            // 界面就卡在「正在解析…」。改成明确的错误态。
            val ep = line.episodes.getOrNull(currentEpisode) ?: line.episodes.lastOrNull()
            if (ep == null) {
                resolving = false
                isBuffering = false
                playbackError = "这条线路没有可播放的剧集"
                switchingSource = false
                return@LaunchedEffect
            }
            resolving = true
            val url = withContext(Dispatchers.IO) { PlayResolver.resolve(ep.url) }
            resolving = false
            // 解析失败原来会让 resolvedUrl 保持 null，于是既不报错也不播放，
            // 黑屏卡在「缓冲中…」。这里补上错误态。
            val oldUrl = resolvedUrl
            if (url.isNullOrBlank()) {
                resolvedUrl = null
                isBuffering = false
                // 解析失败也是「线路失效」：交给统一入口决定自动换源还是报错
                switchingSource = false
                handlePlaybackFailure("这条线路解析不出播放地址")
            } else {
                resolvedUrl = url
                // 新源地址与当前相同时 resolvedUrl 不变，监听它的 effect 不会重跑，
                // switchingSource 会永远停在 true，把「换源」按钮一直藏住
                if (url == oldUrl) switchingSource = false
            }
        }

        LaunchedEffect(speed) {
            exo.playbackParameters = PlaybackParameters(speed)
        }

        LaunchedEffect(fullscreen) {
            requestedOrientation = if (fullscreen) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        LaunchedEffect(resolvedUrl) {
            val url = resolvedUrl
            if (url == null) {
                // 解析失败会走到这里：本次切换尝试结束，放行「换源」按钮
                switchingSource = false
                return@LaunchedEffect
            }
            val keepPos = if (switchingSource) exo.currentPosition else 0L
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                    // 播放成功 = 本次容错序列结束，计数与去重集合清零
                    if (playing) {
                        autoAttempts = 0
                        autoTriedApis = emptySet()
                        failoverInFlight = false
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    isBuffering = state == Player.STATE_BUFFERING
                    ended = state == Player.STATE_ENDED
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    hasSubtitle = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
                    // 收集视频轨高度作为清晰度档位。多码率 HLS 会给出多组，
                    // 单码率就只有一组 —— 此时面板里只会有「自动」，这是诚实的结果。
                    val hs = LinkedHashSet<Int>()
                    var frameRate = 0f
                    tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { g ->
                        for (i in 0 until g.length) {
                            val f = g.getTrackFormat(i)
                            if (f.height > 0) hs.add(f.height)
                            if (frameRate <= 0f && f.frameRate > 0f) frameRate = f.frameRate
                        }
                    }
                    val sorted = hs.sortedDescending()
                    if (sorted != qualityHeights) qualityHeights = sorted

                    // 默认清晰度：选了「最高」就用当前轨里的最高档。
                    // 采集源绝大多数是单码率，这里多半无事发生 —— 但只要源给了多档，
                    // 用户就不必每次手动去面板里挑。
                    if (preferHighest && sorted.isNotEmpty()) {
                        val top = sorted.first()
                        if (maxHeight != top) {
                            maxHeight = top
                            exo.trackSelectionParameters = exo.trackSelectionParameters
                                .buildUpon()
                                .setMaxVideoSize(Int.MAX_VALUE, top)
                                .build()
                        }
                    } else if (maxHeight != 0 && sorted.isNotEmpty() && sorted.none { it <= maxHeight }) {
                        // 换集/换源后旧的高度上限可能已经不存在，回落自动，避免"选了 1080 却是空"
                        maxHeight = 0
                    }

                    // 按内容帧率挑刷新率档：24fps 在 60Hz 上是 3:2 下拉（必然抖），
                    // 在 120Hz 上是 5:5（均匀）。零成本、真收益，所以不需要开关。
                    if (frameRate > 1f) {
                        runCatching { com.ivan.cinema.ui.runtime.RefreshRate.applyForContent(this@PlayerActivity, frameRate) }
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    resolving = false
                    isBuffering = false
                    // 统一走容错入口：静默上报 + 自动换下一条线路，试无可试才报错
                    handlePlaybackFailure("这条线路失效了")
                }
            })
            exo.prepare()
            if (switchingSource && keepPos > 10_000) {
                exo.seekTo(keepPos)
            }
            exo.playWhenReady = true
            switchingSource = false
        }

        LaunchedEffect(lines, resolvedUrl) {
            if (resolvedUrl == null || !resume) return@LaunchedEffect
            val e = withContext(Dispatchers.IO) { dao.get(vodKey) } ?: return@LaunchedEffect
            if (e.positionMs > 15_000) {
                var waited = 0
                while (exo.duration <= 0 && waited < 5000) {
                    delay(200); waited += 200
                }
                if (exo.duration > 0 && e.positionMs < exo.duration - 10_000) {
                    exo.seekTo(e.positionMs)
                }
            }
        }

        LaunchedEffect(resolvedUrl) {
            if (resolvedUrl == null) return@LaunchedEffect
            while (isActive) {
                delay(500)
                if (!dragging) {
                    positionMs = exo.currentPosition
                    durationMs = exo.duration.coerceAtLeast(0)
                }
                if (exo.currentPosition % 5000 < 600 && exo.currentPosition > 0) {
                    saveProgress(
                        dao, vodKey, name, pic,
                        currentSourceApi, currentSourceName, currentVodId,
                        lineIndex, currentEpisode, exo
                    )
                }
            }
        }

        // 任何交互都重置隐藏计时：拖进度条 / 亮度音量手势中不允许控制层消失
        LaunchedEffect(
            controlsVisible, isPlaying, showEpisodes, showSpeedMenu, showSubtitleMenu,
            dragging, gestureHint
        ) {
            if (controlsVisible && isPlaying && !showEpisodes && !showSpeedMenu &&
                !showSubtitleMenu && !dragging && gestureHint == null
            ) {
                delay(4000)
                controlsVisible = false
            }
        }

        // 手势提示：快进/快退、亮度、音量 1400ms；换源确认 / 自动容错 2000ms（要读得完）
        var hintText by remember { mutableStateOf("") }
        LaunchedEffect(gestureHint) {
            val hint = gestureHint ?: return@LaunchedEffect
            hintText = hint
            val longHint = hint.startsWith("已切换线路") || hint.contains("已自动切到")
            delay(if (longHint) 2000 else 1400)
            gestureHint = null
        }

        val currentLine = lines.getOrNull(lineIndex)
        val episodes = currentLine?.episodes ?: emptyList()
        val hasNext = directUrl.isNullOrEmpty() && currentEpisode + 1 < episodes.size

        fun playEpisode(idx: Int) {
            if (idx in episodes.indices && idx != currentEpisode) {
                playbackError = null
                // 手动选集 = 新的容错序列，计数与去重集合清零
                autoAttempts = 0
                autoTriedApis = emptySet()
                failoverInFlight = false
                currentEpisode = idx
            }
        }

        /** 换源：切下一个源，保持当前集与进度。 */
        fun switchSource() {
            if (lines.size < 2) return
            val keepEp = currentEpisode
            playbackError = null
            // 手动换源 = 新的容错序列，计数与去重集合清零
            autoAttempts = 0
            autoTriedApis = emptySet()
            failoverInFlight = false
            switchingSource = true
            lineIndex = (lineIndex + 1) % lines.size
            // 记录真正生效的源：保存进度时要用，否则切过源后存的还是进来那一条
            currentSourceApi = lines[lineIndex].api
            currentSourceName = lines[lineIndex].name
            currentVodId = lines[lineIndex].vodId
            // 新源集数可能更少：夹到最后一集
            currentEpisode = keepEp.coerceAtMost((lines[lineIndex].episodes.size - 1).coerceAtLeast(0))
            gestureHint = "已切换线路：${lines[lineIndex].name}"
        }

        /**
         * 直接选中第 [index] 条线路（下拉里点某一条源）。
         * 与 [switchSource] 的区别只是「跳着选」而不是「轮下一个」，
         * 容错计数清零、集数夹取、源记录三件事必须完全一致 —— 所以共用一段实现。
         */
        fun selectSource(index: Int) {
            if (index !in lines.indices || index == lineIndex) return
            val keepEp = currentEpisode
            playbackError = null
            autoAttempts = 0
            autoTriedApis = emptySet()
            failoverInFlight = false
            switchingSource = true
            lineIndex = index
            currentSourceApi = lines[index].api
            currentSourceName = lines[index].name
            currentVodId = lines[index].vodId
            currentEpisode = keepEp.coerceAtMost((lines[index].episodes.size - 1).coerceAtLeast(0))
            gestureHint = "已切换线路：${lines[index].name}"
        }

        /** 应用清晰度上限。height = 0 表示自动（不限制）。 */
        fun applyQuality(height: Int) {
            maxHeight = height
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, if (height == 0) Int.MAX_VALUE else height)
                .build()
            gestureHint = if (height == 0) "清晰度：自动" else "清晰度：${height}P"
        }

        /**
         * 开关画质增强。
         *
         * 视频后处理要求解码器输出到 GL，部分设备/编码格式走不通，硬开可能直接黑屏。
         * 所以这里全程 runCatching：抛异常就回滚开关状态并明确告诉用户"这台设备不支持"，
         * 而不是留一个开着但没生效的假开关。
         */
        fun applyEnhance(on: Boolean) {
            runCatching {
                exo.setVideoEffects(if (on) listOf(SharpenEffect()) else emptyList())
            }.onFailure {
                enhance = false
                PlayPrefs.setEnhance(context, false)
                gestureHint = "这台设备不支持画质增强"
            }.onSuccess {
                gestureHint = if (on) "画质增强：开" else "画质增强：关"
            }
        }

        // 上次开着就恢复。放在这里而不是更早：applyEnhance 是局部函数，
        // 只能在声明之后调用。
        LaunchedEffect(Unit) {
            if (enhance) applyEnhance(true)
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                // 手势无可见控件：给读屏暴露等价操作，否则这些功能对无障碍用户完全不可用
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(if (isPlaying) "暂停" else "播放") {
                            if (exo.isPlaying) exo.pause() else exo.play()
                            gestureHint = if (exo.isPlaying) "播放" else "暂停"
                            true
                        },
                        CustomAccessibilityAction("快进 30 秒") {
                            exo.seekForward()
                            true
                        },
                        CustomAccessibilityAction("快退 10 秒") {
                            exo.seekBack()
                            true
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = {
                            if (exo.isPlaying) exo.pause() else exo.play()
                            gestureHint = if (exo.isPlaying) "播放" else "暂停"
                        }
                    )
                }
                .pointerInput(resolvedUrl) {
                    var start = 0L
                    var acc = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { start = exo.currentPosition; acc = 0f },
                        onHorizontalDrag = { _, delta ->
                            acc += delta
                            val target = (start + (acc * 400).toLong()).coerceIn(0L, durationMs.coerceAtLeast(0))
                            gestureHint = "快进/快退  ${fmtTime(target)}"
                        },
                        onDragEnd = {
                            if (abs(acc) > 12f) {
                                exo.seekTo((start + (acc * 400).toLong()).coerceIn(0L, durationMs.coerceAtLeast(0)))
                            }
                            acc = 0f
                        }
                    )
                }
                .pointerInput(resolvedUrl) {
                    var leftSide = true
                    detectVerticalDragGestures(
                        onDragStart = { offset -> leftSide = offset.x < size.width / 2f },
                        onVerticalDrag = { _, delta ->
                            if (leftSide) {
                                val lp = window.attributes
                                val cur = lp.screenBrightness.takeIf { it >= 0f } ?: 0.5f
                                val next = (cur - delta / size.height).coerceIn(0.05f, 1f)
                                lp.screenBrightness = next
                                window.attributes = lp
                                gestureHint = "亮度 ${(next * 100).toInt()}%"
                            } else {
                                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                                val next = (cur + (-delta / size.height * max).toInt()).coerceIn(0, max)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                                gestureHint = "音量 ${(next * 100 / max)}%"
                            }
                        }
                    )
                }
        ) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        useController = false
                        player = exo
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (resolving || isBuffering) {
                Text(
                    if (resolving) "正在解析…" else "缓冲中…",
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        // 往下让开中间的播放按钮：原来两者都在正中心，字和图标叠在一起都糊了
                        .offset(y = 88.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = Space.md, vertical = Space.xs + 2.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }

            playbackError?.let { err ->
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(Radius.md))
                        .background(Color.Black.copy(alpha = 0.78f))
                        .padding(horizontal = Space.xl, vertical = Space.lg),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(err, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = Color.White)
                    Spacer(Modifier.height(Space.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                        // 换源是脏源的唯一出路：只要有别的线路就必须给出来
                        if (lines.size > 1) {
                            Text(
                                "换一条线路（${lines.size} 条可选）",
                                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.pill))
                                    .background(Color.White.copy(alpha = 0.18f))
                                    .clickable { switchSource() }
                                    .padding(horizontal = Space.lg, vertical = Space.sm)
                            )
                        }
                        Text(
                            "重试",
                            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                            color = Color.Black,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.pill))
                                .background(pal.accent)
                                .clickable {
                                    playbackError = null
                                    exo.prepare()
                                    exo.play()
                                }
                                .padding(horizontal = Space.lg, vertical = Space.sm)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = gestureHint != null,
                enter = fadeIn(motionFade(120)),
                exit = fadeOut(motionExit(100)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = Space.lg, vertical = Space.sm)
                ) {
                    Text(
                        gestureHint ?: hintText,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }

            // ── 腾讯视频式控制层：无面板、无圆角卡，只有黑渐变遮罩 ──
            // 错误态时不显示（否则中央播放键会压住「重试」按钮）
            AnimatedVisibility(
                visible = controlsVisible && playbackError == null,
                enter = fadeIn(motionFade(180)),
                exit = fadeOut(motionExit(150)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(Modifier.fillMaxSize()) {
                    // 顶部渐变：返回 + 标题 + 当前线路（点它换源）
                    Row(
                        Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)
                                )
                            )
                            .statusBarsPadding()
                            .padding(horizontal = Space.md, vertical = Space.sm + 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                            modifier = Modifier
                                // 24dp 的点击目标太小，撑到 48dp（图标仍画 24dp）
                                .size(48.dp)
                                .clickable { onExit() }
                                .padding(12.dp)
                        )
                        Spacer(Modifier.width(Space.md))
                        Text(
                            "$name · 第${currentEpisode + 1}集",
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        // 切换源：只留一个图标。原来是一枚「暴风资源 ⇄」文字胶囊，
                        // 源名长短不一，长名字会把标题挤没；点开才是源列表。
                        if (lines.size > 1 && !switchingSource) {
                            Icon(
                                Icons.Rounded.SwapHoriz,
                                contentDescription = "切换播放源",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .clickable { showSources = true }
                                    .padding(12.dp)
                            )
                        }
                    }

                    // 中央播放/暂停大按钮（图标 140ms 交叉淡入淡出，不硬切）
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f))
                            .clickable { if (exo.isPlaying) exo.pause() else exo.play() },
                        contentAlignment = Alignment.Center
                    ) {
                        MotionIconSwap(
                            icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = Color.White,
                            size = 36.dp
                        )
                    }

                    // 底部渐变：细进度条 + 时间 + 右侧功能按钮
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                                )
                            )
                            .padding(horizontal = Space.lg, vertical = Space.md)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fmtTime(positionMs), style = MetaMono, color = Color.White)
                            Slider(
                                value = if (durationMs > 0)
                                    (if (dragging) dragPos else positionMs).toFloat() / durationMs else 0f,
                                onValueChange = {
                                    dragging = true
                                    dragPos = (it * durationMs).toLong()
                                },
                                onValueChangeFinished = {
                                    exo.seekTo(dragPos)
                                    dragging = false
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = pal.accent,
                                    activeTrackColor = pal.accent,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.28f)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = Space.sm)
                                    .height(48.dp)
                                    // 默认读屏只念百分比；补上时间与含义
                                    .semantics {
                                        contentDescription = "播放进度"
                                        stateDescription = fmtTime(if (dragging) dragPos else positionMs)
                                    }
                            )
                            Text(fmtTime(durationMs), style = MetaMono, color = Color.White)
                        }

                        Spacer(Modifier.height(Space.xs))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (hasNext) {
                                PlayBarButton("下一集") { playEpisode(currentEpisode + 1) }
                            }
                            Spacer(Modifier.weight(1f))
                            PlayBarButton("${speed}x") { showSpeedMenu = true }
                            Spacer(Modifier.width(Space.lg))
                            // 清晰度入口常驻：即使源只有单码率，也要让用户看得到"当前是自动"，
                            // 而不是点了发现按钮不存在、以为功能没做
                            PlayBarButton(if (maxHeight == 0) "清晰度" else "${maxHeight}P") {
                                showQuality = true
                            }
                            // 没有字幕轨时不显示入口：点了只会开出一个死胡同面板
                            if (hasSubtitle) {
                                Spacer(Modifier.width(Space.lg))
                                PlayBarButton("字幕") { showSubtitleMenu = true }
                            }
                            Spacer(Modifier.width(Space.lg))
                            PlayBarButton("选集") { showEpisodes = true }
                            Spacer(Modifier.width(Space.lg))
                            Icon(
                                if (fullscreen) Icons.Rounded.FitScreen else Icons.Rounded.Fullscreen,
                                contentDescription = if (fullscreen) "退出全屏" else "全屏",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable { fullscreen = !fullscreen }
                                    .padding(11.dp)
                            )
                        }
                    }
                }
            }

            if (ended && playbackError == null) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(Radius.md))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .clickable {
                            if (hasNext) playEpisode(currentEpisode + 1)
                            else {
                                ended = false
                                exo.seekTo(0)
                                exo.play()
                            }
                        }
                        .padding(horizontal = Space.xl, vertical = Space.md)
                ) {
                    Text(
                        if (hasNext) "▶ 下一集" else "▶ 重新播放",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
            }

            // ── 切换源面板：列出所有线路，按可信度排，最好的标「推荐」 ──
            LaunchedEffect(showSources) {
                if (showSources) {
                    rankedApis = runCatching {
                        SharedHealth.rankLines(context, lines.map { it.api })
                    }.getOrNull()
                }
            }
            if (showSources) {
                // 推荐顺序 = SharedHealth 的可信度排序（坏源降权在后）；拿不到就按原顺序，
                // 并把第一条当推荐 —— 至少保证"推荐"这个位置永远有东西
                val order = rankedApis ?: lines.map { it.api }
                val ordered = order.mapNotNull { api -> lines.indexOfFirst { it.api == api }
                    .takeIf { it >= 0 }?.let { it to lines[it] } }
                    .let { if (it.isEmpty()) lines.mapIndexed { i, l -> i to l } else it }
                MenuSheet(onDismiss = { showSources = false }) {
                    Text(
                        "选择播放源",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = pal.ink
                    )
                    Spacer(Modifier.height(Space.sm + 2.dp))
                    Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                        ordered.forEachIndexed { rank, (idx, line) ->
                            val on = idx == lineIndex
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Radius.sm + 2.dp))
                                    .background(if (on) pal.accent.copy(alpha = 0.16f) else Color.Transparent)
                                    .clickable {
                                        showSources = false
                                        selectSource(idx)
                                    }
                                    .padding(horizontal = Space.md, vertical = 15.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    line.name,
                                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                                    color = if (on) pal.accent else pal.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (rank == 0 && !on) {
                                    Text(
                                        "推荐",
                                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                        color = pal.accentInk,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(Radius.pill))
                                            .background(pal.accent)
                                            .padding(horizontal = Space.sm, vertical = 2.dp)
                                    )
                                    Spacer(Modifier.width(Space.sm))
                                }
                                Text(
                                    if (on) "当前" else "${line.episodes.size} 集",
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                    color = pal.inkMuted
                                )
                            }
                        }
                    }
                }
            }

            // ── 清晰度面板：档位来自播放器实际拿到的视频轨 ──
            if (showQuality) {
                MenuSheet(onDismiss = { showQuality = false }) {
                    Text(
                        "清晰度",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = pal.ink
                    )
                    Spacer(Modifier.height(Space.sm + 2.dp))
                    if (qualityHeights.isEmpty()) {
                        Text(
                            "这条线路只有一个码率，没有可切换的档位",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            color = pal.inkMuted
                        )
                    } else {
                        Column(Modifier.height(280.dp).verticalScroll(rememberScrollState())) {
                            // 「自动」交给播放器按带宽自适应；「最高」永远用当前轨里的最高档。
                            // 两者都是"策略"，会记住；下面那些具体档位是一次性选择。
                            QualityRow("自动", selected = !preferHighest && maxHeight == 0) {
                                preferHighest = false
                                maxHeight = 0
                                PlayPrefs.setPreferHighest(context, false)
                                exo.trackSelectionParameters = exo.trackSelectionParameters
                                    .buildUpon().setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE).build()
                            }
                            QualityRow(
                                "最高（${qualityHeights.first()}P）",
                                selected = preferHighest
                            ) {
                                preferHighest = true
                                PlayPrefs.setPreferHighest(context, true)
                                applyQuality(qualityHeights.first())
                            }
                            qualityHeights.forEach { h ->
                                QualityRow("${h}P", selected = !preferHighest && maxHeight == h) {
                                    preferHighest = false
                                    PlayPrefs.setPreferHighest(context, false)
                                    applyQuality(h)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(Space.md))
                    Text(
                        "画质增强",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = pal.ink
                    )
                    Spacer(Modifier.height(Space.xs))
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            enhance = !enhance
                            PlayPrefs.setEnhance(context, enhance)
                            applyEnhance(enhance)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "边缘锐化超分",
                                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                                color = pal.ink
                            )
                            Text(
                                // 说清楚它做不到什么，免得被当成"变成真 4K"
                                "锐化边缘、提升观感，不是把片源变成真 4K；中端机可能掉帧",
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                color = pal.inkMuted
                            )
                        }
                        Text(
                            if (enhance) "已开启" else "已关闭",
                            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                            color = if (enhance) pal.accent else pal.inkMuted
                        )
                    }
                }
            }

            if (showSpeedMenu) {
                MenuSheet(onDismiss = { showSpeedMenu = false }) {
                    Text("倍速", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = pal.ink)
                    Spacer(Modifier.height(Space.sm + 2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { s ->
                            val on = s == speed
                            Text(
                                "${s}x",
                                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                                color = if (on) pal.accentInk else pal.ink,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.sm + 2.dp))
                                    .background(if (on) pal.accent else pal.surfaceRaised)
                                    .clickable {
                                        speed = s
                                        PlayPrefs.setSpeed(context, s)
                                        showSpeedMenu = false
                                    }
                                    .padding(horizontal = Space.md, vertical = Space.sm)
                            )
                        }
                    }
                }
            }

            if (showSubtitleMenu) {
                MenuSheet(onDismiss = { showSubtitleMenu = false }) {
                    Text("字幕轨", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = pal.ink)
                    Spacer(Modifier.height(Space.sm + 2.dp))
                    Text(
                        if (hasSubtitle) "点击关闭或开启内嵌字幕" else "当前线路没有内嵌字幕轨",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = pal.inkMuted
                    )
                    if (hasSubtitle) {
                        Spacer(Modifier.height(Space.sm + 2.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            listOf("关闭" to true, "开启" to false).forEach { (label, disable) ->
                                Text(
                                    label,
                                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                                    color = pal.ink,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Radius.sm + 2.dp))
                                        .background(pal.surfaceRaised)
                                        .clickable {
                                            exo.trackSelectionParameters = exo.trackSelectionParameters
                                                .buildUpon()
                                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disable)
                                                .build()
                                            showSubtitleMenu = false
                                        }
                                        .padding(horizontal = Space.md, vertical = 15.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (showEpisodes) {
                Dialog(onDismissRequest = { showEpisodes = false }) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.xl))
                            .background(pal.surface)
                            .padding(Space.md + 4.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "选集",
                                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                    color = pal.ink,
                                    modifier = Modifier.weight(1f)
                                )
                                if (lines.size > 1) {
                                    Text(
                                        "当前：${currentLine?.name ?: ""}",
                                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                        color = pal.inkMuted
                                    )
                                }
                            }
                            Spacer(Modifier.height(Space.sm + 2.dp))
                            Column(
                                Modifier
                                    .height(360.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                episodes.chunked(4).forEachIndexed { rowIdx, rowEps ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = Space.xs),
                                        horizontalArrangement = Arrangement.spacedBy(Space.sm)
                                    ) {
                                        rowEps.forEachIndexed { col, ep ->
                                            val idx = rowIdx * 4 + col
                                            val on = idx == currentEpisode
                                            Text(
                                                ep.name,
                                                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                                                color = if (on) pal.accentInk else pal.ink,
                                                maxLines = 1,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(Radius.sm))
                                                    .background(if (on) pal.accent else pal.surfaceRaised)
                                                    .clickable {
                                                        showEpisodes = false
                                                        playEpisode(idx)
                                                    }
                                                    .padding(vertical = 15.dp),
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                        repeat(4 - rowEps.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun saveProgress(
        dao: com.ivan.cinema.db.WatchDao,
        vodKey: String, name: String, pic: String,
        sourceApi: String, sourceName: String, vodId: String,
        lineIndex: Int, episodeIndex: Int, exo: ExoPlayer
    ) {
        if (vodKey.isEmpty() || exo.duration <= 0) return
        val entry = WatchEntry(
            vodKey = vodKey, name = name, year = "", pic = pic,
            sourceApi = sourceApi, sourceName = sourceName, vodId = vodId,
            lineIndex = lineIndex, episodeIndex = episodeIndex,
            episodeName = "第${episodeIndex + 1}集",
            positionMs = exo.currentPosition, durationMs = exo.duration,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsert(Account.stamp(entry))
        com.ivan.cinema.data.NowPlaying.update(entry)
        // 已登录云端时按 60s 节流上传进度；退出播放页会强制推最后一条
        Account.pushWatch(this, entry)
    }

    private fun fmtTime(ms: Long): String {
        if (ms <= 0) return "00:00"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%02d:%02d", m, sec)
    }
}

/** 腾讯视频式文字按钮：无面板，只有白字 + 按压反馈。文案变化时 150ms 交叉淡化。 */
@Composable
private fun PlayBarButton(label: String, onClick: () -> Unit) {    MotionTextSwap(
        text = label,
        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 15.dp)
    )
}

/** 清晰度面板里的一行：整行可点，选中态用金色底 + 右侧「当前」。 */
@Composable
private fun QualityRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val pal = LocalIVAN.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm + 2.dp))
            .background(if (selected) pal.accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = Space.md, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            color = if (selected) pal.accent else pal.ink,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Text(
                "当前",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = pal.inkMuted
            )
        }
    }
}

@Composable
fun MenuSheet(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val pal = LocalIVAN.current
    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.xl))
                .background(pal.surface)
                .padding(Space.md + 4.dp)
        ) {
            Column(content = content)
        }
    }
}
