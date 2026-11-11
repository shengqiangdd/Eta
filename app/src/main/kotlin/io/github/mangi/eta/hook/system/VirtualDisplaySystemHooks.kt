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
    private const val ATS_CLASS = "com.android.server.wm.ActivityTaskSupervisor"

    private const val IME_POLICY_INITIAL_DELAY_MS = 100L
    private const val IME_POLICY_RETRY_DELAY_MS = 150L
    private const val IME_POLICY_RETRY_COUNT = 20

    private val activeEtaDisplayCreation = ThreadLocal.withInitial { false }

    /**
     * Eta 创建成功过的虚拟屏 displayId。
     *
     * 为什么不能用「当前正在创建」那个 ThreadLocal 判放行：`canPlaceEntityOnDisplay`
     * 发生在 startActivity 时，与 `createVirtualDisplay` 不在同一时段，标记早已复位。
     * 必须按 displayId 记住归属；且只在创建成功后写入，避免把失败返回的负数当成屏。
     */
    private val etaDisplayIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "VirtualDisplay")
        return hooks.install {
            installDisplayManagerHooks(hooks, classLoader)
            installCrossAppLaunchHooks(hooks, classLoader)
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
                // 记下归属：后续该屏上的跨应用启动靠它判放行。
                etaDisplayIds.add(displayId)
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

    /**
     * 允许把 activity 放到 Eta 自己的虚拟屏上。
     *
     * `canPlaceEntityOnDisplay()` 限制「只能在同一虚显上启动 activity」，
     * 目的是防后台应用把界面偷偷丢到副显；但 Eta 的虚拟屏本来
     * 就是拿来跑别的应用的，于是正常跨应用跳转会被当成滥用拒掉。
     *
     * 放行条件（全部满足才放行）：目标屏是 Eta 创建过的、
     * 调用方是 Eta、虚拟屏总开关打开。任一条判不出来一律 proceed()。
     */
    @SuppressLint("PrivateApi")
    private fun installCrossAppLaunchHooks(hooks: HookRegistrar, classLoader: ClassLoader) {
        val atsClass = HookSupport.findClassOrNull(classLoader, ATS_CLASS)
        val canPlaceMethod = atsClass?.let { clazz ->
            HookSupport.findDeclaredMethods(clazz, makeAccessible = true) { method ->
                method.name == "canPlaceEntityOnDisplay" &&
                    method.parameterCount == 5 &&
                    method.returnType == Boolean::class.javaPrimitiveType
            }.firstOrNull()
        }
        if (canPlaceMethod == null) {
            hooks.missing(
                id = "system.virtual-display-cross-app",
                description = "ActivityTaskSupervisor.canPlaceEntityOnDisplay",
                detail = "未找到 5 参数的 canPlaceEntityOnDisplay（系统版本可能不同）",
            )
            return
        }

        hooks.intercept(
            id = "system.virtual-display-cross-app",
            executable = canPlaceMethod,
            description = "ActivityTaskSupervisor.canPlaceEntityOnDisplay",
        ) { chain ->
            // 参数布局：(Task?, int displayId, ..., ActivityInfo)，取第一个 Int 当 displayId。
            val displayId = chain.args.firstOrNull { it is Int } as? Int
            // 判定逻辑抽在 VirtualDisplayCallerPolicy（纯函数，有单测）：
            // 只看“这个屏是不是 Eta 建的”与“调用方是不是 Eta”，
            // 不看调用时刻（那个时段早已过去，写出来就是永不命中的死代码）。
            // 也不在这里读 AGENT_VIRTUAL_DISPLAY_ENABLED：它不在 LOCAL_AGENT_KEYS，
            // hook 进程读到的是默认值，写成条件也永远为真。
            if (VirtualDisplayCallerPolicy.allows(
                    displayId = displayId,
                    etaDisplayIds = etaDisplayIds,
                    callerIsEta = isEtaCaller(chain.thisObject),
                )
            ) {
                return@intercept true
            }
            chain.proceed()
        }
    }

    /** 调用方是否为 Eta；判不出来一律 false（不放行）。 */
    private fun isEtaCaller(target: Any?): Boolean = runCatching {
        val context = SystemServerContextResolver.resolve(target) ?: return@runCatching false
        val callingUid = Binder.getCallingUid()
        context.packageManager.getPackagesForUid(callingUid)
            ?.any { it == ModuleConfig.ETA_PACKAGE } == true
    }.getOrDefault(false)

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
