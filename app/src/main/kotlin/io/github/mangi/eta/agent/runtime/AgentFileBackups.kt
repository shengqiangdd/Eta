package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.terminal.FileBackupEntry
import io.github.mangi.eta.agent.terminal.FileBackupStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray

/**
 * 作用域级文件检查点（通常按会话）：每次写入/编辑前把原文存到 App 私有目录，供 `restore_file` 回滚。
 *
 * 与 [AgentRunFileChanges] 一样只覆盖「工具自己写的文件」，Shell 重定向、其他应用写入
 * 都看不见。存储按作用域分目录，单次备份上限 512 KiB，每个作用域最多 40 份 / 16 MiB，
 * 只保留最近 24 个作用域目录，避免无限增长。
 */
internal class AgentFileBackups private constructor(
    private val dir: File,
) : FileBackupStore {

    companion object {
        private const val MAX_ENTRIES = 40
        private const val MAX_TOTAL_BYTES = 16L * 1024 * 1024
        private const val MAX_BACKUP_BYTES = 512 * 1024
        private const val MAX_RUN_DIRS = 24
        private const val MAX_INDEX_BYTES = 256L * 1024
        private val LOCK = Any()

        fun forApp(context: Context, runId: String): AgentFileBackups? =
            forRoot(File(context.filesDir, "agent/file_backups"), runId)

        /** 测试入口：直接指定存储根目录，不依赖 Android Context。 */
        internal fun forDirectory(root: File, runId: String): AgentFileBackups? = forRoot(root, runId)

        private fun forRoot(root: File, runId: String): AgentFileBackups? {
            if (runId.isBlank()) return null
            val dir = File(root, token(runId))
            if (!dir.isDirectory && !dir.mkdirs()) return null
            pruneRunDirs(root)
            return AgentFileBackups(dir)
        }

        /** 运行目录名不直接使用 runId，避免路径字符与超长名称。 */
        private fun token(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                .take(32)

        private fun pruneRunDirs(root: File) {
            val dirs = root.listFiles { file -> file.isDirectory } ?: return
            dirs.sortedByDescending { it.lastModified() }.drop(MAX_RUN_DIRS).forEach { stale ->
                runCatching { stale.deleteRecursively() }
            }
        }
    }

    override fun save(
        environment: String,
        identity: String,
        path: String,
        bytes: ByteArray,
        tool: String,
    ): String? = synchronized(LOCK) {
        if (bytes.size > MAX_BACKUP_BYTES) return null
        val id = UUID.randomUUID().toString()
        val target = File(dir, "$id.bin")
        if (!writeAtomically(target, bytes)) return null
        val entries = loadIndex().toMutableList()
        entries += FileBackupEntry(
            id = id,
            environment = environment,
            identity = identity,
            path = path,
            sizeBytes = bytes.size.toLong(),
            createdAt = System.currentTimeMillis(),
            tool = tool,
        )
        val kept = pruneEntries(entries)
        if (!saveIndex(kept)) {
            target.delete()
            return null
        }
        id
    }

    override fun list(
        environment: String,
        identity: String,
        path: String,
        limit: Int,
    ): List<FileBackupEntry> = synchronized(LOCK) {
        loadIndex()
            .filter { it.environment == environment && it.identity == identity && it.path == path }
            .sortedByDescending { it.createdAt }
            .take(limit.coerceIn(1, 50))
    }

    override fun load(id: String): ByteArray? = synchronized(LOCK) {
        if (!id.matches(Regex("[0-9a-fA-F-]{36}"))) return null
        val entry = loadIndex().firstOrNull { it.id == id } ?: return null
        val file = File(dir, "${entry.id}.bin")
        if (!file.isFile || file.length() > MAX_BACKUP_BYTES) return null
        runCatching { file.readBytes() }.getOrNull()
    }

    private fun pruneEntries(entries: List<FileBackupEntry>): List<FileBackupEntry> {
        var total = 0L
        val kept = entries.sortedByDescending { it.createdAt }.filterIndexed { index, entry ->
            total += entry.sizeBytes
            index < MAX_ENTRIES && total <= MAX_TOTAL_BYTES
        }
        entries.filter { it !in kept }.forEach { stale -> File(dir, "${stale.id}.bin").delete() }
        return kept
    }

    private fun loadIndex(): List<FileBackupEntry> {
        val file = File(dir, "index.json")
        if (!file.isFile || file.length() > MAX_INDEX_BYTES) return emptyList()
        val array = runCatching { JSONArray(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            FileBackupEntry.fromJson(array.optJSONObject(index) ?: return@mapNotNull null)
        }
    }

    private fun saveIndex(entries: List<FileBackupEntry>): Boolean = runCatching {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        writeAtomically(File(dir, "index.json"), array.toString().toByteArray(Charsets.UTF_8))
    }.getOrDefault(false)

    private fun writeAtomically(target: File, bytes: ByteArray): Boolean = runCatching {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        true
    }.getOrDefault(false)
}
