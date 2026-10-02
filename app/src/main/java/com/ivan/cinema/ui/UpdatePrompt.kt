package com.ivan.cinema.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ivan.cinema.IVANApp
import com.ivan.cinema.data.ApkState
import com.ivan.cinema.data.ApkUpdater
import com.ivan.cinema.data.UpdateInfo
import com.ivan.cinema.ui.components.press
import com.ivan.cinema.ui.components.pressDip
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.Space

/**
 * 启动时的更新提示。
 *
 * 之前只有设置页里一个角标 —— 不主动进设置、不主动点「检查」就永远看不到，
 * 20 个人用下来绝大多数会一直停在旧版本。这里改成启动即弹一次。
 *
 * 「以后再说」会按 versionCode 记住，同一个版本只打扰一次，不会每次开都弹。
 */
@Composable
fun UpdatePrompt(info: UpdateInfo, onDismiss: () -> Unit) {
    val pal = LocalIVAN.current
    val ctx = IVANApp.ctx()
    val apkState = ApkUpdater.state.value
    var installArmed by remember { mutableStateOf(false) }

    // 从「安装未知来源应用」设置页返回后自动续装（与设置页同一套处理）
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

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sheet))
                .background(pal.surface)
                .padding(Space.xl)
        ) {
            Text(
                "发现新版本 ${info.versionName}",
                style = MaterialTheme.typography.titleLarge,
                color = pal.ink
            )
            Spacer(Modifier.height(Space.sm))
            Text(
                info.notes.ifBlank { "建议更新到最新版本" },
                style = MaterialTheme.typography.bodyMedium,
                color = pal.inkMutedOnGlass,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )

            if (apkState is ApkState.Downloading) {
                Spacer(Modifier.height(Space.lg))
                LinearProgressIndicator(
                    progress = { apkState.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(Radius.pill)),
                    color = pal.accent,
                    trackColor = Color.White.copy(alpha = 0.14f)
                )
                Spacer(Modifier.height(Space.sm))
                Text(
                    "正在下载 ${(apkState.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = pal.inkMutedOnGlass
                )
            }
            if (apkState is ApkState.Failed) {
                Spacer(Modifier.height(Space.md))
                Text(
                    "下载失败：${apkState.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = pal.dangerOnGlass
                )
            }

            Spacer(Modifier.height(Space.xl))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DialogAction("以后再说", filled = false, onClick = onDismiss)
                Spacer(Modifier.width(Space.sm))
                DialogAction(
                    label = when (apkState) {
                        is ApkState.Downloading -> "取消"
                        is ApkState.Ready -> "安装"
                        is ApkState.Failed -> "重试"
                        else -> "立即更新"
                    },
                    filled = true,
                    onClick = {
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
                            else -> ApkUpdater.download(ctx, info.url, info.versionName)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DialogAction(label: String, filled: Boolean, onClick: () -> Unit) {
    val pal = LocalIVAN.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .pressDip(interaction, to = press.control)
            .clip(RoundedCornerShape(Radius.pill))
            .background(if (filled) pal.accent else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (filled) pal.accentInk else pal.inkMutedOnGlass
        )
    }
}

/** 「以后再说」按 versionCode 记一次，同一个版本不再重复弹。 */
object UpdatePromptPrefs {
    private const val PREF = "ivan_update"
    private const val KEY = "dismissed_code"

    fun dismissed(ctx: Context): Int =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY, -1)

    fun dismiss(ctx: Context, versionCode: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(KEY, versionCode).apply()
    }
}
