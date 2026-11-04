package io.github.mangi.eta.agent.terminal

/**
 * 后台任务该不该出现在某个会话的视野里。
 *
 * 背景：后台任务的记录是**一份全局文件**（`terminal-daemons.json`），
 * 而旧实现 `list()` 不做任何过滤 —— 于是每个会话都能看到同一批任务。
 * 后果不是「信息多了」那么轻松：
 *
 *  - 你在会话 A 的面板里看到会话 B 派的活，会以为那是自己的；
 *  - 按 id 去 `stop` / `read`，操作的是别人的任务；
 *  - 排查问题时把 B 的日志当成 A 的结论。
 *
 * 但也不能一律拒绝：排查环境问题时确实需要全局视角。
 * 所以规则分三层 —— 「我是谁」「它属于谁」「是否明确要全部」。
 *
 * 这是一个纯函数，因为它是最容易被改错、也最容易靠读代码说服自己的那部分：
 * 三个分支的优先级一旦调换，表现都是「看起来正常，但权限串了」。
 */
internal object BackgroundTaskOwnership {

    /**
     * @param taskScope 任务的归属会话；null / 空白表示没有归属（旧记录、外部直调）
     * @param viewerScope 当前会话；null / 空白表示调用方没有会话概念（语音一次性入口等）
     * @param includeOther 调用方明确要求看全部
     */
    fun visible(taskScope: String?, viewerScope: String?, includeOther: Boolean): Boolean {
        // 没有会话概念的调用方保持旧行为：它根本无从比较，限制它只会让功能坏掉。
        if (viewerScope.isNullOrBlank()) return true
        if (includeOther) return true
        // 无归属的任务对所有人可见。取这个方向是因为「升完级突然 stop 不了旧任务」
        // 比「偶尔多看到一个旧任务」更难受，而且旧记录里没有可依赖的归属信息。
        if (taskScope.isNullOrBlank()) return true
        return taskScope == viewerScope
    }
}
