package com.ivan.cinema

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ivan.cinema.player.PlayerActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 播放页截图。
 *
 * PlayerActivity 是**独立 Activity**，`createAndroidComposeRule<MainActivity>()` 抓不到它。
 * 用 `createEmptyComposeRule()`：它自己不启动任何 Activity，专门配合"手动启动的 Activity"，
 * 于是仍然能走 `onRoot().captureToImage()` —— 这条 CPU 绘制路径已被验证可用
 * （用 `view.draw(Canvas)` 会全黑，播放器的硬件图层画不出来）。
 *
 * 给一个公开测试流走 directUrl，跳过源解析，直接进入播放器 UI。
 */
@RunWith(AndroidJUnit4::class)
class PlayerScreenshotTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    @Test
    fun capturePlayer() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(ctx, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("vodKey", "screenshot-probe")
            putExtra("name", "测试影片")
            putExtra("pic", "")
            putExtra("sourceApi", "")
            putExtra("sourceName", "")
            putExtra("vodId", "")
            putExtra("lineIndex", 0)
            putExtra("episodeIndex", 0)
            putExtra("resume", false)
            putExtra("directUrl", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
        }

        ActivityScenario.launch<PlayerActivity>(intent).use {
            Thread.sleep(10_000)
            // 控制层 4 秒后会自动隐藏，先点一下把它唤出来再抓
            runCatching {
                rule.onRoot().performTouchInput {
                    click(androidx.compose.ui.geometry.Offset(center.x, center.y))
                }
            }
            Thread.sleep(1_500)
            grab(ctx, "11-player-controls")

            // 再等，抓自动隐藏后的干净画面
            Thread.sleep(8_000)
            grab(ctx, "12-player-clean")
        }
    }

    private fun grab(ctx: Context, name: String) {
        runCatching {
            rule.waitForIdle()
            val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
            save(ctx, name, bmp)
        }
    }

    private fun save(ctx: Context, name: String, bmp: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/ivanshots"
            )
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert 失败")
        ctx.contentResolver.openOutputStream(uri)!!.use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
