package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.agent.tool.requiredText
import org.json.JSONArray
import org.json.JSONObject

internal object FileToolDispatcher {
    fun execute(
        backend: FileToolBackend,
        name: String,
        args: JSONObject,
        ledger: FileAccessLedger? = null,
        backups: FileBackupStore? = null,
    ): String {
        val operations = AgentFileOperations(backend, ledger, backups)
        val path = if (args.has("path")) args.requiredText("path") else ""
        if ("://" in path) throw FileToolException("UNSUPPORTED_FILE_REFERENCE", "请使用已授权的文件系统路径；文档 URI 需先导入，导入路径对应副本")
        val revision = args.optString("expected_revision").takeIf(String::isNotBlank)
        return when (name) {
            "stat_file" -> operations.statFile(args.requiredText("path"))
            "read_file" -> operations.readFile(
                path = args.requiredText("path"),
                offsetBytes = args.optLong("offset_bytes", 0),
                maxBytes = args.optInt("max_bytes", 16_000),
                startLine = if (args.has("start_line")) args.getInt("start_line") else null,
                maxLines = args.optInt("max_lines", 200),
                expectedRevision = revision,
            )
            "write_file" -> operations.writeFile(
                path = args.requiredText("path"),
                content = args.requiredText("content"),
                append = args.optBoolean("append", false),
                expectedRevision = revision,
                dryRun = args.optBoolean("dry_run", false),
            )
            "edit_file" -> operations.editFile(
                path = args.requiredText("path"),
                oldText = if (args.has("old_text")) args.requiredText("old_text") else null,
                newText = if (args.has("new_text")) args.requiredText("new_text") else null,
                replaceAll = args.optBoolean("replace_all", false),
                expectedRevision = revision,
                edits = parseEdits(args.optJSONArray("edits")),
                dryRun = args.optBoolean("dry_run", false),
            )
            "edit_files" -> operations.editFiles(
                patches = parsePatches(args.optJSONArray("files")),
                dryRun = args.optBoolean("dry_run", false),
            )
            "list_file_backups" -> operations.listFileBackups(path, args.optInt("limit", 20))
            "restore_file" -> operations.restoreFile(
                path = path,
                backupId = args.optString("backup_id").takeIf(String::isNotBlank),
                expectedRevision = revision,
            )
            "project_info" -> operations.projectInfo(path)
            "list_directory" -> operations.listDirectory(path, args.optBoolean("show_hidden", false), args.optInt("limit", 80), args.optInt("offset", 0), revision)
            "glob_files" -> operations.globFiles(path, args.requiredText("pattern"), args.optInt("limit", 80), args.optString("cursor").takeIf(String::isNotEmpty), args.optBoolean("include_hidden", false))
            "grep_files" -> {
                // `pattern` 是 glob_files 的参数，跟 query 只差一个词，模型经常传错（日志里 8 次）。
                // 只报「query 缺失或不是文本」不足以让它改对，这里点明两者分工。
                if (!args.has("query") && args.has("pattern")) {
                    throw FileToolException(
                        "INVALID_ARGUMENT",
                        "grep_files 用 query 传要搜索的文本；pattern 是 glob_files 的路径模式参数。" +
                            "按文件路径找文件请用 glob_files(pattern=…)，搜内容请用 " +
                            "grep_files(query=…, glob=…)。",
                    )
                }
                operations.grepFiles(
                    path = path,
                    query = args.requiredText("query"),
                    glob = args.optString("glob", "**/*"),
                    limit = args.optInt("limit", 50),
                    cursor = args.optString("cursor").takeIf(String::isNotEmpty),
                    includeHidden = args.optBoolean("include_hidden", false),
                    caseSensitive = args.optBoolean("case_sensitive", true),
                    regex = args.optBoolean("regex", false),
                    filesOnly = args.optBoolean("files_only", false),
                )
            }
            else -> throw FileToolException("UNKNOWN_TOOL", "未知文件工具")
        }
    }

    private fun parsePatches(array: JSONArray?): List<FilePatch> {
        if (array == null) return emptyList()
        if (array.length() > 20) throw FileToolException("TOO_MANY_FILES", "单次最多 20 个文件")
        return (0 until array.length()).map { index ->
            val item = array.optJSONObject(index)
                ?: throw FileToolException("INVALID_ARGUMENT", "files[$index] 必须是对象")
            FilePatch(
                path = item.requiredText("path"),
                edits = parseEdits(item.optJSONArray("edits")),
                expectedRevision = item.optString("expected_revision").takeIf(String::isNotBlank),
            )
        }
    }

    private fun parseEdits(array: JSONArray?): List<FileEdit> {
        if (array == null) return emptyList()
        if (array.length() > 50) throw FileToolException("TOO_MANY_EDITS", "单次最多 50 处编辑；请拆分批次")
        return (0 until array.length()).map { index ->
            val item = array.optJSONObject(index)
                ?: throw FileToolException("INVALID_ARGUMENT", "edits[$index] 必须是对象")
            FileEdit(
                oldText = item.requiredText("old_text"),
                newText = item.requiredText("new_text"),
                replaceAll = item.optBoolean("replace_all", false),
            )
        }
    }
}
