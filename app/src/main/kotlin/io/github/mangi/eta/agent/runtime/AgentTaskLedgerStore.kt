package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentTaskLedger
import io.github.mangi.eta.agent.model.AgentTaskRecord
import io.github.mangi.eta.agent.model.AgentTaskStatus
import java.io.File
import java.security.MessageDigest

/**
 * 会话级任务台账的落盘存储。
 *
 * 与 [AgentTodoStore] 同一套取舍（单文件、原子替换、按作用域哈希命名），区别只有两点：
 * - 台账是**追加/改状态**，不是整表替换，所以这里暴露的是"开始一条 / 结束一条"两个动作；
 * - 子代理**可能并发**（同一轮里模型可以同时派多个），所以读-改-写必须在同一把锁里，
 *   否则两个 start 各自读到旧台账、后写的那份会把先写的整条抹掉。
 *
 * 返回 Boolean / null 而不是抛异常：台账是**旁路记录**，写不进去不该让子代理本身失败。
 */
internal class AgentTaskLedgerStore(private val root: File) {

    fun fileFor(scopeId: String): File? {
        if (scopeId.isBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(scopeId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
            .take(32)
        return File(root, "$digest.json")
    }

    fun load(scopeId: String): AgentTaskLedger {
        val file = fileFor(scopeId) ?: return AgentTaskLedger()
        if (!file.isFile || file.length() > MAX_BYTES) return AgentTaskLedger()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?: return AgentTaskLedger()
        return AgentTaskLedger.fromStoredJson(text) ?: AgentTaskLedger()
    }

    /** 记一条"开始了"；同 id 覆盖（resume 同一个子代理时保持台账只有一条）。 */
    fun recordStarted(scopeId: String, record: AgentTaskRecord): Boolean =
        synchronized(writeLock) {
            write(scopeId, load(scopeId).upsert(record))
        }

    /** 记一条"结束了"；台账里没有这个 id 时返回 false（不凭空造一条没有开头的记录）。 */
    fun recordFinished(
        scopeId: String,
        id: String,
        status: AgentTaskStatus,
        summary: String?,
        endedAt: Long,
    ): Boolean = synchronized(writeLock) {
        val updated = load(scopeId).finish(id, status, summary, endedAt) ?: return false
        write(scopeId, updated)
    }

    fun clear(scopeId: String): Boolean {
        val file = fileFor(scopeId) ?: return false
        return runCatching { !file.exists() || file.delete() }.getOrDefault(false)
    }

    private fun write(scopeId: String, ledger: AgentTaskLedger): Boolean {
        val file = fileFor(scopeId) ?: return false
        val dir = file.parentFile ?: return false
        return runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(ledger.toJson().toString(), Charsets.UTF_8)
            // 直接 rename 覆盖在某些文件系统上会失败，先删再换；失败就清掉临时文件。
            if (file.exists() && !file.delete()) {
                tmp.delete()
                return false
            }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return false
            }
            true
        }.getOrDefault(false)
    }

    companion object {
        /** 32 条 × 一条几百字节，远小于这个上限；超过就是坏文件，直接当作没有台账。 */
        const val MAX_BYTES = 64 * 1024

        /**
         * 全进程一把锁，而不是每个 store 实例一把。
         *
         * 子代理执行器与工具执行器各自持有 store 实例，指向同一批文件；按实例加锁等于没加锁。
         */
        private val writeLock = Any()

        fun forApp(context: Context): AgentTaskLedgerStore =
            AgentTaskLedgerStore(File(context.applicationContext.filesDir, "agent/tasks"))
    }
}
