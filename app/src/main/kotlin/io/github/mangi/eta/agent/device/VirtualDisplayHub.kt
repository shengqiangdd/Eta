package io.github.mangi.eta.agent.device

import java.util.LinkedHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 所有虚拟屏会话的共享入口：App 内预览面板与截图都走这里。
 *
 * 支持**多会话并存**（两个对话各建一块屏很常见）：
 * - 每个会话记下自己的 controller（不同运行可能用不同实例）；
 * - 面板显示最后创建、仍存活的那块屏；
 * - 释放当前屏时自动回退到上一块，而不是直接消失。
 */
internal object VirtualDisplayHub {
    data class SessionInfo(
        val id: String,
        val displayId: Int,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val purpose: String,
    )

    private data class Entry(val controller: VirtualDisplayController, val info: SessionInfo)

    /** 插入顺序 = 创建顺序；最后一项就是面板显示的屏。 */
    private val entries = LinkedHashMap<String, Entry>()
    private val active = MutableStateFlow<SessionInfo?>(null)
    val session: StateFlow<SessionInfo?> = active.asStateFlow()

    @Synchronized
    fun register(controller: VirtualDisplayController, session: VirtualDisplayController.Session) {
        entries[session.id] = Entry(
            controller = controller,
            info = SessionInfo(
                id = session.id,
                displayId = session.displayId,
                width = session.width,
                height = session.height,
                densityDpi = session.densityDpi,
                purpose = session.purpose,
            ),
        )
        active.value = entries.values.last().info
    }

    @Synchronized
    fun unregister(id: String) {
        entries.remove(id)
        // 释放当前屏后回退到上一块仍存活的屏，而不是让面板直接消失。
        active.value = entries.values.lastOrNull()?.info
    }

    /** 取当前展示屏最新一帧的 PNG；没有会话时返回 null。 */
    @Synchronized
    fun capture(): ByteArray? {
        val entry = entries.values.lastOrNull() ?: return null
        return runCatching { entry.controller.capturePng(entry.info.id, timeoutMs = 400) }.getOrNull()
    }
}
