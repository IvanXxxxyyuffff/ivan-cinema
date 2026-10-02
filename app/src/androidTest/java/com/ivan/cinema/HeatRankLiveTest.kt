package com.ivan.cinema

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ivan.cinema.data.HeatRank
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机/模拟器上的 HeatRank 实时验证：直接调 load()，把三平台合成结果打进 logcat。
 * 目的不是断言名次（榜单每天变），而是证明三条采集链路在 **Android 运行时**都能解析出内容
 * （B站 JSON、爱奇艺 __NUXT__ 正则、腾讯 getPage JSON）。跑法见 _dsx_probe/HARNESS.md。
 */
@RunWith(AndroidJUnit4::class)
class HeatRankLiveTest {

    @Test
    fun dumpLiveRanks() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        for (kind in HeatRank.Kind.entries) {
            val list = runCatching { HeatRank.load(ctx, kind) }.getOrElse { emptyList() }
            android.util.Log.i(
                "HeatRankLive",
                "${kind.name} n=${list.size} top12=${list.take(12)}"
            )
            android.util.Log.i(
                "HeatRankLive",
                "${kind.name} 归一化后前 12=${list.take(12).map { HeatRank.normalize(it) }}"
            )
        }
        // 归一化单测（不依赖网络）：确认季/动态漫/括号噪音都能被剥掉
        val cases = listOf(
            "牧神记 动态漫" to "牧神记",
            "冰之城墙 第2季" to "冰之城墙",
            "斗罗大陆Ⅱ· 更新" to "斗罗大陆ⅱ",
            "开心锤锤（国语版）" to "开心锤锤"
        )
        for ((raw, want) in cases) {
            val got = HeatRank.normalize(raw)
            android.util.Log.i("HeatRankLive", "normalize('$raw') = '$got'  ${if (got == want) "OK" else "WANT '$want'"}")
        }
    }
}
