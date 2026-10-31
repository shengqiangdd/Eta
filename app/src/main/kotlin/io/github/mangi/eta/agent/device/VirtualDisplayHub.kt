package io.github.mangi.eta.agent.device

import java.util.LinkedHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 所有虚拟屏会话的共享入口：App 内预览面板、截图与跨运行管理都走这里。
 *
 * 为什么要有这一层：`VirtualDisplayController` 是**每次运行**一个实例（工具在 run 初始化时新建），
 * 但它建出来的屏是系统资源，运行结束、进程被杀都还在。于是「谁建的、归谁、还能不能管」
 * 不能只靠 controller 自己的 map —— 那个 map 只有本运行建的屏，新运行看不到旧会话，
 * 释放旧屏会静默「成功」而屏还在。
 *
 * 约定：
 * - 每块屏登记时带 owner（会话 id / 运行 id），跨运行也能回答「这块屏是哪个对话的」；
 * - 面板按会话取屏（[lastOwnedBy]），不把别的对话的屏顶到用户眼前；
 * - 释放走 [release]，由登记时记下的 controller 执行。
 */
internal object VirtualDisplayHub {
    data class SessionInfo(
        val id: String,
        val displayId: Int,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val purpose: String,
        /** 建这块屏的会话 id；空串表示当时没有会话（语音 / 一次性入口）。 */
        val ownerConversationId: String = "",
        /** 建这块屏的运行 id；没有会话时用它区分归属，也用于诊断。 */
        val ownerRunId: String = "",
        val createdAtMs: Long = 0L,
    )

    private data class Entry(val controller: VirtualDisplayController, val info: SessionInfo)

    /** 插入顺序 = 创建顺序。 */
    private val entries = LinkedHashMap<String, Entry>()
    private val active = MutableStateFlow<SessionInfo?>(null)
    private val all = MutableStateFlow<List<SessionInfo>>(emptyList())

    /** 面板默认展示的屏：最后创建、仍存活的那块。 */
    val session: StateFlow<SessionInfo?> = active.asStateFlow()

    /** 全部存活会话，按创建顺序；UI 想按会话过滤就用它。 */
    val sessions: StateFlow<List<SessionInfo>> = all.asStateFlow()

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
                ownerConversationId = session.ownerConversationId,
                ownerRunId = session.ownerRunId,
                createdAtMs = session.createdAtMs,
            ),
        )
        publish()
    }

    @Synchronized
    fun unregister(id: String) {
        entries.remove(id)
        publish()
    }

    /** 当前存活的全部会话（跨运行、跨对话）。 */
    @Synchronized
    fun list(): List<SessionInfo> = entries.values.map { it.info }

    @Synchronized
    fun find(id: String): SessionInfo? = entries[id]?.info

    /** 取登记方的实时会话（含 idle / 内存估算）；屏已不在时返回 null。 */
    @Synchronized
    fun liveSession(id: String): VirtualDisplayController.Session? = entries[id]?.controller?.peek(id)

    /**
     * 某个对话拥有的最后一块屏（面板用）。
     *
     * 同一对话跨多轮可能建过几块，取最新的那块 —— 用户该看到的是最新在跑的那块。
     */
    @Synchronized
    fun lastOwnedBy(conversationId: String): SessionInfo? {
        if (conversationId.isEmpty()) return null
        return entries.values.lastOrNull { it.info.ownerConversationId == conversationId }?.info
    }

    /**
     * 释放一块屏，由**登记它的** controller 执行。
     *
     * 返回 false 表示屏还在（例如屏上仍有任务没清掉），调用方应把原因报给用户，
     * 不要笼统说「释放成功」。
     */
    @Synchronized
    fun release(id: String): Boolean {
        val entry = entries[id] ?: return true
        val released = runCatching { entry.controller.release(id) }.getOrDefault(false)
        if (released) {
            // controller.release 成功时会回调 unregister；这里兜底一次，保证 UI 状态不残留。
            entries.remove(id)
            publish()
        }
        return released
    }

    /** 取当前展示屏最新一帧的 PNG；没有会话时返回 null。 */
    @Synchronized
    fun capture(): ByteArray? {
        val entry = entries.values.lastOrNull() ?: return null
        return runCatching { entry.controller.capturePng(entry.info.id, timeoutMs = 400) }.getOrNull()
    }

    /** 按屏 id 截一帧（面板跟随当前对话时用）；屏已不在时返回 null。 */
    @Synchronized
    fun captureOf(id: String): ByteArray? {
        val entry = entries[id] ?: return null
        return runCatching { entry.controller.capturePng(id, timeoutMs = 400) }.getOrNull()
    }

    private fun publish() {
        val list = entries.values.map { it.info }
        all.value = list
        active.value = list.lastOrNull()
    }
}
