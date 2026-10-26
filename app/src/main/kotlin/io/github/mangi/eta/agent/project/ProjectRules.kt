package io.github.mangi.eta.agent.project

import java.io.File

/**
 * 项目规则文件（参考 Claude Code 的 CLAUDE.md / Codex 的 AGENTS.md）。
 *
 * 工作区根目录下放 AGENTS.md / CLAUDE.md / ETA.md / .eta/rules.md 时，内容会进入系统提示，
 * 让模型在改代码前先看到项目约定（构建命令、代码风格、禁止事项）。
 *
 * 取第一个存在且非空的文件，最多 8000 字符。支持独占一行的 `@相对路径` 引用
 * （最多 4 个文件 / 4000 字符 / 2 层），用来把风格、构建说明拆成多个文件；
 * 引用必须落在工作区内，绝对路径、`..` 越界和循环引用都不会被展开。
 */
internal object ProjectRules {
    private val candidates = listOf("AGENTS.md", "CLAUDE.md", "ETA.md", ".eta/rules.md")
    private const val MAX_CHARS = 8_000
    private const val MAX_IMPORT_FILES = 4
    private const val MAX_IMPORT_CHARS = 4_000
    private const val MAX_IMPORT_DEPTH = 2

    /** 只认独占一行的引用，避免把正文里的 `@用户`、邮箱之类误当文件路径。 */
    private val importLine = Regex("^\\s*(?:[-*]\\s+)?@([A-Za-z0-9_./-]+)\\s*$")

    fun load(workspace: String): String {
        if (workspace.isBlank()) return ""
        val root = runCatching { File(workspace).canonicalFile }.getOrNull() ?: return ""
        if (!root.isDirectory) return ""
        for (name in candidates) {
            val file = File(root, name)
            if (!file.isFile || !file.canRead()) continue
            val body = runCatching { file.readText() }.getOrNull()
                ?.removePrefix("\uFEFF")
                ?.trim()
                .orEmpty()
            // 空文件不占位：优先继续找下一个候选，而不是把更完整的规则挡在外面。
            if (body.isEmpty()) continue
            return expand(
                text = body,
                directory = file.parentFile ?: root,
                root = root,
                visited = mutableSetOf(file.canonicalPath),
                depth = 0,
                budget = ImportBudget(),
            ).take(MAX_CHARS).trim()
        }
        return ""
    }

    private fun expand(
        text: String,
        directory: File,
        root: File,
        visited: MutableSet<String>,
        depth: Int,
        budget: ImportBudget,
    ): String {
        if (!text.contains('@')) return text
        val output = StringBuilder(text.length)
        text.lineSequence().forEach { line ->
            val match = importLine.matchEntire(line)
            val imported = if (match != null && depth < MAX_IMPORT_DEPTH) {
                loadImport(match.groupValues[1], directory, root, visited, depth, budget)
            } else {
                null
            }
            if (imported != null) {
                output.append(imported)
                if (!imported.endsWith("\n")) output.append('\n')
            } else {
                output.append(line).append('\n')
            }
        }
        return output.toString().trimEnd('\n')
    }

    private fun loadImport(
        reference: String,
        directory: File,
        root: File,
        visited: MutableSet<String>,
        depth: Int,
        budget: ImportBudget,
    ): String? {
        if (budget.files <= 0 || budget.chars <= 0) return null
        val target = runCatching { File(directory, reference).canonicalFile }.getOrNull() ?: return null
        if (!target.path.startsWith(root.path + File.separator)) return null
        if (!target.isFile || !target.canRead()) return null
        if (!visited.add(target.path)) return null
        budget.files -= 1
        val body = runCatching { target.readText() }.getOrNull()
            ?.removePrefix("\uFEFF")
            ?.trim()
            .orEmpty()
        if (body.isEmpty()) return null
        val nested = expand(body, target.parentFile ?: directory, root, visited, depth + 1, budget)
        val clipped = nested.take(minOf(MAX_IMPORT_CHARS, budget.chars))
        if (clipped.isEmpty()) return null
        budget.chars -= clipped.length
        val relative = target.path.removePrefix(root.path + File.separator)
        return "<!-- 引用 $relative -->\n$clipped"
    }

    private class ImportBudget(
        var files: Int = MAX_IMPORT_FILES,
        var chars: Int = MAX_IMPORT_CHARS,
    )
}
