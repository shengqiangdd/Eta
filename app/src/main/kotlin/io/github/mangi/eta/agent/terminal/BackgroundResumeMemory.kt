package io.github.mangi.eta.agent.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.agent.runtime.BackgroundResumeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一个会话作用域里「哪些后台任务已经续过」。
 *
 * 为什么不用卡片里的 `remember`：真机实测（2026-11-01，canary）点过「继续」、一轮跑完之后，
 * 那个 `remember` 状态丢了 —— 按钮又出现，「同一条不再续」和「最多 2 次」两条防线同时失效。
 *
 * 为什么按作用域分开：记录本身没有会话归属（见 [DetachedTask]），但「在 A 会话里续过」
 * 不该让 B 会话少显示一个按钮。作用域键与台账、目标、待办一致（`conversationId ?: runId`）。
 *
 * 两条状态的生命周期刻意不同：
 * - [resumed] **落盘**（重启后仍记得「这条续过」），否则重启就等于把用户的点击忘掉；
 * - [autoResumed] **只在进程内**：它是「这一轮对话自动续跑的预算」，落盘会变成超一次就永久停用。
 */
internal class BackgroundResumeScope(
    private val store: BackgroundResumeStore?,
    private val scopeId: String,
) {

    /** 已续过的任务 id（手动点「继续」也算）。 */
    val resumed = mutableStateListOf<String>()

    /** 本进程、本作用域已自动续跑的次数；上限是 [AutoResumeMaxPerSession]。 */
    var autoResumed by mutableIntStateOf(0)
        private set

    /** 磁盘记忆是否已经读过。读之前不能判「没续过」，否则会自动续一条已经续过的任务。 */
    var isLoaded by mutableStateOf(false)
        private set

    /** 从磁盘恢复。只做一次；读失败按「没有记录」处理，不阻塞界面。 */
    suspend fun loadFromStore() {
        if (isLoaded) return
        val stored = withContext(Dispatchers.IO) { store?.load(scopeId).orEmpty() }
        stored.forEach { remember(it) }
        isLoaded = true
    }

    /** 记下「这条续过了」；返回 true 表示状态真的变了（调用方据此决定要不要落盘）。 */
    fun markResumed(id: String): Boolean {
        if (id.isBlank() || id in resumed) return false
        remember(id)
        return true
    }

    fun markAutoResumed(id: String) {
        markResumed(id)
        autoResumed += 1
    }

    /** 把当前集合写回磁盘；写失败只影响下次启动的记忆，不影响这次判定。 */
    suspend fun persist() {
        val ids = resumed.toList()
        withContext(Dispatchers.IO) { store?.save(scopeId, ids) }
    }

    private fun remember(id: String) {
        resumed.add(id)
        // 与落盘上限同一个数：内存里留得比磁盘多，重启后就会「变回没续过」。
        while (resumed.size > BackgroundResumeStore.MAX_IDS) resumed.removeAt(0)
    }
}

/**
 * 作用域 → [BackgroundResumeScope] 的进程级表。
 *
 * 放在进程级是为了扛住「组合被丢弃」：卡片重建、页面切走、App 回到前台，
 * 内存里的这份状态都还在（真机实测 `remember` 扛不住）。
 * 表不回收：每个会话一个对象，每个对象最多 64 个 id，量级可接受；进程结束即清空。
 */
internal object BackgroundResumeMemory {

    private val scopes = HashMap<String, BackgroundResumeScope>()

    fun scopeFor(store: BackgroundResumeStore?, scopeId: String): BackgroundResumeScope =
        synchronized(scopes) {
            scopes.getOrPut(scopeId) { BackgroundResumeScope(store, scopeId) }
        }

    /** 只给测试用：清掉所有作用域，避免用例之间互相污染。 */
    fun reset() {
        synchronized(scopes) { scopes.clear() }
    }
}
