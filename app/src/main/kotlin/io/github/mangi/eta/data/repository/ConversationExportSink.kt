package io.github.mangi.eta.data.repository

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 导出文件的落地目标：不经过系统「保存到…」选择器。
 *
 * 为什么不用 `ActivityResultContracts.CreateDocument`：那个选择器由系统决定挂在哪个显示上。
 * 2026-10-25 实测——在虚拟屏里发起导出，系统把整个任务连同选择器一起迁到主屏，
 * 直接把用户正在用的界面顶掉；虚拟屏上根本走不通。而导出真正需要的只是一个可写目标，
 * 所以默认写公共下载目录：API 29+ 走 MediaStore（不需要存储权限，虚拟屏可用），
 * 更低版本回退到应用专属目录（`Android/data/<包名>/files/exports`）。
 */
internal object ConversationExportSink {
    /** 写入目标；[finish] 必须在流关闭后调用，[abort] 用于失败时删除半截文件。 */
    data class Target(
        val stream: OutputStream,
        /** 给用户看的保存位置。 */
        val label: String,
        val finish: () -> Unit,
        val abort: () -> Unit,
    )

    private const val FOLDER = "Eta"

    fun create(context: Context, fileName: String, mimeType: String): Target {
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull()
        val stream = uri?.let { runCatching { resolver.openOutputStream(it) }.getOrNull() }
        if (uri != null && stream != null) {
            return Target(
                stream = stream,
                label = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$fileName",
                finish = {
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    runCatching { resolver.update(uri, done, null, null) }
                        .onFailure { AndroidAgentLogger.warn("conversation export: publish failed $uri") }
                },
                abort = { runCatching { resolver.delete(uri, null, null) } },
            )
        }
        uri?.let { runCatching { resolver.delete(it, null, null) } }
        AndroidAgentLogger.warn("conversation export: media store unavailable, falling back to app dir")
        return appTarget(appContext, fileName)
    }

    private fun appTarget(context: Context, fileName: String): Target {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        return Target(
            stream = FileOutputStream(file),
            label = file.absolutePath,
            finish = {},
            abort = { runCatching { file.delete() } },
        )
    }
}
