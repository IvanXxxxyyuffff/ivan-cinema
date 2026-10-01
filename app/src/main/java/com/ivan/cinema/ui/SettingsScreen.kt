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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ivan.cinema.BuildConfig
import com.ivan.cinema.IVANApp
import com.ivan.cinema.data.Account
import com.ivan.cinema.data.ApkState
import com.ivan.cinema.data.ApkUpdater
import com.ivan.cinema.data.UpdateChecker
import com.ivan.cinema.data.UpdateInfo
import com.ivan.cinema.ui.components.LiquidCard
import com.ivan.cinema.ui.components.pressDip
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.MotionPrefs
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.Space
import kotlinx.coroutines.Dispatchers
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
                                if (account == null) "登录后可显示身份位与会员标识"
                                else if (account.isSvip) "会员身份 · 本机有效" else "普通用户",
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
                                    if (account == null) pal.accent else Color.White.copy(alpha = 0.14f)
                                )
                                .clickable(interactionSource = action, indication = null) {
                                    if (account == null) onLogin() else showLogout = !showLogout
                                }
                                .padding(horizontal = Space.md, vertical = 15.dp)
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
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .clickable(interactionSource = svipAction, indication = null) {
                                        Account.setSvip(ctx, !account.isSvip)
                                    }
                                    .padding(horizontal = Space.md, vertical = 15.dp)
                            )
                            val logoutAction = remember { MutableInteractionSource() }
                            Text(
                                "退出登录",
                                style = MaterialTheme.typography.labelLarge,
                                color = pal.dangerOnGlass,
                                modifier = Modifier
                                    .pressDip(logoutAction, to = 0.94f)
                                    .clip(RoundedCornerShape(Radius.pill))
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .clickable(interactionSource = logoutAction, indication = null) {
                                        Account.logout(ctx)
                                        showLogout = false
                                    }
                                    .padding(horizontal = Space.md, vertical = 15.dp)
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
                                updateSubtitle(update, apkState, BuildConfig.VERSION_NAME),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (apkState is ApkState.Failed) pal.danger else pal.inkMuted
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
                                .background(if (update != null) pal.accent else Color.White.copy(alpha = 0.14f))
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
                                            if (u != null) ApkUpdater.download(ctx, u.url, u.versionName)
                                            else UpdateChecker.check(ctx, BuildConfig.VERSION_CODE)
                                        }
                                    }
                                }
                                .padding(horizontal = Space.md, vertical = 15.dp)
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
                            MotionPrefs.setUser(it)
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
                            .background(Color.White.copy(alpha = 0.14f))
                            .clickable(interactionSource = clearInteraction, indication = null) {
                                scope.launch(Dispatchers.IO) {
                                    File(IVANApp.app.cacheDir, "coil_images").deleteRecursively()
                                    coil.Coil.imageLoader(IVANApp.app).memoryCache?.clear()
                                    cacheCleared = true
                                }
                            }
                            .padding(horizontal = Space.md, vertical = Space.sm)
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
                            .background(Color.White.copy(alpha = 0.14f))
                            .clickable(interactionSource = clearInteraction, indication = null) {
                                scope.launch(Dispatchers.IO) {
                                    com.ivan.cinema.db.AppDb.get(IVANApp.app).searchDao().clear()
                                    historyCleared = true
                                }
                            }
                            .padding(horizontal = Space.md, vertical = Space.sm)
                    )
                }
            }
        }

        item {
            Text(
                "IVAN CINEMA · 自用版\n数据来自公开采集接口，仅本机使用",
                style = MaterialTheme.typography.labelSmall,
                color = pal.inkMutedOnGlass.copy(alpha = 0.7f),
                modifier = Modifier.padding(vertical = Space.md)
            )
        }
    }
}

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
