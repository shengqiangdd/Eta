package io.github.mangi.eta.agent.device

import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 静默输入法的崩溃兜底。
 *
 * 虚拟屏输入租约会临时把默认输入法切成 Eta 的无 UI IME；如果进程在租约期间被
 * 系统杀掉，内存里的原输入法就没了，用户会失去主屏键盘。切换前把原 IME 写到这个
 * root 可读文件，App 下一次启动时检查并恢复。
 */
internal object VirtualDisplayImeRestore {
    const val PATH = "/data/local/tmp/eta-ime-restore"
    private val IME_ID = Regex("[A-Za-z0-9_.$/:-]+")

    fun repair(logger: AgentLogger = AndroidAgentLogger) {
        val executor = runCatching { BoundedRootCommandExecutor(logger) }.getOrNull() ?: return
        executor.use {
            val current = runCatching {
                executor.execute("settings get secure default_input_method", timeoutMillis = 5_000).stdout.trim()
            }.getOrNull().orEmpty()
            val original = runCatching {
                executor.execute("cat $PATH", timeoutMillis = 5_000).stdout.trim()
            }.getOrNull().orEmpty()
            if (current.isBlank()) return
            if (current.contains("EtaSilentImeService") &&
                original.isNotBlank() &&
                original.matches(IME_ID) &&
                original != current
            ) {
                runCatching {
                    executor.execute("ime set $original", timeoutMillis = 5_000)
                    logger.info("Virtual display IME restored after process restart")
                }.onFailure {
                    logger.warn("Virtual display IME restore after process restart failed")
                }
            } else if (!current.contains("EtaSilentImeService")) {
                runCatching { executor.execute("rm -f $PATH", timeoutMillis = 5_000) }
            }
        }
    }
}
