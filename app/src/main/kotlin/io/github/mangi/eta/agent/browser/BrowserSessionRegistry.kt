package io.github.mangi.eta.agent.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 页面按会话保留，运行引用与挂载容器共同决定何时可以回收。 */
internal object BrowserSessionRegistry {
    const val MANUAL_SESSION_KEY = "browser:manual"

    /**
     * 最多同时保留多少个空闲会话。
     *
     * 超出的按「最久未用」依次 dispose。为什么要上限：每个 Entry 背后是一个
     * AgentBrowserSession，可能拎着一个 WebView（十几到几十 MB）；而 entries
     * 原先只在 requestRemove 后才回收，那个方法在生产代码里**从未被调用**，
     * 所以会话只增不减。真机上三次 OOM（256MB 堆用满）就发生在这种长时间使用后。
     *
     * 取 8 而不取更小：页面的价值在「同一会话下一轮还能看到上次打开的页」，
     * 用户近期来回切的那几个对话应该都在窗口内。
     */
    private const val MAX_IDLE_SESSIONS = 8

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val entries = mutableMapOf<String, Entry>()
    private val clearingData = AtomicBoolean(false)

    private class Entry(val session: AgentBrowserSession) {
        val runs = mutableMapOf<String, Int>()
        var removalRequested = false

        /** 当前有几个界面上真正挂着这个会话的 WebView。挂载期间不能回收。 */
        var mounted = 0

        /** 最近一次被取得或挂载的时刻，用于空闲会话的 LRU 淘汰。 */
        var lastUsedAt = SystemClock.elapsedRealtime()
    }

    fun get(sessionKey: String): AgentBrowserSession = synchronized(lock) {
        entry(sessionKey).also { it.lastUsedAt = SystemClock.elapsedRealtime() }.session
    }

    /**
     * 标记一个会话正被界面挂载（浏览器面板打开）。
     *
     * 这是类注释里「挂载容器」那一半：原先根本没实现，所以面板关掉后没人通知，
     * 会话只能等下次 onTrimMemory 才被释放。返回的 AutoCloseable 在界面销毁时关闭。
     */
    fun mount(sessionKey: String): AutoCloseable {
        val owner = synchronized(lock) {
            entry(sessionKey).also {
                it.mounted += 1
                it.lastUsedAt = SystemClock.elapsedRealtime()
            }
        }
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (released.compareAndSet(false, true)) {
                synchronized(lock) { owner.mounted = (owner.mounted - 1).coerceAtLeast(0) }
                collectRemovedPages()
            }
        }
    }

    fun acquireRun(sessionKey: String, runId: String): AutoCloseable {
        require(runId.isNotBlank())
        val owner = synchronized(lock) {
            entry(sessionKey).also {
                it.runs[runId] = (it.runs[runId] ?: 0) + 1
                it.lastUsedAt = SystemClock.elapsedRealtime()
            }
        }
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (released.compareAndSet(false, true)) {
                synchronized(lock) {
                    val count = (owner.runs[runId] ?: 1) - 1
                    if (count == 0) owner.runs.remove(runId) else owner.runs[runId] = count
                }
                // 一次性会话（键为 run:<id>）跑完就再也没人能用到：浏览器面板用的是
                // 会话 id 或 MANUAL_SESSION_KEY，不会挂 run: 开头的键。
                // 不在这里回收，它们就会随着运行次数无限累积。
                if (BrowserSessionRetention.isRunScoped(sessionKey)) requestRemove(sessionKey)
                else collectRemovedPages()
            }
        }
    }

    fun requestRemove(sessionKey: String) {
        synchronized(lock) { entries[sessionKey]?.removalRequested = true }
        collectRemovedPages()
    }

    fun releaseIdlePages() = onMain {
        synchronized(lock) {
            entries.values.filter { it.runs.isEmpty() }.forEach { it.session.releaseIdlePage() }
            removeReadyEntries()
        }
    }

    /** 全局清理先唤醒动作等待，再在后台逐页重置，主线程从不等待页面操作锁。 */
    fun clearSharedBrowsingData(context: Context) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Browser data clearing requires a worker thread" }
        check(clearingData.compareAndSet(false, true)) { "Browser data clearing is already in progress" }
        try {
            val sessions = synchronized(lock) { entries.values.map { it.session } }
            sessions.forEach { it.stopFromUser() }
            sessions.forEach { it.resetFromUser() }
            val cleared = CompletableFuture<Unit>()
            mainHandler.post {
                try {
                    val cacheCleaner = WebView(context.applicationContext)
                    try { cacheCleaner.clearCache(true) } finally { cacheCleaner.destroy() }
                    WebStorage.getInstance().deleteAllData()
                    CookieManager.getInstance().removeAllCookies {
                        try {
                            CookieManager.getInstance().flush()
                            cleared.complete(Unit)
                        } catch (failure: Exception) {
                            cleared.completeExceptionally(failure)
                        }
                    }
                } catch (failure: Exception) {
                    cleared.completeExceptionally(failure)
                }
            }
            cleared.get(15L, TimeUnit.SECONDS)
        } finally {
            clearingData.set(false)
        }
    }

    private fun entry(sessionKey: String): Entry {
        require(sessionKey.isNotBlank())
        return entries.getOrPut(sessionKey) {
            Entry(AgentBrowserSession(onIdle = ::collectRemovedPages, isDataClearing = clearingData::get))
        }
    }

    private fun collectRemovedPages() = onMain { synchronized(lock) { removeReadyEntries() } }

    private fun removeReadyEntries() {
        // 判定全部交给 BrowserSessionRetention（纯逻辑、有纯 JVM 单测）；
        // 这里只负责真正 dispose——销毁 WebView 必须在主线程，也只能在这里做。
        entries.entries.removeAll { (key, entry) ->
            val candidate = entry.snapshot(key)
            BrowserSessionRetention.canDispose(candidate, entry.removalRequested) &&
                entry.session.releaseIdlePage(dispose = true)
        }
        trimIdleSessions()
    }

    private fun trimIdleSessions() {
        val victims = BrowserSessionRetention.selectEvictions(
            candidates = entries.map { (key, entry) -> entry.snapshot(key) },
            maxIdle = MAX_IDLE_SESSIONS,
        )
        for (key in victims) {
            // dispose 可能失败（页面操作锁被占、或刚好又有运行挂上）——失败就留着，下次再说。
            val entry = entries[key] ?: continue
            if (entry.session.releaseIdlePage(dispose = true)) entries.remove(key)
        }
    }

    private fun Entry.snapshot(key: String) = BrowserSessionRetention.Candidate(
        key = key,
        runs = runs.size,
        mounted = mounted,
        lastUsedAt = lastUsedAt,
    )

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }
}
