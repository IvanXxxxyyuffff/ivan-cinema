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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ivan.cinema.BuildConfig
import com.ivan.cinema.IVANApp
import com.ivan.cinema.data.ApkState
import com.ivan.cinema.data.ApkUpdater
import com.ivan.cinema.data.UpdateChecker
import com.ivan.cinema.data.UpdateInfo
import com.ivan.cinema.ui.components.LiquidCard
import com.ivan.cinema.ui.components.press
import com.ivan.cinema.ui.components.pressDip
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.MotionPrefs
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 设置页 —— 只留「一个月才开一次」的开关：检查更新 / 减少动效 / 封面缓存 / 搜索历史 / 版本。
 *
 * 身份位与「我的追剧」已迁出：前者是每天可见的「我的」，后者是每天打开的理由「追剧」，
 * 都不该埋在这一层。本页现在是从「我的」推入的**二级页**，所以自带返回按钮。
 */
@Composable
fun SettingsScreen(contentBottomPadding: Dp = 0.dp, onBack: () -> Unit = {}) {
    val pal = LocalIVAN.current
    val scope = rememberCoroutineScope()
    val ctx = IVANApp.ctx()
    val update = UpdateChecker.latest.value

    var reduce by remember { mutableStateOf(MotionPrefs.reduce) }
    var cacheCleared by remember { mutableStateOf(false) }
    var historyCleared by remember { mutableStateOf(false) }
    // 检查更新是异步无回调的：这里承接「已是最新 / 检查失败」的瞬时反馈
    var updateNote by remember { mutableStateOf<String?>(null) }
    var installArmed by remember { mutableStateOf(false) }
    val apkState = ApkUpdater.state.value

    // 从「安装未知来源应用」设置页返回后自动继续安装（只自动触发一次）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && installArmed) {
                installArmed = false
                val s = ApkUpdater.state.value
                if (s is ApkState.Ready && ApkUpdater.canInstall(ctx)) {
                    ApkUpdater.install(ctx, s.file)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = Space.lg, end = Space.lg, top = 0.dp, bottom = contentBottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(Space.md),
        modifier = Modifier
            .fillMaxSize()
            // 推入页必须自带不透明底，否则下层的「我的」页会透上来（实拍确认过）
            .opaqueScreenBackground()
            .statusBarsPadding()
    ) {
        // ── 标题 + 返回（二级页必须有可见返回入口，和详情页同款胶囊）──
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = Space.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        // 与详情页/片单页返回胶囊同款间距（它们都用 padding(end = Space.sm)）
                        .padding(end = Space.sm)
                        .size(48.dp)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(Radius.pill))
                        .clickable { onBack() },
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
                    "设置",
                    style = MaterialTheme.typography.headlineSmall,
                    color = pal.ink
                )
            }
        }

        // ── 检查更新（应用内下载 + 安装，全程不跳浏览器）──
        item {
            LiquidCard(Modifier.fillMaxWidth(), radius = Radius.lg) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(Space.md + 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "检查更新",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = pal.ink
                                )
                                if (update != null) {
                                    Spacer(Modifier.width(Space.sm))
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(Radius.sm))
                                            // 与海报角标同一套令牌，全 App 徽章观感一致
                                            .background(pal.badge)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            "新版本",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = pal.badgeInk
                                        )
                                    }
                                }
                            }
                            Text(
                                // 检查完成的瞬时反馈优先于常态副标题
                                updateNote ?: updateSubtitle(update, apkState, BuildConfig.VERSION_NAME),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (apkState is ApkState.Failed || updateNote?.startsWith("检查失败") == true)
                                    pal.danger else pal.inkMuted
                            )
                        }
                        val checkAction = remember { MutableInteractionSource() }
                        Text(
                            updateActionLabel(update, apkState),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (update != null) pal.accentInk else pal.ink,
                            modifier = Modifier
                                .pressDip(checkAction, to = press.control)
                                .clip(RoundedCornerShape(Radius.pill))
                                .background(if (update != null) pal.accent else Color.Transparent)
                                .then(
                                    if (update == null) {
                                        Modifier.border(1.dp, pal.hairline, RoundedCornerShape(Radius.pill))
                                    } else Modifier
                                )
                                .clickable(interactionSource = checkAction, indication = null) {
                                    when (val s = ApkUpdater.state.value) {
                                        is ApkState.Downloading -> ApkUpdater.cancel()
                                        is ApkState.Ready -> {
                                            if (ApkUpdater.canInstall(ctx)) {
                                                ApkUpdater.install(ctx, s.file)
                                            } else {
                                                installArmed = true
                                                ApkUpdater.requestInstallPermission(ctx)
                                            }
                                        }
                                        else -> {
                                            val u = UpdateChecker.latest.value
                                            if (u != null) {
                                                ApkUpdater.download(ctx, u.url, u.versionName)
                                            } else {
                                                // UpdateChecker.check 无回调：这里等镜像返回，
                                                // 再给「已是最新 / 检查失败」的可见反馈，不再点了没反应
                                                updateNote = "正在检查…"
                                                scope.launch {
                                                    UpdateChecker.check(ctx, BuildConfig.VERSION_CODE)
                                                    // 镜像超时 6s，最多等 7.5s
                                                    var waited = 0
                                                    while (waited < 7500 && UpdateChecker.latest.value == null) {
                                                        delay(300)
                                                        waited += 300
                                                    }
                                                    updateNote = when {
                                                        UpdateChecker.latest.value != null -> null
                                                        !hasNetwork(ctx) -> "检查失败，稍后再试"
                                                        else -> "已是最新版本"
                                                    }
                                                    delay(4000)
                                                    updateNote = null
                                                }
                                            }
                                        }
                                    }
                                }
                                .pillHit()
                                .padding(horizontal = 20.dp)
                        )
                    }

                    if (apkState is ApkState.Downloading) {
                        Spacer(Modifier.height(Space.sm))
                        LinearProgressIndicator(
                            progress = { apkState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(Radius.pill)),
                            color = pal.accent,
                            trackColor = Color.White.copy(alpha = 0.14f)
                        )
                    }
                }
            }
        }

        // ── 动效 ──
        item {
            LiquidCard(Modifier.fillMaxWidth(), radius = Radius.lg) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(Space.md + 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("减少动效", style = MaterialTheme.typography.titleMedium, color = pal.ink)
                        Text(
                            if (MotionPrefs.reduce) "已开启" else "去掉弹簧过冲与级联延迟",
                            style = MaterialTheme.typography.labelSmall,
                            color = pal.inkMutedOnGlass
                        )
                    }
                    Switch(
                        checked = reduce,
                        onCheckedChange = {
                            reduce = it
                            MotionPrefs.setUser(ctx, it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = pal.accentInk,
                            checkedTrackColor = pal.accent,
                            uncheckedThumbColor = pal.inkMuted,
                            uncheckedTrackColor = pal.surfaceRaised
                        )
                    )
                }
            }
        }

        // ── 缓存 ──
        item {
            LiquidCard(Modifier.fillMaxWidth(), radius = Radius.lg) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(Space.md + 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("封面缓存", style = MaterialTheme.typography.titleMedium, color = pal.ink)
                        Text(
                            if (cacheCleared) "已清理" else "封面与图片的本地缓存",
                            style = MaterialTheme.typography.labelSmall,
                            color = pal.inkMutedOnGlass
                        )
                    }
                    val clearInteraction = remember { MutableInteractionSource() }
                    Text(
                        "清理",
                        style = MaterialTheme.typography.labelLarge,
                        color = pal.ink,
                        modifier = Modifier
                            .pressDip(clearInteraction, to = press.control)
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(Color.Transparent)
                            .border(1.dp, pal.hairline, RoundedCornerShape(Radius.pill))
                            .clickable(interactionSource = clearInteraction, indication = null) {
                                scope.launch(Dispatchers.IO) {
                                    File(IVANApp.app.cacheDir, "coil_images").deleteRecursively()
                                    coil.Coil.imageLoader(IVANApp.app).memoryCache?.clear()
                                    cacheCleared = true
                                }
                            }
                            .pillHit()
                            .padding(horizontal = 20.dp)
                    )
                }
            }
        }

        // ── 搜索历史 ──
        item {
            LiquidCard(Modifier.fillMaxWidth(), radius = Radius.lg) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(Space.md + 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("搜索历史", style = MaterialTheme.typography.titleMedium, color = pal.ink)
                        Text(
                            if (historyCleared) "已清空" else "记录你搜过的片名",
                            style = MaterialTheme.typography.labelSmall,
                            color = pal.inkMutedOnGlass
                        )
                    }
                    val clearInteraction = remember { MutableInteractionSource() }
                    Text(
                        "清空",
                        style = MaterialTheme.typography.labelLarge,
                        color = pal.ink,
                        modifier = Modifier
                            .pressDip(clearInteraction, to = press.control)
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(Color.Transparent)
                            .border(1.dp, pal.hairline, RoundedCornerShape(Radius.pill))
                            .clickable(interactionSource = clearInteraction, indication = null) {
                                scope.launch(Dispatchers.IO) {
                                    com.ivan.cinema.db.AppDb.get(IVANApp.app).searchDao().clear()
                                    historyCleared = true
                                }
                            }
                            .pillHit()
                            .padding(horizontal = 20.dp)
                    )
                }
            }
        }

        item {
            Text(
                "IVAN CINEMA · 自用版\n数据来自公开采集接口，仅本机使用",
                style = MaterialTheme.typography.labelSmall,
                // 10sp + 0.7 alpha 只有 4.3:1，低于 4.5:1 —— 去掉 alpha
                color = pal.inkMutedOnGlass,
                modifier = Modifier.padding(vertical = Space.md)
            )
        }
    }
}

/** 胶囊按钮统一 48dp 命中区，文字在最小高度内垂直居中。 */
private fun Modifier.pillHit(): Modifier =
    this.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically)

/** 是否有可用网络：区分「已是最新」和「检查失败」的唯一本地依据。 */
private fun hasNetwork(ctx: android.content.Context): Boolean = runCatching {
    val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as android.net.ConnectivityManager
    val net = cm.activeNetwork ?: return@runCatching false
    val caps = cm.getNetworkCapabilities(net) ?: return@runCatching false
    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}.getOrDefault(false)

/** 更新卡片副标题：下载中显示进度，失败显示原因，其余显示版本信息。 */
private fun updateSubtitle(update: UpdateInfo?, apk: ApkState, currentVersion: String): String = when {
    apk is ApkState.Downloading -> {
        val pct = (apk.progress * 100).toInt()
        if (apk.total > 0) {
            "正在下载 $pct%（${apk.received.mb()}/${apk.total.mb()}MB）"
        } else {
            "正在下载…已获取 ${apk.received.mb()}MB"
        }
    }
    apk is ApkState.Ready -> "下载完成，点「安装」继续"
    apk is ApkState.Failed -> "下载失败：${apk.message}"
    update != null -> "发现 ${update.versionName}：${update.notes.ifEmpty { "建议更新" }}"
    else -> "当前 $currentVersion · 有新版本会在这里提示"
}

/** 更新卡片右侧按钮文案：跟随状态机变化。 */
private fun updateActionLabel(update: UpdateInfo?, apk: ApkState): String = when {
    apk is ApkState.Downloading -> "取消"
    apk is ApkState.Ready -> "安装"
    apk is ApkState.Failed -> "重试"
    update != null -> "更新"
    else -> "检查"
}

private fun Long.mb(): String = String.format("%.1f", this / 1048576.0)
