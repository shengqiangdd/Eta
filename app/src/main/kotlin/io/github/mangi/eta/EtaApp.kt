package io.github.mangi.eta

import io.github.mangi.eta.agent.voice.SpeechOssUpload
import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.VirtualDisplayImeRestore
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.BootLog
import io.github.mangi.eta.core.DiagnosticsRetention
import io.github.mangi.eta.core.StartupTrace
import io.github.mangi.eta.ui.components.CharacterImageCache
import io.github.mangi.eta.ui.markdown.StaticDocumentCache
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.app.AppVisibility
import io.github.mangi.eta.ui.app.PredictiveBackController
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 模块 UI 进程的 Application。
 *
 * 在进程启动时注册 [XposedServiceHelper] 监听器，框架会通过 XposedProvider 推送 binder，
 * 随后 UI 即可拿到 [XposedService] 写入 RemotePreferences，跨进程同步到各 hook 进程。
 *
 * UI 侧通过 [XposedService] 写入 RemotePreferences。
 */
class EtaApp : Application(), XposedServiceHelper.OnServiceListener {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.initLocal(this)
        // 启动账目落盘：logcat 环形缓冲几分钟就会被 agent 事件顶掉，事后根本查不到启动分段。
        BootLog.init(java.io.File(filesDir, "diagnostics"))
        if (!AppProcessPolicy.shouldInitializeFullRuntime(Application.getProcessName(), packageName)) {
            return
        }
        registerVisibilityCallbacks()
        StartupTrace.mark("app-onCreate")
        TerminalRuntime.initialize(this)
        RootAccess.initialize(this)
        applicationScope.launch {
            // 若上一次在虚拟屏输入租约期间被系统杀掉，先把默认输入法修回来，避免主屏没有键盘。
            // Root 探测是异步的：多试几次，避免恰好在授权状态就绪前放弃。
            repeat(3) {
                VirtualDisplayImeRestore.repair()
                kotlinx.coroutines.delay(1_500)
            }
        }
        applicationScope.launch { TerminalRuntime.backgroundJobs(this@EtaApp).resume() }
        SettingsDataStore.init(this)
        AppearanceSettingsRepository.init(this)
        // 不再 runBlocking 等 DataStore：冷启动首次读取要建文件 + 解析整个 Preferences，
        // 堵在主线程就是启动画面卡住的那几百毫秒。先用上次快照，真值由 flow 稍后纠正。
        val predictiveBackEnabled = AppearanceSettingsRepository.snapshot().predictiveBackEnabled
        PredictiveBackController.apply(applicationInfo, predictiveBackEnabled)
        AgentMemoryRepository.init(this)
        io.github.mangi.eta.agent.usage.AgentUsageLedger.init(this)
        io.github.mangi.eta.agent.trace.AgentRunTraceStore.init(this)
        ProviderRepository.init(this)
        McpServerRepository.init(this)
        XposedServiceHelper.registerListener(this)
        StartupTrace.mark("app-ready")
        // 诊断数据（run-traces \/ spill）自装上起只增不减，开个后台任务按预算回收。
        // 纯观测数据，失败静默：不能因为清理影响启动。
        applicationScope.launch {
            runCatching { DiagnosticsRetention.run(filesDir) }
        }
        applicationScope.launch {
            try {
                SpeechOssUpload(this@EtaApp).retryPending(this@EtaApp)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                AndroidAgentLogger.warn("Eta speech cleanup unavailable: type=${error.javaClass.simpleName}")
            }
        }
        applicationScope.launch {
            LinuxEnvironmentSettingsRepository.initialize(this@EtaApp)
        }
    }

    /**
     * 维护 [AppVisibility]：所有 Activity 都 stop 了才算进后台。
     *
     * 为什么不用 onTrimMemory：后台进程常常是先被系统记账、后收到 trim 回调，
     * 甚至压根收不到（进程已被冻结）。以前台/后台切换作为释放点更早也更可靠，
     * 而且它和「用户看不见了」这件事本身一致。
     */
    private fun registerVisibilityCallbacks() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started += 1
                if (started == 1) AppVisibility.setForeground(true)
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) AppVisibility.setForeground(false)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                CharacterImageCache.evictAll()
                StaticDocumentCache.evictAll()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                CharacterImageCache.trimTo(16 * 1024 * 1024)
                StaticDocumentCache.evictAll()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        CharacterImageCache.evictAll()
        StaticDocumentCache.evictAll()
    }

    override fun onServiceBind(service: XposedService) {
        serviceInstance = service
        Prefs.reconcileAgentPreferences(service)
        dispatch(service)
    }

    override fun onServiceDied(service: XposedService) {
        // 只有当前持有的 service 死亡时才清空并派发 null；
        // 多 framework 场景下死掉的可能是已被替换的旧实例，无需影响 UI。
        if (serviceInstance === service) {
            serviceInstance = null
            dispatch(null)
        }
    }

    companion object {
        @Volatile
        var serviceInstance: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            listeners.add(listener)
            if (notifyImmediately) {
                dispatchTo(listener, serviceInstance)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }

        private fun dispatch(service: XposedService?) {
            listeners.forEach { dispatchTo(it, service) }
        }

        private fun dispatchTo(listener: ServiceStateListener, service: XposedService?) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onServiceStateChanged(service)
            } else {
                mainHandler.post {
                    if (listeners.contains(listener)) {
                        listener.onServiceStateChanged(service)
                    }
                }
            }
        }
    }
}
