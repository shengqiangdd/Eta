package io.github.mangi.eta.agent.runtime

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「这条后台任务已经续过」的落盘记忆，按会话作用域分文件。
 *
 * 为什么需要落盘：卡片里的续跑状态原来只活在进程里，App 被回收再打开，
 * 「继续」按钮又出现在同一批任务上 —— 用户点过一次的任务被当成没点过。
 *
 * 只存 id，**不存**自动续跑次数：次数是「这一轮对话的预算」，跟着进程走才对。
 * 落盘会让「自动续了 2 次」在重启后继续有效，等于超一次就永久停用（与 §122 同一类取舍）。
 *
 * 与 [BackgroundEventStore] 同一套取舍：作用域哈希命名、进程级一把写锁、`.tmp` 换名、
 * 坏文件按「没有记录」处理。返回 Boolean 而不是抛异常：这是旁路存储，写不进去不该让界面失败。
 */
internal class BackgroundResumeStore(private val root: File) {

    fun fileFor(scopeId: String): File? {
        if (scopeId.isBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(scopeId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
            .take(32)
        return File(root, "$digest.json")
    }

    /**
     * 已经续过的任务 id，最近的排在末尾。
     *
     * 空作用域、文件不存在、超过大小上限、JSON 坏、字段类型不对 —— 一律当「没有记录」，
     * 不抛异常：这个记忆只用来少显示一个按钮，读不出来就退回原来的行为。
     */
    fun load(scopeId: String): List<String> {
        val file = fileFor(scopeId) ?: return emptyList()
        if (!file.isFile || file.length() > MAX_BYTES) return emptyList()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        val stored = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val array = stored.optJSONArray("resumed") ?: return emptyList()
        val raw = (0 until array.length()).map { index -> array.optString(index) }
        return sanitize(raw)
    }

    /** 覆盖写入；只保留最后 [MAX_IDS] 条。空作用域直接返回 false（不落盘）。 */
    fun save(scopeId: String, ids: List<String>): Boolean {
        val file = fileFor(scopeId) ?: return false
        val cleaned = sanitize(ids)
        synchronized(WRITE_LOCK) { return write(file, cleaned) }
    }

    /** 去掉空白、去重（保留**最近**的一次出现）、只留最后 [MAX_IDS] 条。 */
    private fun sanitize(ids: List<String>): List<String> =
        ids.asReversed()
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_IDS)
            .asReversed()

    private fun write(file: File, ids: List<String>): Boolean {
        val dir = file.parentFile ?: return false
        return runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val payload = JSONObject().put("resumed", JSONArray(ids)).toString()
            // 加了上限还超限，说明单条 id 长得离谱；宁可不写，也不留一个读不回来的文件。
            if (payload.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return false
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(payload, Charsets.UTF_8)
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
        /** 文件里最多 64 个 id；超过这个大小就是坏文件，直接当作没有记录。 */
        const val MAX_BYTES = 8 * 1024

        /** 每个作用域记多少条。任务 id 会随记录清理消失，留太多只是白占空间。 */
        const val MAX_IDS = 64

        /** 进程级一把锁：同一会话可能有两个 run（主进程 + 语音），按实例加锁等于没加锁。 */
        private val WRITE_LOCK = Any()

        fun forApp(context: Context): BackgroundResumeStore =
            BackgroundResumeStore(File(context.applicationContext.filesDir, "agent/background_resume"))
    }
}
