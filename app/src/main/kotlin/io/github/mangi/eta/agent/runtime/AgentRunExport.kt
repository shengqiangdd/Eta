package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.trace.AgentRunTraceStore
import io.github.mangi.eta.agent.trace.AgentRunTrajectory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次运行的 Markdown 报告（对齐 dsh 的 session-log-export，但形态不同）。
 *
 * dsh 在 Web 端把会话日志与附件打成一个 ZIP 流给浏览器下载；Eta 没有 Web 端，
 * 导出物要能直接发出去（聊天、邮件、文件管理器），所以选单文件 Markdown：
 * 手机上的阅读器都能打开，不依赖解压工具，也不会因为附件缺失而变成半个包。
 *
 * 取舍写清楚：报告是**摘要**，工具参数与结果各压成一行；完整原文仍在运行详情的卡片里。
 * 文件变更只包含 [AgentRunFileChanges] 账本里的条目 —— 也就是 write_file / edit_file 改过的文件。
 */
internal object AgentRunExport {

    /** 单份报告上限：再长就不是「发出去看一眼」的东西了。 */
    const val MAX_CHARS = 200_000

    /** 工具结果/参数压成一行时各自保留的字数。 */
    private const val DETAIL_CHARS = 160
    private const val ARGUMENT_CHARS = 100

    fun fileName(runId: String, at: Long = System.currentTimeMillis()): String {
        val token = runId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(40)
            .ifBlank { "run" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
        return "eta-run-$token-$stamp.md"
    }

    fun markdown(
        trace: AgentRunTraceStore.Trace,
        changes: List<RunFileChange> = emptyList(),
        exportedAt: Long = System.currentTimeMillis(),
    ): String {
        val trajectory = AgentRunTrajectory.build(trace)
        val builder = StringBuilder(4096)
        var stopped = false

        fun line(text: String = "") {
            if (stopped) return
            if (builder.length + text.length + 1 > MAX_CHARS) {
                builder.appendLine()
                builder.appendLine("…（报告超过 ${MAX_CHARS / 1000}k 字符上限，后续内容已省略）")
                stopped = true
                return
            }
            builder.appendLine(text)
        }

        line("# Eta 运行记录")
        line()
        line("- 运行 ID：`${trace.runId}`")
        line(
            "- 模型：`${trace.model.ifBlank { "未知" }}`" +
                if (trace.provider.isBlank()) "" else "（${trace.provider}）",
        )
        line("- 类型：${purposeLabel(trace.purpose)}")
        line("- 开始：${time(trace.startedAt)}")
        line(
            "- 结束：${trace.finishedAt?.let(::time) ?: "未记录"} · 用时 ${duration(trace.durationMs)}",
        )
        line("- 结果：${resultLabel(trace)}")
        line(
            "- 统计：${trajectory.turns.size} 轮 · ${trace.toolCalls} 次工具" +
                (if (trace.toolFailures > 0) "（失败 ${trace.toolFailures}）" else "") +
                " · 重试 ${trace.retries} · token ${count(trace.totalTokens)}",
        )
        line(
            "- 记录：${trace.entries.size} 条" +
                (if (trace.truncated) "（超过单文件上限，后段缺失）" else ""),
        )

        line()
        line("## 时间线")
        if (trajectory.turns.isEmpty()) {
            line()
            line("这次运行没有留下可读的记录。")
        }
        trajectory.turns.forEach { turn ->
            line()
            line(
                "### 第 ${turn.index + 1} 轮 · +${duration(turn.startMs - originOf(trace))} · " +
                    duration(turn.durationMs),
            )
            turn.entries.forEach { ref ->
                val entry = ref.entry
                if (entry.kind == AgentRunTraceStore.KIND_ROUND) return@forEach
                describe(entry, ref.offsetMs)?.let { line("- $it") }
            }
        }

        if (changes.isNotEmpty()) {
            line()
            line("## 文件变更")
            line()
            line("共 ${changes.size} 个文件，+${changes.sumOf { it.added }}/-${changes.sumOf { it.removed }} 行。")
            changes.forEach { change ->
                line()
                line(
                    "### `${change.path}`（+${change.added}/-${change.removed}" +
                        (if (change.count > 1) "，${change.count} 次" else "") + "）",
                )
                line()
                if (change.diff.isBlank()) {
                    line("这次只记到行数（原文过大或不可读，未生成逐行对照）。")
                } else {
                    line("```text")
                    change.diff.lineSequence().forEach { diffLine -> line(diffLine) }
                    line("```")
                }
            }
        }

        line()
        line("---")
        line()
        line(
            "由 Eta 生成于 ${time(exportedAt)}。这是摘要：工具参数与结果各压成一行，完整原文在运行详情里；" +
                "文件变更只统计 write_file / edit_file，shell 重定向与其他应用写的文件不在其中。",
        )
        return builder.toString()
    }

    /** 单条记录的 Markdown 列表项；返回 null 表示这类记录不进报告（例如 meta / thinking）。 */
    private fun describe(entry: AgentRunTraceStore.Entry, offsetMs: Long): String? {
        val at = "`+${duration(offsetMs)}`"
        return when (entry.kind) {
            AgentRunTraceStore.KIND_START -> "运行开始"
            AgentRunTraceStore.KIND_REQUEST -> "$at 模型请求 · 耗时 ${duration(entry.elapsedMs ?: 0)}"
            AgentRunTraceStore.KIND_RESPONSE -> "$at 收到响应 · HTTP ${entry.httpCode ?: 0}"
            AgentRunTraceStore.KIND_THINKING -> "$at 思考 · ${count(entry.chars ?: 0)} 字符"
            AgentRunTraceStore.KIND_TEXT -> "$at 正文输出 · ${count(entry.chars ?: 0)} 字符"
            AgentRunTraceStore.KIND_TOOL -> buildString {
                append(at).append(" 工具 `").append(entry.name.orEmpty()).append('`')
                entry.elapsedMs?.let { append(" · ").append(duration(it)) }
                append(if (entry.ok == false) " · 失败" else " · 成功")
                val arguments = compact(entry.arguments, ARGUMENT_CHARS)
                if (arguments.isNotEmpty()) append(" · ").append(arguments)
                val result = compact(entry.result, DETAIL_CHARS)
                if (result.isNotEmpty()) append(" — ").append(result)
            }
            AgentRunTraceStore.KIND_RETRY -> "$at 重试 ${entry.attempt ?: 0}/${entry.maxAttempts ?: 0}" +
                " · 等待 ${duration(entry.delayMs ?: 0)} · ${entry.code.orEmpty()}"
            AgentRunTraceStore.KIND_USAGE -> "$at token 用量 · 上下文 ${count(entry.contextTokens ?: 0)}" +
                " · 输入 ${count(entry.inputTokens ?: 0)} · 输出 ${count(entry.outputTokens ?: 0)}" +
                " · 缓存 ${count(entry.cachedTokens ?: 0)}"
            AgentRunTraceStore.KIND_COMPACT -> buildString {
                append(at).append(' ')
                append(if (entry.phase == "completed") "上下文已压缩" else "正在压缩上下文")
                append(" · ")
                append(
                    if (entry.charsBefore != null || entry.charsAfter != null) {
                        "${count(entry.charsBefore ?: 0)} → ${count(entry.charsAfter ?: 0)} 字符"
                    } else {
                        // 旧追踪没有字符数，只能回退到 token 口径。
                        "${count(entry.before ?: 0)} → ${count(entry.after ?: 0)} token"
                    },
                )
                // 耗时是压缩体验的关键信号（真机实测一度 44.9 秒），有就带上。
                entry.elapsedMs?.takeIf { it > 0 }?.let { append(" · 用时 ").append(duration(it)) }
            }
            AgentRunTraceStore.KIND_DONE -> buildString {
                append(at).append(if (entry.ok == true) " 运行完成" else " 运行结束")
                entry.error?.takeIf(String::isNotBlank)?.let { append(" · ").append(compact(it, DETAIL_CHARS)) }
            }
            AgentRunTraceStore.KIND_TRUNCATED -> "$at 记录超过单文件上限，后段未写入"
            else -> null
        }
    }

    private fun purposeLabel(purpose: String): String = when (purpose) {
        "chat" -> "对话"
        "rewrite" -> "改写回复"
        "compact" -> "压缩上下文"
        "" -> "未标注"
        else -> purpose
    }

    private fun resultLabel(trace: AgentRunTraceStore.Trace): String = when (trace.ok) {
        true -> "成功"
        false -> "失败" + (trace.error?.takeIf(String::isNotBlank)?.let { "：${compact(it, 300)}" } ?: "")
        null -> "未记录（运行可能被中断）"
    }

    private fun originOf(trace: AgentRunTraceStore.Trace): Long =
        trace.startedAt.takeIf { it > 0 } ?: trace.entries.firstOrNull()?.at ?: 0L

    /** 报告里的内容必须压成一行：换行会打断列表结构，长文本会淹没整份报告。 */
    private fun compact(text: String?, maxChars: Int): String {
        if (text.isNullOrBlank()) return ""
        val flat = text.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s{2,}"), " ").trim()
        return if (flat.length <= maxChars) flat else flat.take(maxChars) + "…"
    }

    private fun time(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

    private fun duration(ms: Long): String {
        val value = ms.coerceAtLeast(0)
        return when {
            value < 1_000 -> "${value}ms"
            value < 60_000 -> String.format(Locale.US, "%.1fs", value / 1000.0)
            else -> {
                val totalSeconds = value / 1000
                "${totalSeconds / 60}m${totalSeconds % 60}s"
            }
        }
    }

    private fun count(value: Int): String = when {
        value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 10_000 -> String.format(Locale.US, "%.1fk", value / 1000.0)
        else -> value.toString()
    }
}
