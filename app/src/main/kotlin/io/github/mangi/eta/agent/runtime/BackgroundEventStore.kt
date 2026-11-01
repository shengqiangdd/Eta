package io.github.mangi.eta.agent.runtime

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * 「后台任务结束事件已经告知到哪一刻」的落盘存储，按会话作用域分文件。
 *
 * 只存一个水位线（`delivered_until`），不存 id 列表：
 * - 事件不需要重放，只需要「别再报一次」；水位线是 O(1) 的状态，不会因为条数上限把旧 id 挤掉
 *   又当成新事件重报。
 * - 代价是同一毫秒内结束的多条任务会被一起压住 —— 那一次渲染本来就会把它们全列出来。
 *
 * 与 [AgentTaskLedgerStore] 同一套取舍：作用域哈希命名、进程级一把写锁、`.tmp` 换名、
 * 坏文件按「没有记录」处理。返回 Boolean 而不是抛异常：事件提示是旁路，写不进去不该让整轮失败。
 */
internal class BackgroundEventStore(private val root: File) {

    fun fileFor(scopeId: String): File? {
        if (scopeId.isBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(scopeId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
            .take(32)
        return File(root, "$digest.json")
    }

    /** 已告知到哪一刻（毫秒）；从没告知过、文件坏、值非法都返回 null。 */
    fun loadDeliveredUntil(scopeId: String): Long? {
        val file = fileFor(scopeId) ?: return null
        if (!file.isFile || file.length() > MAX_BYTES) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val stored = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val value = stored.optLong("delivered_until", 0L)
        return value.takeIf { it > 0 }
    }

    /**
     * 把水位线推进到 [at]；只前进不后退。
     *
     * 不后退很重要：两个 run 并发时，后完成的那次可能带着更早的事件（记录读得早），
     * 若直接覆盖，先前的告知就会被「忘掉」，下次又重报一遍。
     */
    fun markDeliveredUntil(scopeId: String, at: Long): Boolean {
        if (at <= 0) return false
        val file = fileFor(scopeId) ?: return false
        synchronized(WRITE_LOCK) {
            val current = loadDeliveredUntil(scopeId) ?: 0L
            if (at <= current) return true
            return write(file, at)
        }
    }

    private fun write(file: File, at: Long): Boolean {
        val dir = file.parentFile ?: return false
        return runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(JSONObject().put("delivered_until", at).toString(), Charsets.UTF_8)
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
        /** 文件里只有一个数字；超过这个大小就是坏文件，直接当作没告知过。 */
        const val MAX_BYTES = 8 * 1024

        /** 进程级一把锁：同一会话可能有两个 run（主进程 + 语音），按实例加锁等于没加锁。 */
        private val WRITE_LOCK = Any()

        fun forApp(context: Context): BackgroundEventStore =
            BackgroundEventStore(File(context.applicationContext.filesDir, "agent/background_events"))
    }
}
