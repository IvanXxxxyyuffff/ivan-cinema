package com.ivan.cinema.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivan.cinema.IVANApp
import com.ivan.cinema.data.Account
import com.ivan.cinema.ui.components.LiquidBar
import com.ivan.cinema.ui.components.pressDip
import com.ivan.cinema.ui.theme.LocalIVAN
import com.ivan.cinema.ui.theme.Radius
import com.ivan.cinema.ui.theme.Space
import com.ivan.cinema.ui.theme.accentBrush

/** 登录 / 注册（本地账号，自用）。 */
@Composable
fun LoginScreen(onDone: () -> Unit) {
    val pal = LocalIVAN.current
    val ctx = IVANApp.ctx()
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var registerMode by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        PosterBlurBackdrop(null)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(72.dp))
            Text(
                "IVAN CINEMA",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                color = pal.ink
            )
            Spacer(Modifier.height(Space.sm))
            Text(
                if (registerMode) "创建你的本地账号" else "登录以同步你的身份位",
                style = MaterialTheme.typography.bodyMedium,
                color = pal.inkMuted
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
                hint = "密码",
                isPassword = true
            )

            error?.let {
                Spacer(Modifier.height(Space.md))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = pal.danger
                )
            }

            Spacer(Modifier.height(Space.xl))
            val btnInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .pressDip(btnInteraction, to = 0.98f)
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(accentBrush(pal))
                    .clickable(interactionSource = btnInteraction, indication = null) {
                        val err = if (registerMode) {
                            Account.register(ctx, user, pass)
                        } else {
                            Account.login(ctx, user, pass)
                        }
                        if (err == null) onDone() else error = err
                    }
                    .padding(vertical = Space.md),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (registerMode) "注册并登录" else "登录",
                    style = MaterialTheme.typography.titleMedium,
                    color = pal.accentInk
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
                    .padding(horizontal = Space.md, vertical = Space.sm)
            )
        }
    }
}

@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    isPassword: Boolean
) {
    val pal = LocalIVAN.current
    LiquidBar(modifier = Modifier.fillMaxWidth(), radius = Radius.lg, elevated = false) {
        Box(Modifier.padding(horizontal = Space.lg, vertical = Space.md + 2.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = pal.ink, fontSize = 16.sp),
                cursorBrush = SolidColor(pal.accent),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(hint, style = MaterialTheme.typography.bodyLarge, color = pal.inkMuted)
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** SVIP 金色徽标（参考 IVAN MUSIC 的会员身份位）。 */
@Composable
fun SvipBadge() {
    Box(
        Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFFE8B23A), Color(0xFFB8871F))
                )
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            "SVIP",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            color = Color(0xFF2A1B02)
        )
    }
}
