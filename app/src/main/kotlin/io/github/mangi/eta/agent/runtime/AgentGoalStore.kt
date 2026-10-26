package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentGoal
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 会话级长期目标的落盘快照。
 *
 * 与 [AgentTodoStore] 同一套取舍：单文件、原子替换、按作用域哈希命名，
 * 不引入数据库迁移。区别是 goal 带 revision，更新必须带旧 revision（乐观并发）。
 */
internal class AgentGoalStore(private val root: File) {

    fun fileFor(scopeId: String): File? {
        if (scopeId.isBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(scopeId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
            .take(32)
        return File(root, "$digest.json")
    }

    fun load(scopeId: String): AgentGoal? {
        val file = fileFor(scopeId) ?: return null
        if (!file.isFile || file.length() > MAX_BYTES) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return AgentGoal.fromStoredJson(text)
    }

    fun save(scopeId: String, goal: AgentGoal): Boolean {
        val file = fileFor(scopeId) ?: return false
        val dir = file.parentFile ?: return false
        return runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(goal.toJson().toString(), Charsets.UTF_8)
            if (file.exists() && !file.delete()) {
                tmp.delete()
                return false
            }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return false
            }
            true
        }.getOrDefault(false).also { saved -> if (saved) notifyChanged() }
    }

    fun clear(scopeId: String): Boolean {
        val file = fileFor(scopeId) ?: return false
        val cleared = runCatching { !file.exists() || file.delete() }.getOrDefault(false)
        if (cleared) notifyChanged()
        return cleared
    }

    companion object {
        const val MAX_BYTES = 16 * 1024

        private val _changes = MutableStateFlow(0L)

        /** 落盘成功自增；界面据此重新读取当前作用域的目标。 */
        val changes: StateFlow<Long> = _changes.asStateFlow()

        private fun notifyChanged() {
            _changes.update { it + 1 }
        }

        fun forApp(context: Context): AgentGoalStore =
            AgentGoalStore(File(context.applicationContext.filesDir, "agent/goals"))
    }
}
