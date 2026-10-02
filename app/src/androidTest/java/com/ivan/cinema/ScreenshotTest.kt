package com.ivan.cinema

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 逐屏截图取证（MainActivity 内的所有页面）。
 *
 * 本机模拟器的 screencap 冻在启动后的第一帧（`dumpsys SurfaceFlinger --latency` 全 0，
 * 8 种 GPU/内存/分辨率组合都一样），所以不走屏幕，改用 `captureToImage()` 把 Compose 树
 * 直接画进 Bitmap —— 纯 CPU 路径，与显示管线无关。
 *
 * 存到 MediaStore 的 Pictures/ivanshots/：应用私有目录在 Android 11+ 对 adb shell 不可读，
 * 且测试跑完 Gradle 会卸载应用、私有目录会被一并删掉。
 *
 * 取回：`adb pull /sdcard/Pictures/ivanshots/`
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val failures = StringBuilder()

    private fun step(name: String, block: () -> Unit) {
        runCatching(block).onFailure { failures.append("[$name] ${it.message}\n") }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/ivanshots"
            )
        }
        val resolver = rule.activity.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert 失败")
        resolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 用精确文案点，substring=false，避免误命中别的节点。 */
    private fun clickExact(text: String) {
        rule.onAllNodesWithText(text, substring = false).onFirst().performClick()
    }

    private fun clickContains(text: String) {
        rule.onAllNodesWithText(text, substring = true).onFirst().performClick()
    }

    /** 回根页顶部。用 recreate 而不是 back()：back() 在栈为空时会直接退出 APP。 */
    private fun toRoot() {
        rule.activityRule.scenario.recreate()
        Thread.sleep(6_000)
    }

    @Test
    fun captureAll() {
        Thread.sleep(16_000)
        step("01-home") { shot("01-home") }

        step("02-home-scrolled") {
            rule.onRoot().performTouchInput { swipeUp() }
            Thread.sleep(3_000)
            shot("02-home-scrolled")
        }

        // 分类页：首页「更多 ›」推入
        step("root-0") { toRoot() }
        step("03-category") {
            clickContains("更多")
            Thread.sleep(11_000)
            shot("03-category")
        }

        // 搜索页初始态（历史 / 热词）。用坐标点顶栏搜索框：
        // 占位文案节点的可点区域算不出来，直接按位置点更稳。
        step("root-1") { toRoot() }
        step("04-search") {
            rule.onRoot().performTouchInput { click(percentOffset(0.5f, 0.055f)) }
            Thread.sleep(4_000)
            shot("04-search")
        }

        // 搜索有结果态：直接往输入框打字（performTextInput 自己会请求焦点）
        step("05-search-results") {
            rule.onNode(hasSetTextAction()).performTextInput("复仇")
            Thread.sleep(14_000)
            shot("05-search-results")
        }

        // 筛选页
        step("06-filter") {
            clickContains("按分类")
            Thread.sleep(10_000)
            shot("06-filter")
        }

        // 详情页
        step("root-2") { toRoot() }
        step("07-detail") {
            rule.onRoot().performTouchInput { swipeUp() }
            Thread.sleep(2_500)
            rule.onRoot().performTouchInput { click(percentOffset(0.18f, 0.42f)) }
            Thread.sleep(15_000)
            shot("07-detail")
        }

        step("root-3") { toRoot() }
        step("08-download") {
            clickExact("下载")
            Thread.sleep(3_000)
            shot("08-download")
        }

        step("09-settings") {
            clickExact("设置")
            Thread.sleep(3_000)
            shot("09-settings")
        }

        // 登录 / 注册页（设置页身份位上的金色胶囊）
        step("10-login") {
            clickContains("登录 / 注册")
            Thread.sleep(4_000)
            shot("10-login")
        }

        if (failures.isNotEmpty()) error("部分步骤失败：\n$failures")
    }
}
