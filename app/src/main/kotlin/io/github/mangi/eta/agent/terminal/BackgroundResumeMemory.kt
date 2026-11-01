package io.github.mangi.eta.agent.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.agent.runtime.BackgroundResumeState
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
 * 三条状态的生命周期（第十二批改过）：
 * - [resumed] **落盘**（重启后仍记得「这条续过」），否则重启就等于把用户的点击忘掉；
 * - [autoResumed] **也落盘**，但语义是「当前这条自动续跑链用了几次」：用户主动发一条消息
 *   就清零。原先只在进程内，进程被杀就归零 —— 那不是「保护循环」，而是「重启即可绕过上限」。
 * - [seenUntil] **落盘**：事件水位线。没有它，重启前刚结束、还没处理的任务会在重启后丢失
 *   （旧实现用「进程启动时刻」当水位线，正好把这类任务挡在外面）。
 */
internal class BackgroundResumeScope(
    private val store: BackgroundResumeStore?,
    private val scopeId: String,
) {

    /** 已续过的任务 id（手动点「继续」也算）。 */
    val resumed = mutableStateListOf<String>()

    /** 本作用域当前这条链已自动续跑的次数；上限是 [AutoResumeMaxPerChain]。 */
    var autoResumed by mutableIntStateOf(0)
        private set

    /** 事件水位线：这个作用域里已经处理到哪一刻的结束事件；null 表示还没立过。 */
    var seenUntil by mutableStateOf<Long?>(null)
        private set

    /** 磁盘记忆是否已经读过。读之前不能判「没续过」，否则会自动续一条已经续过的任务。 */
    var isLoaded by mutableStateOf(false)
        private set

    // 内存在磁盘之前被改过时，加载不能把这些改动盖回去（否则 watcher 先立水位线、再加载，
    // seenUntil 会被磁盘上的旧值冲掉，同一批任务会被重放一遍）。
    private var chainTouched = false
    private var seenUntilTouched = false

    /**
     * 从磁盘恢复。只做一次；读失败按「没有记录」处理，不阻塞界面。
     *
     * 缺字段按默认值读：升级前的老文件只有 `resumed`，不能因为缺 `chain` / `seen_until`
     * 就把「哪些续过」一起丢掉。
     */
    suspend fun loadFromStore() {
        if (isLoaded) return
        val state = withContext(Dispatchers.IO) { store?.loadState(scopeId) } ?: BackgroundResumeState()
        state.resumed.forEach { remember(it) }
        if (!chainTouched) autoResumed = state.chain
        if (!seenUntilTouched) seenUntil = state.seenUntil
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
        chainTouched = true
    }

    /**
     * 用户在场（发了一条消息）：这条自动续跑链到此为止。
     *
     * 先加载再清零 —— 未加载就写盘会把磁盘上的 `resumed` 与 `seenUntil` 一起抹成空。
     * 返回 true 表示真的写了盘（链本来就是 0 时没有写盘的必要）。
     */
    suspend fun resetChain(): Boolean {
        loadFromStore()
        if (autoResumed == 0) return false
        autoResumed = 0
        chainTouched = true
        persist()
        return true
    }

    /**
     * 推进事件水位线；只前进不后退。
     *
     * 返回 true 表示水位线变了（调用方据此决定要不要落盘）。
     */
    fun markSeenUntil(at: Long): Boolean {
        if (at <= 0) return false
        val current = seenUntil
        if (current != null && current >= at) return false
        seenUntil = at
        seenUntilTouched = true
        return true
    }

    /**
     * 重建水位线：**允许后退**，只给「时钟回拨」用（第十三批）。
     *
     * [markSeenUntil] 只前进，它守的是「事件不重放」；但时钟回拨之后，「前进」的方向
     * 本身就是错的 —— 拒绝后退会让自动续跑永久停在失效状态。
     * 调用方必须先吸收「比现在还晚」的记录，否则水位线后退会让它们变成候选。
     *
     * 返回 true 表示水位线变了（调用方据此决定要不要落盘）。
     */
    fun reestablishSeenUntil(at: Long): Boolean {
        if (at <= 0) return false
        if (seenUntil == at) return false
        seenUntil = at
        seenUntilTouched = true
        return true
    }

    /** 把当前状态写回磁盘；写失败只影响下次启动的记忆，不影响这次判定。 */
    suspend fun persist() {
        val state = BackgroundResumeState(
            resumed = resumed.toList(),
            chain = autoResumed,
            seenUntil = seenUntil,
        )
        withContext(Dispatchers.IO) { store?.saveState(scopeId, state) }
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
