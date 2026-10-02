package com.ivan.cinema.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ivan.cinema.BuildConfig
import com.ivan.cinema.IVANApp
import com.ivan.cinema.data.Account
import com.ivan.cinema.data.ApkState
import com.ivan.cinema.data.ApkUpdater
import com.ivan.cinema.data.FollowStore
import com.ivan.cinema.data.SupabaseConfig
import com.ivan.cinema.data.UpdateChecker
import com.ivan.cinema.data.UpdateInfo
import com.ivan.cinema.ui.components.LiquidCard
import com.ivan.cinema.ui.components.pressDip
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.MotionPrefs
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SettingsScreen(contentBottomPadding: Dp, onLogin: () -> Unit = {}) {
    val pal = LocalIVAN.current
    val scope = rememberCoroutineScope()
    val ctx = IVANApp.ctx()
    val account = Account.state.value
    val update = UpdateChecker.latest.value

    var reduce by remember { mutableStateOf(MotionPrefs.reduce) }
    var cacheCleared by remember { mutableStateOf(false) }
    var historyCleared by remember { mutableStateOf(false) }
    var showLogout by remember { mutableStateOf(false) }
    // 退出登录会清掉登录态，二次确认
    var confirmLogout by remember { mutableStateOf(false) }
    // 检查更新是异步无回调的：这里承接「已是最新 / 检查失败」的瞬时反馈
    var updateNote by remember { mutableStateOf<String?>(null) }
    var installArmed by remember { mutableStateOf(false) }
    val apkState = ApkUpdater.state.value
    // 我的追剧：按当前账号订阅（换账号要重订，否则读到上一个账号的行）
    val follows by remember(account?.username) { FollowStore.allFor(ctx) }
        .collectAsState(initial = emptyList())
    // 通知权限/渠道状态：决定追剧卡片是「更新后通知你」还是「不会提醒」
    var notifAllowed by remember { mutableStateOf(followNotifAllowed(ctx)) }

    // 从「安装未知来源应用」设置页返回后自动继续安装（只自动触发一次）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 用户可能刚从系统设置里改了通知权限，回来要立刻反映到追剧卡片
                notifAllowed = followNotifAllowed(ctx)
                if (installArmed) {
                    installArmed = false
                    val s = ApkUpdater.state.value
                    if (s is ApkState.Ready && ApkUpdater.canInstall(ctx)) {
                        ApkUpdater.install(ctx, s.file)
                    }
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
        modifier = Modifier.fillMaxSize().statusBarsPadding()
    ) {
        item {
            Text(
                "设置",
                style = MaterialTheme.typography.headlineSmall,
                color = pal.ink,
                modifier = Modifier.padding(vertical = Space.md)
            )
        }

        // ── 身份位（头像 + 用户名 + SVIP 徽标）──
        item {
            LiquidCard(Modifier.fillMaxWidth(), radius = Radius.lg) {
                Column(Modifier.padding(Space.md + 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(
                                    if (account != null) pal.accent.copy(alpha = 0.22f)
                                    else Color.White.copy(alpha = 0.08f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                account?.username?.take(1)?.uppercase() ?: "?",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (account != null) pal.accent else pal.inkMuted
                            )
                        }
                        Spacer(Modifier.width(Space.md))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    account?.username ?: "未登录",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = pal.ink
                                )
                                if (account?.isSvip == true) {
                                    Spacer(Modifier.width(Space.sm))
                                    SvipBadge()
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (account == null) {
                                    // 只有真正配置了 Supabase 才承诺同步，否则只是本机身份位
                                    if (SupabaseConfig.isConfigured()) "登录后可同步观看记录"
                                    else "登录后可显示身份位与会员标识"
                                } else if (account.isSvip) "会员身份 · 本机有效" else "普通用户",
                                style = MaterialTheme.typography.labelSmall,
                                color = pal.inkMutedOnGlass
                            )
                        }
                        val action = remember { MutableInteractionSource() }
                        Text(
                            if (account == null) "登录 / 注册" else "账号",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (account == null) pal.accentInk else pal.ink,
                            modifier = Modifier
                                .pressDip(action, to = 0.94f)
                                .clip(RoundedCornerShape(Radius.pill))
                            .background(
                                if (account == null) pal.accent else Color.Transparent
                            )
                            .then(
                                if (account == null) Modifier
                                else Modifier.border(
                                    1.dp, pal.hairline, RoundedCornerShape(Radius.pill)
                                )
                            )
                                .clickable(interactionSource = action, indication = null) {
                                    if (account == null) onLogin() else showLogout = !showLogout
                                }
                                .pillHit()
                                .padding(horizontal = 20.dp)
                        )
                    }
                    if (account != null && showLogout) {
                        Spacer(Modifier.height(Space.md))
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            val svipAction = remember { MutableInteractionSource() }
                            Text(
                                if (account.isSvip) "取消 SVIP" else "开启 SVIP",
                                style = MaterialTheme.typography.labelLarge,
                                color = pal.ink,
                                modifier = Modifier
                                    .pressDip(svipAction, to = 0.94f)
                                    .clip(RoundedCornerShape(Radius.pill))
                                    .background(Color.Transparent)
                                .border(1.dp, pal.hairline, RoundedCornerShape(Radius.pill))
                                    .clickable(interactionSource = svipAction, indication = null) {
                                        Account.setSvip(ctx, !account.isSvip)
                                    }
                                    .pillHit()
                                    .padding(horizontal = 20.dp)
                            )
                            val logoutAction = remember { MutableInteractionSource() }
                            Text(
                                "退出登录",
                                style = MaterialTheme.typography.labelLarge,
                                color = pal.dangerOnGlass,
                                modifier = Modifier
                                    .pressDip(logoutAction, to = 0.94f)
                                    .clip(RoundedCornerShape(Radius.pill))
                                    .background(Color.Transparent)
                                .border(1.dp, pal.hairline, RoundedCornerShape(Radius.pill))
                                    .clickable(interactionSource = logoutAction, indication = null) {
                                        // 退出会清登录态，先确认
                                        confirmLogout = true
                                    }
                                    .pillHit()
                                    .padding(horizontal = 20.dp)
                            )
                        }
                    }
                }
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
                                            // 白字压亮红只有 3.4:1，换成深一档的红
                                            .background(Color(0xFFB04A4A))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("新版本", fontSize = 10.sp, color = Color.White)
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
                                .pressDip(checkAction, to = 0.94f)
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
                            .pressDip(clearInteraction, to = 0.94f)
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
                            .pressDip(clearInteraction, to = 0.94f)
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

        // ── 我的追剧（MacCMS 无推送，只能每天轮询 —— 文案必须说清节奏）──
        item {
            LiquidCard(Modifier.fillMaxWidth(), radius = Radius.lg) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(Space.md + 2.dp)
                ) {
                    Text("我的追剧", style = MaterialTheme.typography.titleMedium, color = pal.ink)
                    Spacer(Modifier.height(Space.xs))
                    if (follows.isEmpty()) {
                        Text(
                            "还没有追的剧。在详情页点「追剧」，更新了会通知你",
                            style = MaterialTheme.typography.labelSmall,
                            color = pal.inkMutedOnGlass
                        )
                    } else {
                        Text(
                            if (notifAllowed) "每天检查一次更新，更新后通知你"
                            else "每天检查一次更新；通知权限未开启，更新不会提醒",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (notifAllowed) pal.inkMutedOnGlass else pal.dangerOnGlass
                        )
                        Spacer(Modifier.height(Space.md))
                        follows.forEach { f ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Space.xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        f.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = pal.ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        if (f.episodeCount > 0) "已更新到第 ${f.episodeCount} 集"
                                        else "等待更新",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = pal.inkMutedOnGlass
                                    )
                                }
                                val unwatch = remember { MutableInteractionSource() }
                                Text(
                                    "取消",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = pal.ink,
                                    modifier = Modifier
                                        .pressDip(unwatch, to = 0.94f)
                                        .clip(RoundedCornerShape(Radius.pill))
                                        .background(Color.Transparent)
                                        .border(1.dp, pal.hairline, RoundedCornerShape(Radius.pill))
                                        .clickable(interactionSource = unwatch, indication = null) {
                                            scope.launch(Dispatchers.IO) {
                                                FollowStore.unfollow(ctx, f.vodKey)
                                            }
                                        }
                                        .pillHit()
                                        .padding(horizontal = 20.dp)
                                )
                            }
                        }
                    }
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

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录", color = pal.ink) },
            text = {
                Text(
                    "退出后需要重新登录才能恢复身份位与同步，确定退出？",
                    color = pal.inkMutedOnGlass
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        Account.logout(ctx)
                        showLogout = false
                        confirmLogout = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("退出", color = pal.dangerOnGlass)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmLogout = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("取消", color = pal.inkMutedOnGlass)
                }
            },
            containerColor = pal.surfaceRaised,
            titleContentColor = pal.ink,
            textContentColor = pal.inkMutedOnGlass
        )
    }
}

/** 胶囊按钮统一 48dp 命中区，文字在最小高度内垂直居中。 */
private fun Modifier.pillHit(): Modifier =
    this.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically)

/** 追剧通知是否真能到达：13+ 看运行时权限，低版本看渠道总开关。 */
private fun followNotifAllowed(ctx: android.content.Context): Boolean = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) false
    else NotificationManagerCompat.from(ctx).areNotificationsEnabled()
}.getOrDefault(true)

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
