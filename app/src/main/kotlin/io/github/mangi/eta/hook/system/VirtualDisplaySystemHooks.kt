package io.github.mangi.eta.hook.system

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.VirtualDisplayConfig
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.agent.device.VirtualDisplayImePolicy
import io.github.mangi.eta.agent.virtualdisplay.CanvasRoutingPolicy
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger

/**
 * 在 system_server 为 Eta 自身的后台虚拟屏授予受信任显示标志与任务迁移权限。
 *
 * 严格限制调用方包名与虚拟屏名称（EtaAgentVirtualDisplay 及旧版 eta-virtual- 前缀），
 * 不对其他应用开放权限。创建成功后还会把该屏的 IME policy 设为 HIDE。
 */
internal object VirtualDisplaySystemHooks {

    private const val DMS_CLASS = "com.android.server.display.DisplayManagerService"
    private const val DMS_BINDER_CLASS = "com.android.server.display.DisplayManagerService\$BinderService"
    private const val ATMS_CLASS = "com.android.server.wm.ActivityTaskManagerService"

    private const val IME_POLICY_INITIAL_DELAY_MS = 100L
    private const val IME_POLICY_RETRY_DELAY_MS = 150L
    private const val IME_POLICY_RETRY_COUNT = 20

    private val activeEtaDisplayCreation = ThreadLocal.withInitial { false }

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "VirtualDisplay")
        return hooks.install {
            installDisplayManagerHooks(hooks, classLoader)
            installTaskMoveHooks(hooks, classLoader)
        }
    }

    private fun installDisplayManagerHooks(hooks: HookRegistrar, classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return
        }
        val dmsClass = HookSupport.findClassOrNull(classLoader, DMS_CLASS)
        val binderClass = HookSupport.findClassOrNull(classLoader, DMS_BINDER_CLASS)
        val createMethod = binderClass?.let { clazz ->
            HookSupport.findDeclaredMethods(clazz, makeAccessible = true) { method ->
                method.name == "createVirtualDisplay" &&
                    method.parameterCount >= 4 &&
                    method.parameterTypes[0] == VirtualDisplayConfig::class.java &&
                    method.parameterTypes[3] == String::class.java
            }.firstOrNull()
        }
        if (dmsClass == null || createMethod == null) {
            hooks.missing(
                id = "system.virtual-display-create",
                description = "DisplayManagerService.createVirtualDisplay",
                detail = "未找到 DisplayManagerService\$BinderService.createVirtualDisplay",
            )
            return
        }

        hooks.intercept(
            id = "system.virtual-display-create",
            executable = createMethod,
            description = "DisplayManagerService\$BinderService.createVirtualDisplay",
        ) { chain ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                !Prefs.isEnabled(Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED)
            ) {
                return@intercept chain.proceed()
            }
            val args = chain.args.toMutableList()
            val config = args.getOrNull(0) as? VirtualDisplayConfig
            val packageName = args.getOrNull(3) as? String
            val upgraded = if (packageName == ModuleConfig.ETA_PACKAGE && config != null) {
                upgradeVirtualDisplayConfig(config)
            } else {
                null
            } ?: return@intercept chain.proceed()
            args[0] = upgraded
            activeEtaDisplayCreation.set(true)
            val result = try {
                chain.proceed(args.toTypedArray())
            } finally {
                activeEtaDisplayCreation.set(false)
            }
            // createVirtualDisplay 返回的是新 displayId；在 system_server 里立刻把
            // 这块屏的 IME policy 设成 HIDE。这样旧/新两条虚拟屏路径都不需要
            // App 去改全局默认输入法。display 可能还没同步到 WMS，稍等重试。
            (result as? Int)?.takeIf { it > 0 }?.let { displayId ->
                scheduleImePolicyHide(
                    displayId = displayId,
                    context = SystemServerContextResolver.resolve(chain.thisObject),
                    logger = hooks.logger,
                )
            }
            result
        }

        val checkPermMethod = HookSupport.findDeclaredMethods(dmsClass, makeAccessible = true) { method ->
            method.name == "checkCallingPermission" &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == String::class.java &&
                method.parameterTypes[1] == String::class.java &&
                method.returnType == Boolean::class.javaPrimitiveType
        }.firstOrNull()

        if (checkPermMethod != null) {
            hooks.intercept(
                id = "system.virtual-display-permission",
                executable = checkPermMethod,
                description = "DisplayManagerService.checkCallingPermission",
            ) { chain ->
                if (activeEtaDisplayCreation.get() == true) {
                    true
                } else {
                    chain.proceed()
                }
            }
        }
    }

    @android.annotation.SuppressLint("WrongConstant")
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun upgradeVirtualDisplayConfig(config: VirtualDisplayConfig): VirtualDisplayConfig? {
        if (!CanvasRoutingPolicy.isEtaVirtualDisplayName(config.name)) return null
        return runCatching {
            VirtualDisplayConfig.Builder(
                config.name,
                config.width,
                config.height,
                config.densityDpi,
            ).apply {
                config.surface?.let(::setSurface)
                setFlags(config.flags or CanvasRoutingPolicy.TRUSTED_INTERACTIVE_FLAGS)
                if (config.requestedRefreshRate > 0f) {
                    setRequestedRefreshRate(config.requestedRefreshRate)
                }
            }.build()
        }.getOrDefault(config)
    }

    /**
     * 给 Eta 自己的虚拟屏设置 per-display IME policy = HIDE。
     *
     * 为什么在 system_server 做：`WindowManagerService.setDisplayImePolicy()` 需要
     * `INTERNAL_SYSTEM_WINDOW`，普通 App 调用会被 SecurityException 拒绝；这里从
     * system_server 通过 IWindowManager 代理调用，调用者 UID 是 1000，正好满足。
     * 只对 createVirtualDisplay Hook 认出并升级过 trusted 标志的 Eta 显示执行。
     */
    private fun scheduleImePolicyHide(displayId: Int, context: Context?, logger: ModuleLogger) {
        // 先清掉旧标记：displayId 可能被复用，不能让新屏误读上一块屏的确认。
        writeImePolicyMarker(context, displayId, 0)
        val handler = Handler(Looper.getMainLooper())
        var attempts = 0
        fun attempt() {
            if (setImePolicyHide(displayId, logger)) {
                writeImePolicyMarker(context, displayId, VirtualDisplayImePolicy.IME_POLICY_HIDE)
                logger.info("虚拟屏 IME policy=HIDE displayId=$displayId")
                return
            }
            attempts += 1
            if (attempts < IME_POLICY_RETRY_COUNT) {
                handler.postDelayed({ attempt() }, IME_POLICY_RETRY_DELAY_MS)
            } else {
                writeImePolicyMarker(context, displayId, 0)
                logger.warn(
                    "虚拟屏 IME policy=HIDE 设置失败 displayId=$displayId；" +
                        "App 将继续走静默输入法守卫",
                )
            }
        }
        handler.postDelayed({ attempt() }, IME_POLICY_INITIAL_DELAY_MS)
    }

    /** 把 policy 是否成功写入系统设置，供 App 在动作前确认；失败时保持 0。 */
    private fun writeImePolicyMarker(context: Context?, displayId: Int, policy: Int) {
        if (context == null) return
        runCatching {
            Settings.Global.putInt(
                context.contentResolver,
                VirtualDisplayImePolicy.imePolicySettingKey(displayId),
                policy,
            )
        }
    }

    @SuppressLint("PrivateApi")
    private fun setImePolicyHide(displayId: Int, logger: ModuleLogger): Boolean = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager
            .getMethod("getService", String::class.java)
            .invoke(null, "window") as? IBinder
            ?: return@runCatching false
        val stub = Class.forName("android.view.IWindowManager\$Stub")
        val windowManager = stub
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
            ?: return@runCatching false
        val method = windowManager.javaClass.getMethod(
            "setDisplayImePolicy",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val token = Binder.clearCallingIdentity()
        try {
            method.invoke(windowManager, displayId, VirtualDisplayImePolicy.IME_POLICY_HIDE)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        true
    }.getOrElse { error ->
        logger.debug {
            "虚拟屏 IME policy 调用未完成 displayId=$displayId type=${error.javaClass.simpleName}"
        }
        false
    }

    private fun installTaskMoveHooks(hooks: HookRegistrar, classLoader: ClassLoader) {
        val atmsClass = HookSupport.findClassOrNull(classLoader, ATMS_CLASS) ?: return
        val moveMethod = HookSupport.findMethod(
            atmsClass,
            "moveRootTaskToDisplay",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        ) ?: return

        hooks.intercept(
            id = "system.virtual-display-move-task",
            executable = moveMethod,
            description = "ActivityTaskManagerService.moveRootTaskToDisplay",
        ) { chain ->
            val target = chain.thisObject
            val context = SystemServerContextResolver.resolve(target)
            val callingUid = Binder.getCallingUid()
            val isEtaCaller = context != null && runCatching {
                context.packageManager.getPackagesForUid(callingUid)
                    ?.any { it == ModuleConfig.ETA_PACKAGE } == true
            }.getOrDefault(false)
            if (!isEtaCaller) {
                return@intercept chain.proceed()
            }
            val token = Binder.clearCallingIdentity()
            try {
                chain.proceed()
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }
    }
}
