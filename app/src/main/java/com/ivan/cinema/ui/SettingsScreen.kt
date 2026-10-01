package com.ivan.cinema.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivan.cinema.IVANApp
import com.ivan.cinema.data.Account
import com.ivan.cinema.data.UpdateChecker
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
                                color = pal.inkMuted
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
                                .padding(horizontal = Space.md, vertical = Space.sm)
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
                                    .padding(horizontal = Space.md, vertical = Space.sm)
                            )
                            val logoutAction = remember { MutableInteractionSource() }
                            Text(
                                "退出登录",
                                style = MaterialTheme.typography.labelLarge,
                                color = pal.danger,
                                modifier = Modifier
                                    .pressDip(logoutAction, to = 0.94f)
                                    .clip(RoundedCornerShape(Radius.pill))
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .clickable(interactionSource = logoutAction, indication = null) {
                                        Account.logout(ctx)
                                        showLogout = false
                                    }
                                    .padding(horizontal = Space.md, vertical = Space.sm)
                            )
                        }
                    }
                }
            }
        }

        // ── 检查更新 ──
        item {
            LiquidCard(Modifier.fillMaxWidth(), radius = Radius.lg) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(Space.md + 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                                        .background(pal.danger)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("新版本", fontSize = 10.sp, color = Color.White)
                                }
                            }
                        }
                        Text(
                            when {
                                update != null -> "发现 ${update.versionName}：${update.notes.ifEmpty { "建议更新" }}"
                                else -> "当前 1.0.0 · 有新版本会在这里提示"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = pal.inkMuted
                        )
                    }
                    val checkAction = remember { MutableInteractionSource() }
                    Text(
                        if (update != null) "去更新" else "检查",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (update != null) pal.accentInk else pal.ink,
                        modifier = Modifier
                            .pressDip(checkAction, to = 0.94f)
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(if (update != null) pal.accent else Color.White.copy(alpha = 0.14f))
                            .clickable(interactionSource = checkAction, indication = null) {
                                if (update != null && update.url.isNotEmpty()) {
                                    runCatching {
                                        ctx.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(update.url))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                } else {
                                    UpdateChecker.check(ctx, 1)
                                }
                            }
                            .padding(horizontal = Space.md, vertical = Space.sm)
                    )
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
                            color = pal.inkMuted
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
                            color = pal.inkMuted
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
                            color = pal.inkMuted
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
                color = pal.inkMuted.copy(alpha = 0.7f),
                modifier = Modifier.padding(vertical = Space.md)
            )
        }
    }
}
