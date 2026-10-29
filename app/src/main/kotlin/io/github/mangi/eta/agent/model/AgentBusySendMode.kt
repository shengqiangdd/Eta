package io.github.mangi.eta.agent.model

/**
 * 繁忙时（运行中）发送按钮的行为，对齐 dsh 的 `BusyEnterBehavior`。
 *
 * dsh 让用户在「排队」与「插话」之间选一个，Enter 与发送按钮都走所选行为：
 * - queue：把这条记下，等当前回合自然结束后再发；
 * - steer：打断当前回合，立刻把这条插进去。
 *
 * Eta 没有键盘回车提交，所以只作用于运行中 + 有草稿时点击发送按钮。
 */
internal enum class AgentBusySendMode(val id: String) {
    QUEUE("queue"),
    STEER("steer"),
    ;

    companion object {
        /** 默认排队：用户点发送的直觉是「先记下」，打断应该是显式选择。 */
        val DEFAULT: AgentBusySendMode = QUEUE

        /** 未知或空值一律回退默认：手改配置或旧版本写入不能让发送按钮行为失配。 */
        fun fromId(raw: String?): AgentBusySendMode {
            val id = raw?.trim().orEmpty()
            return values().firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}

/**
 * 本次点击是否真的走「插话」。
 *
 * 暂停态是 Eta 独有的例外：暂停时 run 还没结束，插话会把它打断，
 * 用户暂停的意图是保住现场，所以暂停中一律排队，等用户点「继续」。
 * 编辑态同理不插话——插话会先打断 run，而编辑内容本身发不出去。
 */
internal object AgentBusySendPolicy {
    fun steers(
        streaming: Boolean,
        paused: Boolean,
        editingMessage: Boolean,
        mode: AgentBusySendMode,
    ): Boolean = streaming && !paused && !editingMessage && mode == AgentBusySendMode.STEER
}
