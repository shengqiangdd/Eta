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
 * 界面动作写回目标的结果。
 *
 * 与 `goal` 工具的 `GOAL_STALE` / `GOAL_NOT_FOUND` / `GOAL_PERSIST_FAILED` 同义，
 * 区别只是这条路径不经过模型：用户点了按钮，失败要当场告诉用户。
 */
internal enum class GoalMutation {
    SAVED,
    MISSING,
    STALE,
    INVALID,
    FAILED,
}

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

    fun save(scopeId: String, goal: AgentGoal): Boolean = synchronized(writeLock) {
        write(scopeId, goal)
    }

    /**
     * 界面动作的读-改-写：在同一把锁里重读最新目标，界面上看到的 revision 没被改动才写。
     *
     * 不做「读到什么就写什么」：界面持有的是渲染时那一版，模型可能刚用 `goal update` 改过。
     * revision 不一致就返回 [GoalMutation.STALE]，由界面提示重试，而不是悄悄覆盖模型的修改。
     */
    fun mutate(
        scopeId: String,
        expectedRevision: Int,
        transform: (AgentGoal) -> AgentGoal,
    ): GoalMutation = synchronized(writeLock) {
        val current = load(scopeId) ?: return@synchronized GoalMutation.MISSING
        if (current.revision != expectedRevision) return@synchronized GoalMutation.STALE
        val next = transform(current)
        val objective = next.objective.trim()
        if (objective.isEmpty() || objective.length > AgentGoal.MAX_OBJECTIVE_CHARS) {
            return@synchronized GoalMutation.INVALID
        }
        val stamped = next.copy(
            objective = objective,
            revision = current.revision + 1,
            updatedAt = System.currentTimeMillis(),
        )
        if (write(scopeId, stamped)) GoalMutation.SAVED else GoalMutation.FAILED
    }

    /** 工具侧的「读-校验-写」也走这把锁，免得与界面动作各自从同一 revision 出发、后写覆盖先写。 */
    fun <T> withLock(block: () -> T): T = synchronized(writeLock) { block() }

    private fun write(scopeId: String, goal: AgentGoal): Boolean {
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

    fun clear(scopeId: String): Boolean = synchronized(writeLock) {
        val file = fileFor(scopeId) ?: return@synchronized false
        val cleared = runCatching { !file.exists() || file.delete() }.getOrDefault(false)
        if (cleared) notifyChanged()
        return cleared
    }

    companion object {
        const val MAX_BYTES = 16 * 1024

        /** 进程内唯一的写锁：界面动作、工具写回都从这里过，避免同一 revision 的双写。 */
        private val writeLock = Any()

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
