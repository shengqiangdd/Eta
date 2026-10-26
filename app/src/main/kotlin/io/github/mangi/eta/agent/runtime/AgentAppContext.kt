package io.github.mangi.eta.agent.runtime

import android.annotation.SuppressLint
import android.content.Context

// 非 App 进程（app_process / hook 环境）没有现成 Context，只能反射 ActivityThread.currentApplication 或
// AppGlobals.getInitialApplication；两条路径都 runCatching，失败返回 null。
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal object AgentAppContext {
    fun resolve(): Context? {
        val activityThreadClass = runCatching {
            Class.forName("android.app.ActivityThread")
        }.getOrNull()
        runCatching {
            activityThreadClass
                ?.getDeclaredMethod("currentApplication")
                ?.apply { isAccessible = true }
                ?.invoke(null) as? Context
        }.getOrNull()?.let { return it.applicationContext ?: it }

        runCatching {
            Class.forName("android.app.AppGlobals")
                .getDeclaredMethod("getInitialApplication")
                .apply { isAccessible = true }
                .invoke(null) as? Context
        }.getOrNull()?.let { return it.applicationContext ?: it }

        return null
    }
}
