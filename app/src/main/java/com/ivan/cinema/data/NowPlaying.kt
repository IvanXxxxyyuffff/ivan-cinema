package com.ivan.cinema.data

import androidx.compose.runtime.mutableStateOf
import com.ivan.cinema.db.WatchEntry

/**
 * 「正在播放」的进程级状态。
 *
 * 播放页退出后把它记下来，根页据此显示迷你播放条 —— 从影片退出来之后能一眼看到
 * 刚才在看什么、并且一键回到播放位置，不用重新找片、重新选集。
 *
 * 只存在内存里：进程被杀掉之后没有「正在播放」是合理的，观看记录本身已经落盘。
 */
object NowPlaying {

    val entry = mutableStateOf<WatchEntry?>(null)

    /** 播放中/退出时调用。只记录有意义的进度，避免 0 秒的空条目占着位置。 */
    fun update(e: WatchEntry) {
        if (e.vodKey.isBlank()) return
        if (e.durationMs <= 0) return
        entry.value = e
    }

    fun clear() {
        entry.value = null
    }
}
