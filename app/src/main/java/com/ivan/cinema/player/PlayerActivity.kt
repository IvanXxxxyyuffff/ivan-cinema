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
import com.ivan.cinema.data.MacCmsApi
import com.ivan.cinema.data.VodSource
import com.ivan.cinema.db.AppDb
import com.ivan.cinema.db.WatchEntry
import com.ivan.cinema.ui.theme.IVANTheme
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.MetaMono
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

object PlayPrefs {
    private const val KEY_SPEED = "speed"
    fun speed(ctx: Context): Float =
        ctx.getSharedPreferences("ivan_play", Context.MODE_PRIVATE).getFloat(KEY_SPEED, 1f)
    fun setSpeed(ctx: Context, v: Float) {
        ctx.getSharedPreferences("ivan_play", Context.MODE_PRIVATE).edit().putFloat(KEY_SPEED, v).apply()
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
        var fullscreen by remember { mutableStateOf(false) }
        var gestureHint by remember { mutableStateOf<String?>(null) }
        var playbackError by remember { mutableStateOf<String?>(null) }
        var switchingSource by remember { mutableStateOf(false) }

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
        DisposableEffect(Unit) { onDispose { exo.release() } }

        // ── 构建全部线路：并发拉每个源的详情（换源的底气）──
        LaunchedEffect(vodKey) {
            if (!directUrl.isNullOrEmpty()) {
                lines = emptyList()
                resolvedUrl = directUrl
                resolving = false
                return@LaunchedEffect
            }
            val specs = if (sourceSpecs.isNotEmpty()) sourceSpecs
            else listOf("$sourceApi|$sourceName|$vodId")
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
            lineIndex = fetched.indexOfFirst { it.api == sourceApi }.takeIf { it >= 0 } ?: 0
            if (fetched.isEmpty()) {
                playbackError = "所有线路都拿不到播放地址"
                resolving = false
            }
        }

        LaunchedEffect(lines, lineIndex, currentEpisode) {
            if (!directUrl.isNullOrEmpty()) return@LaunchedEffect
            val line = lines.getOrNull(lineIndex) ?: return@LaunchedEffect
            val ep = line.episodes.getOrNull(currentEpisode)
                ?: line.episodes.lastOrNull()
                ?: return@LaunchedEffect
            resolving = true
            resolvedUrl = withContext(Dispatchers.IO) { PlayResolver.resolve(ep.url) }
            resolving = false
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
            val url = resolvedUrl ?: return@LaunchedEffect
            val keepPos = if (switchingSource) exo.currentPosition else 0L
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlaybackStateChanged(state: Int) {
                    isBuffering = state == Player.STATE_BUFFERING
                    ended = state == Player.STATE_ENDED
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    hasSubtitle = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    resolving = false
                    isBuffering = false
                    playbackError = "这条线路失效了"
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
                    saveProgress(dao, vodKey, name, pic, sourceApi, sourceName, lineIndex, currentEpisode, exo)
                }
            }
        }

        LaunchedEffect(controlsVisible, isPlaying, showEpisodes, showSpeedMenu, showSubtitleMenu) {
            if (controlsVisible && isPlaying && !showEpisodes && !showSpeedMenu && !showSubtitleMenu) {
                delay(4000)
                controlsVisible = false
            }
        }

        LaunchedEffect(gestureHint) {
            if (gestureHint != null) {
                delay(900)
                gestureHint = null
            }
        }

        val currentLine = lines.getOrNull(lineIndex)
        val episodes = currentLine?.episodes ?: emptyList()
        val hasNext = directUrl.isNullOrEmpty() && currentEpisode + 1 < episodes.size

        fun playEpisode(idx: Int) {
            if (idx in episodes.indices && idx != currentEpisode) {
                playbackError = null
                currentEpisode = idx
            }
        }

        /** 换源：切下一个源，保持当前集与进度。 */
        fun switchSource() {
            if (lines.size < 2) return
            val keepEp = currentEpisode
            playbackError = null
            switchingSource = true
            lineIndex = (lineIndex + 1) % lines.size
            // 新源集数可能更少：夹到最后一集
            currentEpisode = keepEp.coerceAtMost((lines[lineIndex].episodes.size - 1).coerceAtLeast(0))
            gestureHint = "已切换线路：${lines[lineIndex].name}"
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
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
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = Space.md, vertical = Space.xs + 2.dp)
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

            gestureHint?.let { hint ->
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = Space.lg, vertical = Space.sm)
                ) {
                    Text(hint, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = Color.White)
                }
            }

            // ── 腾讯视频式控制层：无面板、无圆角卡，只有黑渐变遮罩 ──
            // 错误态时不显示（否则中央播放键会压住「重试」按钮）
            AnimatedVisibility(
                visible = controlsVisible && playbackError == null,
                enter = fadeIn(androidx.compose.animation.core.tween(180)),
                exit = fadeOut(androidx.compose.animation.core.tween(150)),
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
                                .size(24.dp)
                                .clickable { onExit() }
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
                        if (lines.size > 1 && !switchingSource) {
                            Text(
                                "${currentLine?.name ?: ""} ⇄",
                                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                                color = Color.White.copy(alpha = 0.92f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.pill))
                                    .background(Color.White.copy(alpha = 0.16f))
                                    .clickable { switchSource() }
                                    .padding(horizontal = Space.md, vertical = Space.xs + 2.dp)
                            )
                        }
                    }

                    // 中央播放/暂停大按钮
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f))
                            .clickable { if (exo.isPlaying) exo.pause() else exo.play() }
                            .padding(18.dp)
                    )

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
                                    .height(28.dp)
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
                            PlayBarButton(if (hasSubtitle) "字幕" else "无字幕") { showSubtitleMenu = true }
                            Spacer(Modifier.width(Space.lg))
                            PlayBarButton("选集") { showEpisodes = true }
                            Spacer(Modifier.width(Space.lg))
                            Icon(
                                if (fullscreen) Icons.Rounded.FitScreen else Icons.Rounded.Fullscreen,
                                contentDescription = if (fullscreen) "退出全屏" else "全屏",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(26.dp)
                                    .clickable { fullscreen = !fullscreen }
                            )
                        }
                    }
                }
            }

            if (ended && !controlsVisible) {
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
                                        .padding(horizontal = Space.md, vertical = Space.sm)
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
                                                    .padding(vertical = Space.sm + 2.dp),
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
        sourceApi: String, sourceName: String,
        lineIndex: Int, episodeIndex: Int, exo: ExoPlayer
    ) {
        if (vodKey.isEmpty() || exo.duration <= 0) return
        dao.upsert(
            WatchEntry(
                vodKey = vodKey, name = name, year = "", pic = pic,
                sourceApi = sourceApi, sourceName = sourceName,
                lineIndex = lineIndex, episodeIndex = episodeIndex,
                episodeName = "第${episodeIndex + 1}集",
                positionMs = exo.currentPosition, durationMs = exo.duration,
                updatedAt = System.currentTimeMillis()
            )
        )
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

/** 腾讯视频式文字按钮：无面板，只有白字 + 按压反馈。 */
@Composable
private fun PlayBarButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .clickable { onClick() }
            .padding(horizontal = Space.sm, vertical = Space.xs)
    )
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
