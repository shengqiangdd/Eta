package io.github.mangi.eta.agent.tool

/**
 * `virtual_display_list` 里「前台进程已经不在了」的说明；不需要提示时返回 null。
 *
 * 单独抽出来是为了能被单测覆盖：任务栈里留着记录、进程却已经没了（崩溃或被系统回收）
 * 这种状态在真机上很难稳定重现——杀掉进程通常连任务记录一起被系统清掉，
 * 于是「工具说前台还活着」这类回归只能靠纯函数测试守住。
 */
internal fun virtualDisplayForegroundNote(packageName: String, processAlive: Boolean?): String? {
    if (processAlive != false) return null
    return "任务栈里还有 $packageName 的记录，但它的进程已经不在了（崩溃或被系统回收）：界面不会更新；" +
        "用 virtual_display_launch 重新拉起，或 virtual_display_release 释放"
}

/**
 * 屏上没有任何前台任务时的说明。
 *
 * 真机实测：在虚拟屏上 `kill -9` 应用后，系统会把该任务从这块屏上直接移除
 * （`task_count` 变 0、前台包名变空），但输出里原本什么提示都没有——
 * 模型会以为屏上跑着的应用还在，接着去截图或点击。
 *
 * `tasksKnown=false` 是另一种情况：任务栈压根没查到（root 不可用或 `am stack list` 超时），
 * 此时 `task_count=0` 不代表没有任务，必须说清楚。
 */
internal fun virtualDisplayEmptyNote(tasksKnown: Boolean): String = if (!tasksKnown) {
    "任务栈查询失败（root 不可用或命令超时）：无法确认这块屏上是否还有任务，task_count=0 不代表没有任务"
} else {
    "这块虚拟屏上当前没有任何任务：应用可能已被系统迁回主屏、被清理或已退出，界面不会再更新；" +
        "需要继续操作请用 virtual_display_launch 重新启动，或 virtual_display_release 释放"
}
