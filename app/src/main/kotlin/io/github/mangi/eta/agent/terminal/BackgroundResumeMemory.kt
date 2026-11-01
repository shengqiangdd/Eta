package io.github.mangi.eta.agent.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.agent.runtime.BackgroundResumeState
import io.github.mangi.eta.agent.runtime.BackgroundResumeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * 用户手动点「继续」（第十八批）：记账是同步的，落盘交给进程级写入器。
     *
     * 三段都不能省，缺一个就有一类静默失败：
     * - 不记账 → 按钮不消失，同一条能点第二次；
     * - 不串行（[BackgroundResumeMemory.mutexFor]）→ 与 watcher 的整份状态写入互相覆盖，
     *   `chain` / `seenUntil` 可能被盖回旧值：重启后链上限被重置（多花额度），
     *   或者用户刚发消息清掉的链又回来了（提前停用）；
     * - 不用进程级写入器 → 卡片原来写在自己的 `rememberCoroutineScope()` 里，
     *   用户点完立刻离开会话，组合销毁就会取消它，这次点击只活在内存里，
     *   重启之后等于没点过（按钮重现，自动续跑也可能再发一条）。
     */
    fun resumeManually(id: String) {
        if (!markResumed(id)) return
        BackgroundResumeMemory.persistSoon(scopeId) { persist() }
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

    /**
     * 把当前状态写回磁盘；返回是否真的写成功。
     *
     * 失败不只是「下次启动记不住」：`seenUntil` 回退会重放已处理的任务，`chain` 回退会
     * 让上限失效 —— 两者都会花用户的额度。所以失败必须能上报（[BackgroundResumeMemory.onPersistFailed]），
     * 不能像原来那样把 `saveState` 的 Boolean 丢掉。
     */
    suspend fun persist(): Boolean {
        val target = store ?: return true
        // 整份状态写入前先读一次盘（第二十一批）：作用域可能是刚建出来的（卡片第一帧
        // 就点了「继续」），这时内存里只有刚记的那一条，整份写下去会把磁盘上的
        // `resumed` / `chain` / `seen_until` 一起抹成默认值 —— 链长归零（重启后又能
        // 自动续）、水位线丢失、以前续过的按钮重现。
        // `resetChain()` 里那句「先加载再清零」是同一条教训，放在这里才拦得住所有写者。
        if (!isLoaded) loadFromStore()
        val state = BackgroundResumeState(
            resumed = resumed.toList(),
            chain = autoResumed,
            seenUntil = seenUntil,
        )
        val saved = withContext(Dispatchers.IO) { target.saveState(scopeId, state) }
        if (!saved) BackgroundResumeMemory.onPersistFailed?.invoke(scopeId)
        return saved
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

    /**
     * 落盘失败回调（第十七批）。
     *
     * 进程级而不是构造参数：失败现场在 [BackgroundResumeScope.persist]，而日志实现
     * （`AndroidAgentLogger`）在 Android 层。用回调保持这个文件纯 JVM 可测。
     */
    @Volatile
    var onPersistFailed: ((String) -> Unit)? = null

    private val scopes = HashMap<String, BackgroundResumeScope>()
    private val locks = HashMap<String, Mutex>()

    /**
     * 进程级写入器（第十八批）。
     *
     * 界面侧的手动「继续」不能把落盘挂在自己的协程作用域上：`rememberCoroutineScope()`
     * 随组合销毁取消，用户点完就切走时那次写入会被取消掉。
     */
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 把一次状态写入排进写入器，并与 watcher、`resetChain()` 共用同一把作用域锁。
     *
     * 不返回任务：调用方（界面点击）不该等落盘，失败会经 [onPersistFailed] 留痕。
     */
    internal fun persistSoon(scopeId: String, persist: suspend () -> Boolean) {
        writer.launch {
            // 不能把异常丢给线程默认处理器：`launch` 里未捕获的异常会直接带到
            // 线程的 uncaught handler，真机上就是一次闪退 —— 而这里只是“记一笔状态没写成”。
            // 取消照旧原样抛出（与第十五批的 `runGuarded` 同一条规则）。
            try {
                mutexFor(scopeId).withLock { persist() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                onPersistFailed?.invoke(scopeId)
            }
        }
    }

    fun scopeFor(store: BackgroundResumeStore?, scopeId: String): BackgroundResumeScope =
        synchronized(scopes) {
            scopes.getOrPut(scopeId) { BackgroundResumeScope(store, scopeId) }
        }

    /**
     * 同一作用域的互斥锁。
     *
     * `runOnce` 的「读内存 → 判定 → 记账 → 写盘」与用户发消息时的 `resetChain()` 必须串行：
     * 两者都可能先读旧水位线、后写盘，后写者会把新值覆盖回去（重启后重放任务）。
     */
    fun mutexFor(scopeId: String): Mutex = synchronized(scopes) { locks.getOrPut(scopeId) { Mutex() } }

    /** 只给测试用：清掉所有作用域，避免用例之间互相污染。 */
    fun reset() {
        synchronized(scopes) {
            scopes.clear()
            locks.clear()
        }
    }
}
