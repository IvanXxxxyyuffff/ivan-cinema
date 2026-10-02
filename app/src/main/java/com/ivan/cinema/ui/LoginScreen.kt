package com.ivan.cinema.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivan.cinema.IVANApp
import com.ivan.cinema.data.Account
import com.ivan.cinema.ui.components.LiquidBar
import com.ivan.cinema.ui.components.press
import com.ivan.cinema.ui.components.pressDip
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.LoginWordmark
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.SolidColorBrushCompat
import com.ivan.cinema.ui.theme.Space
import com.ivan.cinema.ui.theme.accentBrush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 登录 / 注册（本地账号，自用）。 */
@Composable
fun LoginScreen(onDone: () -> Unit, onBack: () -> Unit = {}) {
    val pal = LocalIVAN.current
    val ctx = IVANApp.ctx()
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var registerMode by remember { mutableStateOf(false) }
    var showPass by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        PosterBlurBackdrop(null)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(72.dp))
            // 字标只认 theme 里的 LoginWordmark 这一处出处（26sp / 加粗 / 4sp 字距）；
            // 原来在这儿手写了一遍同样参数，设计一改两边就会漂
            Text(
                "IVAN CINEMA",
                style = LoginWordmark,
                maxLines = 1,
                color = pal.ink
            )
            Spacer(Modifier.height(Space.sm))
            Text(
                if (registerMode) "用户名 2–20 位，仅限英文、数字与 . _ -（不支持中文）"
                else "登录后可同步观看记录",
                style = MaterialTheme.typography.bodyMedium,
                color = pal.inkMutedOnGlass
            )
            Spacer(Modifier.height(Space.xxl))

            LoginField(
                value = user,
                onValueChange = { user = it; error = null },
                hint = "用户名",
                isPassword = false
            )
            Spacer(Modifier.height(Space.md))
            LoginField(
                value = pass,
                onValueChange = { pass = it; error = null },
                hint = "密码（至少 6 位）",
                isPassword = true,
                visible = showPass,
                onToggleVisible = { showPass = !showPass }
            )

            error?.let {
                Spacer(Modifier.height(Space.md))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = pal.dangerOnGlass
                )
            }

            Spacer(Modifier.height(Space.xl))
            val btnInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .pressDip(btnInteraction, to = press.card)
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(
                        if (busy) SolidColorBrushCompat(Color.White.copy(alpha = 0.14f))
                        else accentBrush(pal)
                    )
                    .clickable(
                        enabled = !busy,
                        interactionSource = btnInteraction,
                        indication = null
                    ) {
                        if (busy) return@clickable
                        error = null
                        busy = true
                        scope.launch {
                            // Account 内部用 runBlocking 打网络，必须挪到 IO 线程，
                            // 否则整页卡死、没有加载反馈，连点还会重复提交
                            val err = withContext(Dispatchers.IO) {
                                if (registerMode) Account.register(ctx, user, pass)
                                else Account.login(ctx, user, pass)
                            }
                            busy = false
                            if (err == null) onDone() else error = err
                        }
                    }
                    .heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when {
                        busy -> "请稍候…"
                        registerMode -> "注册并登录"
                        else -> "登录"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (busy) pal.inkMutedOnGlass else pal.accentInk
                )
            }

            Spacer(Modifier.height(Space.lg))
            Text(
                if (registerMode) "已有账号？去登录" else "还没有账号？去注册",
                style = MaterialTheme.typography.bodyMedium,
                color = pal.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .clickable {
                        registerMode = !registerMode
                        error = null
                    }
                    .heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(horizontal = Space.md)
            )
        }

        // 从设置页进来只能靠系统返回键 —— 这里补一个左上角返回。
        // 与详情/分类/收藏/筛选页的返回胶囊逐项对齐：48dp / 黑 55% / 1px 白描边 / 22dp 白箭头。
        // 原实现是 34% 且没有描边，浮在模糊背景上比其它页明显偏淡、像半成品。
        Box(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = Space.md, top = Space.sm)
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
    }
}

@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    isPassword: Boolean,
    visible: Boolean = false,
    onToggleVisible: (() -> Unit)? = null
) {
    val pal = LocalIVAN.current
    LiquidBar(modifier = Modifier.fillMaxWidth(), radius = Radius.lg, elevated = false) {
        Row(
            Modifier
                .padding(horizontal = Space.lg)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = pal.ink, fontSize = 16.sp),
                    cursorBrush = SolidColor(pal.accent),
                    visualTransformation = if (isPassword && !visible) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
                        // 密码是最后一个字段，回车直接提交，不再弹「下一项」
                        imeAction = if (isPassword) ImeAction.Done else ImeAction.Next
                    ),
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty()) {
                                Text(hint, style = MaterialTheme.typography.bodyLarge, color = pal.inkMutedOnGlass)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        // 占位符是兄弟节点，读屏只会念「编辑框」，这里补一个可读名字
                        .semantics { contentDescription = hint }
                )
            }
            // 密码可见性开关：48dp 命中区，图标画在字段内
            if (isPassword) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable { onToggleVisible?.invoke() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        contentDescription = if (visible) "隐藏密码" else "显示密码",
                        tint = pal.inkMutedOnGlass,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/** SVIP 金色徽标（参考 IVAN MUSIC 的会员身份位）。 */
@Composable
fun SvipBadge() {
    val pal = LocalIVAN.current
    Box(
        Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(
                // 品牌金只此一处出处：pal.brand（= BrandGold）。末端向 ink 靠 18% 做出体积，
                // 不再自造第二组金色 —— 原来的 #E8B23A→#B8871F 是把同一件事又写了一遍。
                Brush.horizontalGradient(
                    listOf(pal.brand, lerp(pal.brand, pal.ink, 0.18f))
                )
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            "SVIP",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            // 金字上的墨色用 accentInk：深色底（浅色主题）下是白字、亮金底（深色主题）下是近黑，
            // 两种主题都 ≥4.5:1；原来的 #2A1B02 在浅色主题的深金底上对比不足。
            color = pal.accentInk
        )
    }
}
