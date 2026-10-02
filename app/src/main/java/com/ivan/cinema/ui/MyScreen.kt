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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.AsyncImage
import com.ivan.cinema.IVANApp
import com.ivan.cinema.data.Account
import com.ivan.cinema.data.FollowStore
import com.ivan.cinema.data.MergedVod
import com.ivan.cinema.data.SourceHit
import com.ivan.cinema.data.SupabaseConfig
import com.ivan.cinema.data.VodSource
import com.ivan.cinema.db.AppDb
import com.ivan.cinema.db.FollowEntry
import com.ivan.cinema.db.WatchEntry
import com.ivan.cinema.ui.components.LiquidCard
import com.ivan.cinema.ui.components.ThinProgress
import com.ivan.cinema.ui.components.press
import com.ivan.cinema.ui.components.pressDip
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/*
 * 两个根页：
 *   追剧（FollowScreen）—— 每天打开 App 的理由。原来埋在设置的卡片里，
 *                          现在独立成页，抬头就把「多久查一次、会不会提醒」说清楚。
 *   我的（MyScreen）  —— 身份位 + 观看历史 + 收藏/下载/设置三个入口。
 *                        下载和设置从底栏降级成这里的二级入口（一个月才开一次）。
 */

// ─────────────────────────────────────────────────────────────────────────────
// 追剧
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 追剧列表。数据来自 [FollowStore.allFor]（按当前账号订阅的 Flow）。
 *
 * MacCMS 没有推送能力，[com.ivan.cinema.data.FollowWorker] 只能每天轮询一次 ——
 * 所以文案必须说「每天检查一次」，绝不能暗示实时提醒；通知权限没开也要如实说
 * 「更新不会提醒」，否则用户以为会响。
 */
@Composable
fun FollowScreen(
    onOpenDetail: (MergedVod) -> Unit,
    contentBottomPadding: Dp
) {
    val pal = LocalIVAN.current
    val ctx = IVANApp.ctx()
    val scope = rememberCoroutineScope()
    val account = Account.state.value
    // 换账号要重订 Flow，否则读到上一个账号的追剧行
    val follows by remember(account?.username) { FollowStore.allFor(ctx) }
        .collectAsState(initial = emptyList())
    // 通知权限/渠道状态：决定抬头是「更新后通知你」还是「不会提醒」
    var notifAllowed by remember { mutableStateOf(followNotifAllowed(ctx)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // 用户可能刚从系统设置里改了通知权限，回来要立刻反映到抬头
            if (event == Lifecycle.Event.ON_RESUME) notifAllowed = followNotifAllowed(ctx)
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
            Column(Modifier.padding(vertical = Space.md)) {
                Text(
                    "追剧",
                    style = MaterialTheme.typography.headlineSmall,
                    color = pal.ink
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    when {
                        follows.isEmpty() -> "在影片详情页点「追剧」，这里会显示更新的集数"
                        notifAllowed -> "每天检查一次更新，更新后通知你"
                        // 权限没开就不能承诺提醒，如实说明
                        else -> "每天检查一次更新；通知权限未开启，更新不会提醒"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    // 这段副标题直接落在页面底色上（不在卡片里），用 canvas 系令牌
                    color = if (!notifAllowed && follows.isNotEmpty()) pal.danger
                    else pal.inkMuted
                )
            }
        }

        if (follows.isEmpty()) {
            item { FollowEmptyState() }
        } else {
            items(follows, key = { it.vodKey }) { f ->
                FollowRow(
                    entry = f,
                    onOpen = {
                        // 追剧记录里带着源与源内 id，能直接重建一条命中进详情页
                        onOpenDetail(
                            MergedVod(
                                key = f.vodKey,
                                name = f.name,
                                year = f.year,
                                pic = f.pic,
                                hits = mutableListOf(
                                    SourceHit(
                                        VodSource(f.sourceName, f.sourceApi),
                                        f.vodId,
                                        ""
                                    )
                                )
                            )
                        )
                    },
                    onUnfollow = {
                        scope.launch(Dispatchers.IO) { FollowStore.unfollow(ctx, f.vodKey) }
                    }
                )
            }
        }
    }
}

/** 空态：明确指出去哪儿追剧（详情页的「追剧」按钮）。 */
@Composable
private fun FollowEmptyState() {
    val pal = LocalIVAN.current
    LiquidCard(Modifier.fillMaxWidth(), radius = Radius.lg) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Rounded.Star,
                contentDescription = null,
                tint = pal.inkMuted.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(Space.md))
            Text(
                "还没有追的剧",
                style = MaterialTheme.typography.titleMedium,
                color = pal.ink
            )
            Spacer(Modifier.height(Space.xs))
            Text(
                "打开任意影片的详情页，点「追剧」按钮即可加入。之后每天检查一次更新。",
                style = MaterialTheme.typography.labelSmall,
                color = pal.inkMutedOnGlass
            )
        }
    }
}

/** 追剧一行：海报 + 片名 + 更新状态 + 取消；点整行进详情。 */
@Composable
private fun FollowRow(
    entry: FollowEntry,
    onOpen: () -> Unit,
    onUnfollow: () -> Unit
) {
    val pal = LocalIVAN.current
    val openInteraction = remember { MutableInteractionSource() }
    LiquidCard(Modifier.fillMaxWidth(), radius = Radius.lg) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressDip(openInteraction, to = press.card)
                .clickable(interactionSource = openInteraction, indication = null) { onOpen() }
                .padding(Space.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(48.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(pal.surfaceRaised)
            ) {
                if (entry.pic.isNotEmpty()) {
                    AsyncImage(
                        model = entry.pic,
                        contentDescription = entry.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = pal.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    // episodeCount 是上次核验的集数基线：0 表示当时就没拿到集数
                    if (entry.episodeCount > 0) "已更新到第 ${entry.episodeCount} 集"
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
                    .pressDip(unwatch, to = press.control)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(Color.Transparent)
                    .border(1.dp, pal.hairline, RoundedCornerShape(Radius.pill))
                    .clickable(interactionSource = unwatch, indication = null) { onUnfollow() }
                    .pillHit()
                    .padding(horizontal = 20.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 我的
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 「我的」根页：身份位 → 观看历史 → 收藏 / 下载 / 设置。
 *
 * 身份位与设置里的那套完全一致（没重设计），只是搬到了更常被看到的位置。
 */
@Composable
fun MyScreen(
    contentBottomPadding: Dp,
    onLogin: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWatch: (WatchEntry) -> Unit
) {
    val pal = LocalIVAN.current
    val ctx = IVANApp.ctx()
    val scope = rememberCoroutineScope()
    // 观看记录按当前用户过滤（本机表设备级，换账号要按 uid 重订）
    val uid = Account.currentUserId()
    val watch by remember(uid) {
        AppDb.get(IVANApp.app).watchDao().recentFor(uid)
    }.collectAsState(initial = emptyList())

    LazyColumn(
        contentPadding = PaddingValues(
            start = Space.lg, end = Space.lg, top = 0.dp, bottom = contentBottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(Space.md),
        modifier = Modifier.fillMaxSize().statusBarsPadding()
    ) {
        item {
            Text(
                "我的",
                style = MaterialTheme.typography.headlineSmall,
                color = pal.ink,
                modifier = Modifier.padding(vertical = Space.md)
            )
        }

        // ── 身份位（与设置里同款，未重设计）──
        item { IdentityCard(onLogin = onLogin) }

        // ── 观看历史 ──
        item {
            LiquidCard(Modifier.fillMaxWidth(), radius = Radius.lg) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(Space.md + 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "观看历史",
                            style = MaterialTheme.typography.titleMedium,
                            color = pal.ink,
                            modifier = Modifier.weight(1f)
                        )
                        if (watch.isNotEmpty()) {
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
                                        val snapshot = watch
                                        scope.launch(Dispatchers.IO) {
                                            val dao = AppDb.get(ctx).watchDao()
                                            snapshot.forEach { dao.delete(it.vodKey) }
                                        }
                                    }
                                    .pillHit()
                                    .padding(horizontal = 20.dp)
                            )
                        }
                    }
                    if (watch.isEmpty()) {
                        Spacer(Modifier.height(Space.sm))
                        Text(
                            "还没有观看记录，去首页找一部看吧",
                            style = MaterialTheme.typography.labelSmall,
                            color = pal.inkMutedOnGlass
                        )
                    } else {
                        Spacer(Modifier.height(Space.xs))
                        watch.forEach { e ->
                            WatchHistoryRow(
                                entry = e,
                                onOpen = { onOpenWatch(e) },
                                onDelete = {
                                    scope.launch(Dispatchers.IO) {
                                        AppDb.get(ctx).watchDao().delete(e.vodKey)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // ── 二级入口 ──
        item {
            EntryRow(
                icon = Icons.Rounded.Favorite,
                title = "片单收藏",
                subtitle = "收藏的影片，按片单浏览",
                onClick = onOpenFavorites
            )
        }
        item {
            EntryRow(
                icon = Icons.Rounded.Download,
                title = "下载",
                subtitle = "离线缓存与下载进度",
                onClick = onOpenDownloads
            )
        }
        item {
            EntryRow(
                icon = Icons.Rounded.Settings,
                title = "设置",
                subtitle = "更新、动效、缓存、搜索历史",
                onClick = onOpenSettings
            )
        }
    }
}

/**
 * 身份位卡片：头像 + 用户名 + SVIP 徽标 + 登录/退出。
 * 从 SettingsScreen 原样搬来（含退出二次确认），只换了宿主页面。
 */
@Composable
private fun IdentityCard(onLogin: () -> Unit) {
    val pal = LocalIVAN.current
    val ctx = IVANApp.ctx()
    val account = Account.state.value
    var showLogout by remember { mutableStateOf(false) }
    // 退出登录会清掉登录态，二次确认
    var confirmLogout by remember { mutableStateOf(false) }

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
                        .pressDip(action, to = press.control)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(if (account == null) pal.accent else Color.Transparent)
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
                            .pressDip(svipAction, to = press.control)
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
                            .pressDip(logoutAction, to = press.control)
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(Color.Transparent)
                            .border(1.dp, pal.hairline, RoundedCornerShape(Radius.pill))
                            .clickable(interactionSource = logoutAction, indication = null) {
                                confirmLogout = true
                            }
                            .pillHit()
                            .padding(horizontal = 20.dp)
                    )
                }
            }
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

/** 观看历史一行：缩略图 + 进度条 + 片名/集名，点进续播，右侧单条删除。 */
@Composable
private fun WatchHistoryRow(
    entry: WatchEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val pal = LocalIVAN.current
    val openInteraction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier
                .weight(1f)
                .pressDip(openInteraction, to = press.card)
                .clip(RoundedCornerShape(Radius.md))
                .clickable(interactionSource = openInteraction, indication = null) { onOpen() }
                .padding(vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(96.dp)
                    .height(54.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(pal.surfaceRaised)
            ) {
                if (entry.pic.isNotEmpty()) {
                    AsyncImage(
                        model = entry.pic,
                        contentDescription = entry.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                ThinProgress(
                    fraction = {
                        if (entry.durationMs > 0) entry.positionMs.toFloat() / entry.durationMs else 0f
                    },
                    modifier = Modifier.align(Alignment.BottomStart)
                )
            }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = pal.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${entry.episodeName.ifBlank { "第${entry.episodeIndex + 1}集" }} · ${fmtClock(entry.positionMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = pal.inkMutedOnGlass,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        val deleteInteraction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .size(48.dp)
                .pressDip(deleteInteraction, to = press.control)
                .clip(RoundedCornerShape(Radius.pill))
                .clickable(interactionSource = deleteInteraction, indication = null) { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = "删除这条观看记录",
                tint = pal.inkMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 「我的」里的二级入口行：图标 + 标题 + 副标题 + 右箭头。 */
@Composable
private fun EntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val pal = LocalIVAN.current
    val interaction = remember { MutableInteractionSource() }
    LiquidCard(Modifier.fillMaxWidth(), radius = Radius.lg) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressDip(interaction, to = press.card)
                .clickable(interactionSource = interaction, indication = null) { onClick() }
                .heightIn(min = 48.dp)
                .padding(Space.md + 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(pal.surfaceRaised),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = pal.accent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = pal.ink
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = pal.inkMutedOnGlass
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = pal.inkMuted,
                modifier = Modifier.size(20.dp)
            )
        }
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

private fun fmtClock(ms: Long): String {
    if (ms <= 0) return "00:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%02d:%02d", m, sec)
}
