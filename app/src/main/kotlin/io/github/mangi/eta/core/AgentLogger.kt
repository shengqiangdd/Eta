package io.github.mangi.eta.core

import android.util.Log

internal interface AgentLogger {
    /**
     * 记录仅用于开发期诊断的信息。
     *
     * supplier 只能构造诊断文本，不能承担程序正确性依赖的副作用；Release 构建会删除整次调用。
     */
    fun debug(message: () -> String)

    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
}

internal object AndroidAgentLogger : AgentLogger {
    private val logThrottle = LogThrottle()

    override fun debug(message: () -> String) {
        Log.d(ModuleConfig.TAG, message())
    }

    override fun info(message: String) {
        Log.i(ModuleConfig.TAG, message)
    }

    /**
     * 记录同时落盘的关键账目行（启动分段、恢复回放）。
     *
     * 只给「事后一定会被追问、又会被 DEBUG 事件顶掉」的少量行使用；普通 info 不要走这里，
     * 否则 `files/diagnostics/boot.log` 会变成第二个日志洪水源。
     */
    fun infoPersisted(message: String) {
        Log.i(ModuleConfig.TAG, message)
        BootLog.append(message)
    }

    override fun warn(message: String) {
        Log.w(ModuleConfig.TAG, message)
    }

    fun warnThrottled(
        key: String,
        windowMs: Long = ModuleConfig.HOT_PATH_LOG_WINDOW_MS,
        message: () -> String
    ) {
        if (logThrottle.shouldLog("warn:$key", windowMs)) {
            warn(message())
        }
    }

    override fun error(message: String, throwable: Throwable?) {
        if (throwable == null) {
            Log.e(ModuleConfig.TAG, message)
        } else {
            Log.e(ModuleConfig.TAG, message, throwable)
        }
    }

    fun errorThrottled(
        key: String,
        throwable: Throwable? = null,
        windowMs: Long = ModuleConfig.HOT_PATH_LOG_WINDOW_MS,
        message: () -> String
    ) {
        if (logThrottle.shouldLog("error:$key", windowMs)) {
            error(message(), throwable)
        }
    }
}
