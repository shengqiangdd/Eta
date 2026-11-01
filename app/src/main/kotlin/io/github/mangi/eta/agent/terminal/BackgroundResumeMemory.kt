package io.github.mangi.eta.agent.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue

/**
 * 「已经续过的后台任务」与「自动续了几次」的进程级记忆。
 *
 * 为什么不用卡片里的 `remember`：真机实测（2026-11-01，canary）点过「继续」、一轮跑完之后，
 * 那个 `remember` 状态丢了 —— 按钮又出现，「同一条不再续」和「最多 2 次」两条防线同时失效。
 * 把状态放到进程级对象里，才和卡片文档里写的「进程内生效、重启清零」对得上。
 *
 * 仍然**不是**持久化：进程被回收就清零（那时卡片打开之前结束的任务本来也不会被自动续）。
 * 也**不按会话区分**：记录里没有会话归属，同一条 id 在本进程任何会话里都算「续过」。
 */
internal object BackgroundResumeMemory {

    /** 只记最近这么多条，避免长时间运行后无限增长。 */
    private const val MaxRemembered = 64

    /** 已续过的任务 id（手动点「继续」也会记进来）。 */
    val resumed = mutableStateListOf<String>()

    /** 本进程已自动续跑的次数；上限是 [AutoResumeMaxPerSession]。 */
    var autoResumed by mutableIntStateOf(0)
        private set

    fun markResumed(id: String) {
        if (id.isBlank() || id in resumed) return
        resumed.add(id)
        while (resumed.size > MaxRemembered) resumed.removeAt(0)
    }

    fun markAutoResumed(id: String) {
        markResumed(id)
        autoResumed += 1
    }

    fun reset() {
        resumed.clear()
        autoResumed = 0
    }
}
