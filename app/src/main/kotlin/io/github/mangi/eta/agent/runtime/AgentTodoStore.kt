package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentTodoPlan
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 会话级任务清单的落盘快照。
 *
 * 用文件而不是数据库：清单是「最新一份整体替换」的派生状态，没有历史价值，
 * 一个文件就是一次原子替换，不需要迁移、查询或事务。
 * 作用域按会话 id 取哈希成为文件名，避免会话 id 里的任意字符进入路径。
 */
internal class AgentTodoStore(private val root: File) {

    fun fileFor(scopeId: String): File? {
        if (scopeId.isBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(scopeId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
            .take(32)
        return File(root, "$digest.json")
    }

    fun load(scopeId: String): AgentTodoPlan? {
        val file = fileFor(scopeId) ?: return null
        if (!file.isFile || file.length() > MAX_BYTES) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return AgentTodoPlan.fromStoredJson(text)
    }

    /** 空清单等价于清空：不留空文件，下一次读取就是「没有清单」。 */
    fun save(scopeId: String, plan: AgentTodoPlan): Boolean {
        if (plan.isEmpty) return clear(scopeId)
        val file = fileFor(scopeId) ?: return false
        val dir = file.parentFile ?: return false
        return runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(plan.toJson().toString(), Charsets.UTF_8)
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
        /** 24 项 × 200 字的清单远小于这个上限；超过就是坏文件，直接忽略。 */
        const val MAX_BYTES = 64 * 1024

        private val _changes = MutableStateFlow(0L)

        /**
         * 每次落盘成功自增。
         *
         * 工具在后台线程写、界面在主线程读；用一次自增代替「按消息数轮询」，
         * 因为 `todo_write` 的结果是就地更新同一条工具卡片，消息条数不会变。
         */
        val changes: StateFlow<Long> = _changes.asStateFlow()

        private fun notifyChanged() {
            _changes.update { it + 1 }
        }

        fun forApp(context: Context): AgentTodoStore =
            AgentTodoStore(File(context.applicationContext.filesDir, "agent/todos"))
    }
}
