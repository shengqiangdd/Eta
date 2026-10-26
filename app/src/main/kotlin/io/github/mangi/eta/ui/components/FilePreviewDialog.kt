package io.github.mangi.eta.ui.components

import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 预览结果：失败态由读取端判定，界面只负责样式，避免用文案前缀猜状态。 */
internal data class FilePreview(val text: String, val failed: Boolean)

/**
 * 文件只读预览：点路径直接看内容，不必只靠 diff 猜。
 *
 * 读取走「直接 File → 失败再 su cat」：App 私有目录可以直接读；
 * /workspace、/data 这类路径只有 root 能读，而 Eta 的 root 是用户明确授权的。
 * 预览有上限（200KB / 400 行），避免把整块二进制读进内存。
 */
internal object FilePreviewReader {
    private const val MAX_BYTES = 200_000
    private const val MAX_LINES = 400

    /** 文案解析：让纯逻辑单测注入假文案，不依赖 Android Resources。 */
    fun interface TextResolver {
        fun get(id: Int, args: Array<out Any>): String
    }

    fun read(resources: Resources, path: String): FilePreview =
        read(path) { id, args -> resources.getString(id, *args) }

    internal fun read(path: String, text: TextResolver): FilePreview {
        if (path.isBlank()) return FilePreview(text.get(R.string.file_preview_path_empty, emptyArray()), false)
        val direct = runCatching { readDirect(text, path) }.getOrNull()
        if (direct != null) return FilePreview(limit(text, direct), false)
        val viaRoot = runCatching { readWithRoot(text, path) }.getOrElse { error ->
            return FilePreview(
                text.get(R.string.file_preview_read_failed, arrayOf(error.message ?: error.javaClass.simpleName)),
                true,
            )
        }
        return FilePreview(limit(text, viaRoot), false)
    }

    private fun readDirect(text: TextResolver, path: String): String {
        val file = java.io.File(path)
        require(file.isFile && file.canRead()) { text.get(R.string.file_preview_no_permission, emptyArray()) }
        return file.inputStream().buffered().use { input ->
            val buffer = ByteArray(MAX_BYTES + 1)
            var total = 0
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            String(buffer, 0, minOf(total, MAX_BYTES), Charsets.UTF_8)
        }
    }

    private fun readWithRoot(text: TextResolver, path: String): String {
        val quoted = "'" + path.replace("'", "'\\''") + "'"
        val process = ProcessBuilder("su", "-c", "head -c $MAX_BYTES -- $quoted")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        if (code != 0 && output.isBlank()) error(text.get(R.string.file_preview_root_failed, arrayOf(code)))
        return output
    }

    private fun limit(texts: TextResolver, text: String): String {
        // 二进制文件（NUL 或大量控制字符）直接说明，别把整屏乱码塞给用户。
        val sample = text.take(4_096)
        val control = sample.count { it.code < 0x09 || it.code in 0x0E..0x1F }
        if (sample.isNotEmpty() && control.toFloat() / sample.length > 0.1f) {
            return texts.get(R.string.file_preview_binary, emptyArray())
        }
        val lines = text.lineSequence().take(MAX_LINES).toList()
        val joined = lines.joinToString("\n")
        val truncated = text.length > joined.length || text.lineSequence().count() > MAX_LINES
        return if (truncated) joined + texts.get(R.string.file_preview_truncated_suffix, emptyArray()) else joined
    }
}

@Composable
internal fun FilePreviewDialog(
    path: String,
    onDismiss: () -> Unit,
) {
    var content by remember(path) { mutableStateOf<String?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val resources = LocalResources.current
    LaunchedEffect(path) {
        val result = withContext(Dispatchers.IO) {
            runCatching { FilePreviewReader.read(resources, path) }
                .getOrElse {
                    FilePreview(resources.getString(R.string.file_preview_read_failed, it.message ?: it.javaClass.simpleName), true)
                }
        }
        failed = result.failed
        content = result.text
    }
    EtaWindowDialog(
        show = true,
        title = path.substringAfterLast('/').ifBlank { stringResource(R.string.file_preview_title) },
        summary = if (path.length <= 80) path else "…" + path.takeLast(77),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            EtaTextButton(
                text = stringResource(R.string.file_preview_copy_path),
                onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(path))
                },
            )
            Text(
                text = content ?: stringResource(R.string.file_preview_loading),
                style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                color = if (failed) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        MiuixDialogActions(
            confirmText = stringResource(R.string.file_preview_close),
            onConfirm = onDismiss,
            onCancel = onDismiss,
        )
    }
}
