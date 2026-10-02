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
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 逐屏截图取证（MainActivity 内的所有页面）。
 *
 * 提速靠三件事，目的是让「复核」这件事本身能反复做（改一行 → 全量重拍 → 看）：
 *
 * 1. **本地假源**：由 `_dsx_probe\fastshots.ps1` 在**进程启动前**把
 *    `files/sources.json` + `files/class_cache_*.json` 塞进应用（run-as），
 *    指向宿主上的 mock MacCMS（`_dsx_probe\mock_server.js`，数据是真实源抓下来的
 *    fixture + 预下载海报）。聚合从「20 个远端源 × 5–10s 超时」变成 1ms 本地读盘，
 *    画面还是真实片名/真实海报。
 *    ⚠️ 必须在进程启动前塞：`SourceHealth.init` 会在 Application.onCreate 时按当时的
 *    源列表探测并把结果写进 prefs，晚一步就会被真实源盖掉。
 *
 * 2. **条件等待**：全部 `waitUntil` 等语义节点出现，不再 `Thread.sleep(11_000)` 硬等。
 *    上一版一跑 ~110s 全是 sleep。
 *
 * 3. **`am instrument` 直跑**：绕开 Gradle 的 assemble/install 环节，改完重拍只要十几秒。
 *
 * 存 MediaStore 的 Pictures/ivanshots/：应用私有目录在 Android 11+ 对 adb shell 不可读，
 * 且测试跑完 Gradle 会卸载应用、私有目录会被一并删掉。
 * 取回：`adb pull /sdcard/Pictures/ivanshots/`
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val failures = StringBuilder()
    private var captured = 0

    private fun step(name: String, block: () -> Unit) {
        runCatching(block).onFailure {
            failures.append("[$name] ${it.message}\n")
            println("SHOT-FAIL $name :: ${it.message}")
        }
    }

    /** 等某个文案出现（含子串）。数据驱动的等待，比 sleep 快也稳。 */
    private fun waitText(text: String, timeoutMs: Long = 20_000) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 等某个文案消失。只对**条件挂载**的层有效（如搜索覆盖层的 searchLayerMounted）。 */
    private fun waitGone(text: String, timeoutMs: Long = 20_000) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * 等推入页到位。
     *
     * 不能用 waitGone 判上一屏的文案消失：MainActivity 的推入栈把所有页都留在组合树里
     * （只把非栈顶的 translationX 推到 -width），所以上一屏的节点永远在语义树里，
     * waitGone 必然超时 —— 之前 03/08/11~14 六步全挂就是这个原因。
     *
     * 改用「返回」胶囊：每个二级页都有且只有一个，根页一个都没有；再配合 boundsInRoot
     * 落在窗口内，确保是"已经滑到位"而不是"还在从右边滑进来"。
     */
    private fun waitPushed(timeoutMs: Long = 15_000) {
        val w = rule.activity.resources.displayMetrics.widthPixels.toFloat()
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithContentDescription("返回").fetchSemanticsNodes().any { n ->
                val b = n.boundsInRoot
                b.width > 0f && b.left >= 0f && b.right <= w
            }
        }
    }

    /** 等这批片名里任意一个出现（首页首条是哪部取决于源的返回顺序，别赌单一名）。 */
    private fun waitAny(names: List<String>, timeoutMs: Long = 30_000) {
        rule.waitUntil(timeoutMs) {
            names.any {
                rule.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
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
        captured++
        println("SHOT-OK $name")
    }

    /**
     * 文案节点是否真的落在窗口内。
     *
     * 「存在于语义树」和「能点到」是两回事：滑出视口的节点仍在树里，
     * 此时 performClick 会抛 "Failed to inject touch input"，
     * 而且这个异常会连带把 Activity 一起带走 —— 后面每一步都变成
     * "No compose hierarchies found"，一次抖动毁掉整轮（踩过两次）。
     */
    private fun onScreen(text: String, exact: Boolean): Boolean {
        val w = rule.activity.resources.displayMetrics.widthPixels.toFloat()
        val h = rule.activity.resources.displayMetrics.heightPixels.toFloat()
        return rule.onAllNodesWithText(text, substring = !exact).fetchSemanticsNodes().any { n ->
            val b = n.boundsInRoot
            b.width > 0f && b.height > 0f &&
                b.left >= 0f && b.top >= 0f && b.right <= w && b.bottom <= h
        }
    }

    /** 点之前先确认它进了视口；不在就回顶再等一次，然后才点。 */
    private fun clickReady(text: String, exact: Boolean, timeoutMs: Long = 12_000) {
        if (!runCatching { rule.waitUntil(timeoutMs) { onScreen(text, exact) } }.isSuccess) {
            toTop()
            rule.waitUntil(timeoutMs) { onScreen(text, exact) }
        }
        rule.onAllNodesWithText(text, substring = !exact).onFirst().performClick()
    }

    private fun clickExact(text: String) = clickReady(text, exact = true)

    private fun clickContains(text: String) = clickReady(text, exact = false)

    /**
     * 依次尝试点这批文案里第一个能点到的。首页首条是哪部取决于源返回顺序，
     * 而"哪张卡片在视口内且可点"又取决于滚动位置 —— 写死一个片名会脆。
     * 返回真正点中的那个。
     */
    private fun clickAny(names: List<String>): String {
        for (n in names) {
            if (rule.onAllNodesWithText(n, substring = true).fetchSemanticsNodes().isEmpty()) continue
            if (!onScreen(n, exact = false)) continue
            runCatching { rule.onAllNodesWithText(n, substring = true).onFirst().performClick() }
                .onSuccess { return n }
        }
        error("没有一个「在视口内且可点」的片名命中：$names")
    }

    private fun back() {
        rule.activityRule.scenario.onActivity {
            it.onBackPressedDispatcher.onBackPressed()
        }
        rule.waitForIdle()
        Thread.sleep(360)   // 让转场跑完（转场时长 300ms，这里只等一次，不是每屏硬等）
    }

    /**
     * 回列表顶部。
     * 首页顶栏（搜索框 + 筛选）和「更多 ›」都是 LazyColumn 里的**普通 item**，不是
     * stickyHeader —— 滑上去就滚出视口了。此时 performClick 会抛
     * "Failed to inject touch input"（节点还在语义树里，但落点在屏幕外），
     * 而且这个异常会把 Activity 一起带走，后面每一步都变成 "No compose hierarchies found"。
     */
    private fun toTop(times: Int = 3) {
        repeat(times) {
            rule.onRoot().performTouchInput { swipeDown() }
            rule.waitForIdle()
        }
        Thread.sleep(500)
    }

    @Test
    fun captureAll() {
        // 首页首条是哪部取决于 mock 源的返回顺序，取 fixture 前几部任意命中即可
        val anyMovie = listOf("地下兵工厂", "失控劫案", "团圆令", "外星人揭秘日", "杀戮轮回")

        // ① 首页：等真实条目渲染出来再拍（而不是 sleep 16s）
        step("01-home") {
            waitAny(anyMovie)
            // 数据到 ≠ 封面到。Coil 是异步的，刚拿到列表时卡片还是占位砖，
            // 拍早了整屏海报全空，会让人误判成"封面加载有 bug"。
            Thread.sleep(2_500)
            shot("01-home")
        }

        // ② 首页滚动。放在最前面拍：它会把顶栏和「更多 ›」一起滚出视口，
        //    后面所有步骤都得先 toTop() 才点得到。
        step("02-home-scrolled") {
            rule.onRoot().performTouchInput { swipeUp() }
            rule.waitForIdle()
            Thread.sleep(1_500)
            shot("02-home-scrolled")
        }

        // ③ 分类页：首页「更多 ›」推入。
        //    必须精确匹配 —— 页脚还有「还在加载更多源…」，用 substring 会先命中它。
        step("03-category") {
            toTop()
            clickExact("更多 ›")
            waitPushed()
            Thread.sleep(1_500)
            shot("03-category")
        }
        step("03b-category-back") { back() }

        // ③b 动漫子专栏：首页切到「动漫」→ 更多 › → 分类页里的 国漫/日漫 chip
        step("03c-anime-cn") {
            toTop()
            clickExact("动漫")
            Thread.sleep(1_200)      // 切 tab 后要重新聚合
            toTop()                  // 切 tab 会重排列表，重新回顶再点「更多 ›」
            clickExact("更多 ›")
            waitPushed()
            waitText("国漫", 15_000)  // chip 行渲染出来才算到位
            Thread.sleep(400)
            clickExact("国漫")
            // 换专栏会重走一遍「分类映射 → 拉列表」，光等固定时长会拍到骨架屏
            waitAny(listOf("无上神帝", "南派盗墓往事", "潘金莲", "全球御鬼时代"), 20_000)
            // 热度榜是另一条异步线（首次要打 B 站接口，耗时不定）。用"排序提示文案出现"
            // 作为它落地的判据 —— 固定 sleep 会拍到还没排序的中间态，看起来像功能没生效。
            // 取不到榜时提示文案是另一句，这里不把它当失败，照常拍。
            runCatching { waitText("按热度排序", 25_000) }
            Thread.sleep(1_500)      // 再等封面
            shot("03c-anime-cn")
        }
        step("03d-anime-jp") {
            clickExact("日漫")
            waitAny(listOf("冰之城墙", "桃源暗鬼", "与你相恋到生命尽头", "转生成为魔剑了", "封天契"), 20_000)
            runCatching { waitText("按热度排序", 25_000) }
            Thread.sleep(1_500)
            shot("03d-anime-jp")
        }
        step("03e-anime-back") {
            back()
            // 首页还停在「动漫」tab，必须切回电影：后面 08-detail 是按电影片名找卡片的
            clickExact("电影")
            Thread.sleep(1_500)
        }

        // ④ 筛选页：从首页顶栏「筛选」进（搜索层里也有个「筛选」，从首页进只有唯一节点）
        step("04-filter") {
            toTop()
            clickExact("筛选")
            waitText("排序", 15_000)
            Thread.sleep(800)
            shot("04-filter")
        }
        step("04b-filter-back") { back() }

        // ④ 搜索：覆盖层。占位文案节点的可点区域算不出来，按位置点顶栏更稳。
        step("05-search-initial") {
            rule.onRoot().performTouchInput { click(percentOffset(0.5f, 0.055f)) }
            waitText("取消", 15_000)
            Thread.sleep(700)   // 覆盖层揭示动画 560ms
            shot("05-search-initial")
        }

        // ⑤ 搜索结果：关键词取 fixture 里真实存在的片名片段
        step("06-search-results") {
            rule.onNode(hasSetTextAction()).performTextInput("宇宙")
            waitText("宇宙护卫队", 20_000)
            Thread.sleep(1_500)   // 等封面
            shot("06-search-results")
        }

        // ⑥ 搜索无结果态
        step("07-search-empty") {
            rule.onNode(hasSetTextAction()).performTextClearance()
            rule.onNode(hasSetTextAction()).performTextInput("zzz不存在zzz")
            waitText("没有找到", 20_000)
            Thread.sleep(300)
            shot("07-search-empty")
        }

        // 关搜索层，回首页
        step("07b-close-search") {
            rule.onNode(hasSetTextAction()).performTextClearance()
            Thread.sleep(300)
            clickExact("取消")
            waitGone("取消", 15_000)
        }

        // ⑦ 详情页：直接点 Banner 的片名（Banner 在首屏固定位置、可点，
        //    比"滚动后按坐标点卡片"稳 —— 后者会碰到卡片只露出半个、点不进去）
        step("08-detail") {
            toTop()
            val hit = clickAny(anyMovie)
            println("SHOT-NOTE detail via $hit")
            waitPushed()
            Thread.sleep(2_500)   // 详情页要拉多源线路 + 英雄区大图
            shot("08-detail")
        }
        step("08b-detail-back") { back() }

        // ⑧ 追剧（根页 tab 1）
        step("09-follow") {
            clickExact("追剧")
            Thread.sleep(900)
            shot("09-follow")
        }

        // ⑨ 我的（根页 tab 2）
        step("10-mine") {
            clickExact("我的")
            Thread.sleep(800)
            shot("10-mine")
        }

        // ⑩ 登录 / 注册
        step("11-login") {
            clickContains("登录 / 注册")
            waitPushed()
            Thread.sleep(900)
            shot("11-login")
        }
        step("11b-login-back") { back() }

        // ⑪ 片单收藏
        step("12-favorites") {
            clickExact("片单收藏")
            waitPushed()
            Thread.sleep(900)
            shot("12-favorites")
        }
        step("12b-fav-back") { back() }

        // ⑫ 下载
        step("13-downloads") {
            clickExact("下载")
            waitPushed()
            Thread.sleep(1_400)   // 下载页有 1200ms 轮询才出真实空态
            shot("13-downloads")
        }
        step("13b-dl-back") { back() }

        // ⑬ 设置
        step("14-settings") {
            clickExact("设置")
            waitPushed()
            Thread.sleep(900)
            shot("14-settings")
        }

        println("SHOT-SUMMARY captured=$captured failures=${failures.length}")
        if (failures.isNotEmpty()) error("部分步骤失败：\n$failures")
    }
}
