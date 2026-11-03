package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentTaskLedger
import io.github.mangi.eta.agent.model.AgentTaskRecord
import io.github.mangi.eta.agent.model.AgentTaskStatus
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

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
internal class AgentTaskLedgerStore(
    private val root: File,
    /** 写者身份：用来分辨「这条 RUNNING 是本进程写的」还是上一个进程的残留。 */
    private val owner: String = ProcessOwner.current,
) {

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
        val stored = runCatching { JSONObject(text) }.getOrNull() ?: return AgentTaskLedger()
        val ledger = AgentTaskLedger.fromStoredJson(stored) ?: return AgentTaskLedger()
        // 写者身份不同 = 这份记录来自上一个进程；那时的「运行中」不可能还在跑。
        // 只在内存里修正、不在这里写盘：读路径要快，修正会在下一次 start/finish 落盘。
        if (stored.optString("owner") == owner) return ledger
        return ledger.abandonRunning() ?: ledger
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

    /**
     * 刷新运行中条目的进度。
     *
     * 特意做成"读-改-写同一把锁"，并且 [AgentTaskLedger.progress] 只改 RUNNING 条目：
     * 子代理结束写终态与子代理线程写进度可能同时发生，不能把 completed 覆盖回 running。
     */
    fun recordProgress(scopeId: String, id: String, text: String, at: Long): Boolean =
        synchronized(writeLock) {
            val updated = load(scopeId).progress(id, text, at) ?: return false
            write(scopeId, updated)
        }

    fun clear(scopeId: String): Boolean {
        val file = fileFor(scopeId) ?: return false
        val ok = runCatching { !file.exists() || file.delete() }.getOrDefault(false)
        if (ok) notifyChanged()
        return ok
    }

    private fun write(scopeId: String, ledger: AgentTaskLedger): Boolean {
        val ok = writeFile(scopeId, ledger)
        if (ok) notifyChanged()
        return ok
    }

    private fun writeFile(scopeId: String, ledger: AgentTaskLedger): Boolean {
        val file = fileFor(scopeId) ?: return false
        val dir = file.parentFile ?: return false
        return runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(ledger.toJson(owner).toString(), Charsets.UTF_8)
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

        private val _changes = MutableStateFlow(0L)

        /**
         * 每次落盘成功自增。
         *
         * 界面靠它刷新：子代理结果写的是同一批文件，消息条数不会变，按消息刷新看不到状态变化。
         * 与 [AgentTodoStore.changes] 同一约定（计入写入次数，而不是存内容）。
         */
        val changes: StateFlow<Long> = _changes.asStateFlow()

        private fun notifyChanged() {
            _changes.update { it + 1 }
        }

        fun forApp(context: Context): AgentTaskLedgerStore =
            AgentTaskLedgerStore(File(context.applicationContext.filesDir, "agent/tasks"))
    }
}

/**
 * 本进程的标识，只用于台账判活。
 *
 * 不用时间判据：设备会改时间，跨重启的时钟也不可比；而「同一个会话作用域只由一个进程写」
 * 是业务事实（子代理在主进程执行），拿写者身份对比既准确又便宜。
 * 它不是安全凭据，也不写进任何用户可见文本。
 */
private object ProcessOwner {
    val current: String = UUID.randomUUID().toString().replace("-", "").take(16)
}
