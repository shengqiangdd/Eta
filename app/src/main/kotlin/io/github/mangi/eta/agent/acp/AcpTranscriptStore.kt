package io.github.mangi.eta.agent.acp

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ACP 委派记录的落盘位置。
 *
 * 目录：`files/acp-transcripts/`，每条一个 `.md`。选 Markdown 不是为了好看，
 * 是因为用户多半会把文件直接丢给编辑器或另一个模型 —— 纯文本没法一眼分出
 * 「代理说了什么」和「它跑了哪些工具」。
 *
 * 为什么在这里（而不是 cache）：这是**产出**不是缓存。清理缓存不该把
 * 「上周让它改了三个文件」的记录一起删掉。
 */
internal object AcpTranscriptStore {

    private const val DIR = "acp-transcripts"

    /** 保留条数。一次委派几 KB 到几百 KB，留 40 条够回看，也不至于把存储堆满。 */
    const val KEEP = 40

    fun dir(context: Context): File = File(context.filesDir, DIR)

    /**
     * 写一条记录并返回**宿主侧**绝对路径（App 进程能直接读的那个）。
     *
     * 不返回 Linux 侧路径：委派记录对用户的意义是「在 Eta 里能打开」，
     * 而 `/workspace` 映射到哪块盘是环境配置决定的，写死反而会指错。
     */
    fun write(context: Context, transcript: AcpTranscript): String? {
        val dir = dir(context)
        return runCatching {
            if (!dir.exists() && !dir.mkdirs()) return null
            val file = File(dir, fileName(transcript))
            file.writeText(render(transcript), Charsets.UTF_8)
            prune(dir)
            file.absolutePath
        }.getOrNull()
    }

    /**
     * 文件名带时间与短会话 id，方便按时间排序；不用纯 sessionId，
     * 因为用户在文件管理器里看到一串十六进制完全不知道该不该打开。
     */
    private fun fileName(transcript: AcpTranscript): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(Date(transcript.startedAtMs))
        val shortId = transcript.sessionId.takeLast(6)
        val agent = transcript.agentId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24)
        return "$stamp-$agent-$shortId.md"
    }

    /**
     * 渲染成 Markdown。
     *
     * 关键一条：**明确写出结果里被截断了多少**。用户看到结果卡上只有一段尾巴时，
     * 最容易的误判是「代理就回了这么点」；文件头把总字数与截断状态摆出来，
     * 才不会把「工具截断」当成「代理偷懒」。
     */
    fun render(transcript: AcpTranscript): String = buildString {
        appendLine("# ACP 委派记录")
        appendLine()
        appendLine("| 项 | 值 |")
        appendLine("| --- | --- |")
        appendLine("| 代理 | ${transcript.agentId} |")
        appendLine("| 模型 | ${transcript.model ?: "（默认）"} |")
        appendLine("| 会话 | ${transcript.sessionId} |")
        appendLine("| 开始 | ${formatTime(transcript.startedAtMs)} |")
        appendLine("| 耗时 | ${transcript.elapsedMs / 1000.0} 秒 |")
        appendLine("| 结果 | ${if (transcript.ok) "成功" else "失败"} |")
        appendLine("| 停止原因 | ${transcript.stopReason ?: "（未提供）"} |")
        appendLine("| 正文字数 | ${transcript.text.length} |")
        appendLine("| 工具记录字数 | ${transcript.tools.length} |")
        if (transcript.textTruncatedInResult) {
            appendLine()
            appendLine(
                "> 结果卡上的正文被截断了（上限 12,000 字符），**本文件是完整原文**。",
            )
        }
        appendLine()
        appendLine("## 任务")
        appendLine()
        appendLine(transcript.task.ifBlank { "（空）" })
        appendLine()
        appendLine("## 工具调用")
        appendLine()
        appendLine("```")
        appendLine(transcript.tools.ifBlank { "（没有工具调用）" }.trimEnd())
        appendLine("```")
        appendLine()
        appendLine("## 正文")
        appendLine()
        appendLine(transcript.text.ifBlank { "（没有正文输出）" }.trimEnd())
        if (transcript.stderrTail.isNotBlank()) {
            appendLine()
            appendLine("## stderr 尾部")
            appendLine()
            appendLine("```")
            appendLine(transcript.stderrTail.trimEnd())
            appendLine("```")
        }
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    /** 超出 [KEEP] 就删最旧的。按文件名排序即可 —— 前缀就是时间戳。 */
    private fun prune(dir: File) {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".md") } ?: return
        if (files.size <= KEEP) return
        files.sortedBy { it.name }
            .take(files.size - KEEP)
            .forEach { runCatching { it.delete() } }
    }
}
