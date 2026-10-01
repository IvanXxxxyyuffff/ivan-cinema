package com.ivan.cinema.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * 液态玻璃 —— "玻璃"的全部参数来源。
 *
 * 玻璃是四层叠出来的东西：
 *   ① 本体     半透明的面，带上下色差（上清下浊，光穿过厚度的感觉）
 *   ② 边缘折射 一圈渐变描边：迎光那侧亮、背光那侧暗
 *   ③ 高光     斜向扫光 + 顶边一道 1px 亮线
 *   ④ 外发光   底下压一层同色系的辉光，玻璃才有厚度
 *
 * 判据：面板自己的明暗落差 < 透过来的背景明暗落差，否则退化成"实色卡片"。
 * 所以玻璃底下必须有东西 —— 氛围场（Ambience）始终存在，有东西可透。
 */

data class GlassPalette(
    val bodyTop: Color,
    val bodyBottom: Color,
    val edgeHi: Color,
    val edgeLo: Color,
    val sheen: Color,
    val topLine: Color,
    val glow: Color,
    val hairline: Color,
)

/** 全局氛围场：三个大而软的色斑 + 底色 + 上下压暗。 */
data class Ambience(
    val base: Color,
    val a: Color,
    val b: Color,
    val c: Color,
    val scrimTop: Float,
    val scrimBottom: Float,
)

val DarkAmbience = Ambience(
    base = Color(0xFF0C0B10),
    a = Color(0xFF3A3550),
    b = Color(0xFF26313F),
    c = Color(0xFF3A2F42),
    scrimTop = 0.40f,
    scrimBottom = 0.55f,
)

val DarkGlass = GlassPalette(
    // 本体落差 0.050 ≈ 13/255，透上来的氛围场 ~33/255 —— 右边大过左边，是玻璃不是板。
    bodyTop = Color(0x20FFFFFF),
    bodyBottom = Color(0x13FFFFFF),
    edgeHi = Color(0x66FFFFFF),
    edgeLo = Color(0x2AFFFFFF),
    sheen = Color(0x2EFFFFFF),
    topLine = Color(0x4DFFFFFF),
    // 辉光是玻璃自身的色偏，跟着唯一的强调色（香槟金）走。
    glow = Color(0x17C8A96A),
    hairline = Color(0x1AFFFFFF),
)

/**
 * 播放器专用：永远按暗底调，不跟主题走（那一屏压在海报/视频上，永远暗）。
 * 比 DarkGlass 鼓一档 —— 它压在有纹理的画面上，太薄就读不出是玻璃。
 */
val ImmersiveGlass = GlassPalette(
    bodyTop = Color(0x3DFFFFFF),
    bodyBottom = Color(0x1FFFFFFF),
    edgeHi = Color(0x80FFFFFF),
    edgeLo = Color(0x24FFFFFF),
    sheen = Color(0x3DFFFFFF),
    topLine = Color(0x66FFFFFF),
    glow = Color(0x24FFFFFF),
    hairline = Color(0x26FFFFFF),
)

/** 播放器屏自给的显式声明：这一屏的玻璃与主题无关。 */
val LocalGlassOverride = staticCompositionLocalOf<GlassPalette?> { null }

@Composable
fun glassPalette(): GlassPalette =
    LocalGlassOverride.current ?: DarkGlass

@Composable
fun ambience(): Ambience = DarkAmbience
