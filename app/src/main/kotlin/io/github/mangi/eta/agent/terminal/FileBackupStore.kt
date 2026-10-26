package io.github.mangi.eta.agent.terminal

import org.json.JSONObject

/** 一次工具写入前的文件快照；id 只在同一运行/存储内有效。 */
internal data class FileBackupEntry(
    val id: String,
    val environment: String,
    val identity: String,
    val path: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val tool: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("environment", environment)
        .put("identity", identity)
        .put("path", path)
        .put("size_bytes", sizeBytes)
        .put("created_at", createdAt)
        .put("tool", tool)

    companion object {
        fun fromJson(json: JSONObject): FileBackupEntry? {
            val id = json.optString("id").trim()
            val path = json.optString("path").trim()
            if (id.isEmpty() || path.isEmpty()) return null
            return FileBackupEntry(
                id = id,
                environment = json.optString("environment"),
                identity = json.optString("identity"),
                path = path,
                sizeBytes = json.optLong("size_bytes", 0L),
                createdAt = json.optLong("created_at", 0L),
                tool = json.optString("tool"),
            )
        }
    }
}

/**
 * 文件检查点存储：写入/编辑前保存原文，供 `restore_file` 回滚。
 *
 * 存储不可用时返回 null / 空列表，调用方必须如实说明「没有备份」，
 * 不能把「没有备份」伪装成「已备份」。
 */
internal interface FileBackupStore {
    fun save(environment: String, identity: String, path: String, bytes: ByteArray, tool: String): String?

    fun list(environment: String, identity: String, path: String, limit: Int = 20): List<FileBackupEntry>

    fun load(id: String): ByteArray?
}
