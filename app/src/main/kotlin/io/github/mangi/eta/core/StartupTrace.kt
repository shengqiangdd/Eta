package io.github.mangi.eta.core

import android.os.Process
import android.os.SystemClock

/**
 * 启动耗时打点。
 *
 * 两套口径，日志里同时给出，避免把「进程存活时长」误当成「启动耗时」：
 * - 进程口径：从 [Process.getStartUptimeMillis] 起算。Application 阶段还没有 Activity，
 *   只能用它（app-onCreate / app-ready 可直接相减）。
 * - 界面口径：从 [markActivityStart] 起算。content-composed / first-frame 用它。
 *
 * 为什么要分开：进程可能先在后台存活很久（通知、定时任务、上次没被回收），第一次显示界面
 * 时进程口径会给出巨大数字 —— 实测出现过 `first-frame +3465080ms`（相当于后台存活 57 分钟），
 * 而同一份代码真正冷启动是 `+2867ms`。只看进程口径会把两者混为一谈。
 *
 * 排查启动慢时：`logcat -d | grep "startup "`。
 *
 * 日志把两个口径**写成不同字段名**（`ui=` / `process=`）：早期写法是
 * `first-frame +174ms (process +3026ms)`，括号里的数字被当成启动耗时，误判成「冷启 3 秒」——
 * 实际那 2.8 秒是进程在用户点开之前就已经在后台存活的时间。`activity-created` 一行专门记下
 * 「进程 fork 到 Activity 创建」这段间隔，出现大数字时一眼能看出不是启动慢。
 */
internal object StartupTrace {
    private val processStart = Process.getStartUptimeMillis()

    /** 本次 Activity.onCreate 的时刻；0 表示还没进入 Activity 阶段。 */
    @Volatile
    private var activityStart: Long = 0L

    /** MainActivity.onCreate 调用，作为「界面启动」口径的起点。 */
    fun markActivityStart() {
        activityStart = SystemClock.uptimeMillis()
        mark("activity-created")
    }

    fun mark(label: String) {
        val now = SystemClock.uptimeMillis()
        val fromProcess = now - processStart
        val startedAt = activityStart
        if (startedAt <= 0L) {
            AndroidAgentLogger.infoPersisted("startup $label process=+${fromProcess}ms")
            return
        }
        AndroidAgentLogger.infoPersisted(
            "startup $label ui=+${now - startedAt}ms process=+${fromProcess}ms"
        )
    }
}
