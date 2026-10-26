package io.github.mangi.eta.ui.app

import android.content.Context
import android.os.Environment
import androidx.core.content.edit
import io.github.mangi.eta.agent.device.RootAccess
import java.io.File

/**
 * 记住导入 / 导出选择器上次停留的目录。
 *
 * 为什么需要：选择器每次都从共享存储根（`/storage/emulated/0`）开始，而用户翻来覆去
 * 用的就是同一个目录（导出到 `Download/xxx` 几乎每次都是）。不记忆的话，每次都要
 * 从头一层层点进去，一个「点一下就能完成」的动作被打断成七八次点击。
 *
 * 边界与取舍：
 * - **只存路径字符串，不在读取时做可访问性预检**。无「所有文件访问」时
 *   `File.isDirectory()` 对公共目录恒为 false，预检会把记忆值白白丢掉 ——
 *   而选择器自己能通过 root 列出该目录。真的不可用时，选择器会显示「无法列出该目录」
 *   并提供「返回上级目录」，同时禁用「使用此文件夹」—— 不比预检更糟。
 * - 只记录**用户主动选中的目录**：导入记的是所选文件的父目录，导出记的是目标目录本身。
 * - 与安全判断无关：这里不参与任何权限或路径合法性判定。
 */
internal object WorkspacePickerHistory {
    private const val PREFS = "eta-workspace-picker"
    private const val KEY_IMPORT = "last_import_dir"
    private const val KEY_EXPORT = "last_export_dir"
    private const val KEY_ATTACHMENT = "last_attachment_dir"

    enum class Kind(val key: String) {
        IMPORT(KEY_IMPORT),
        EXPORT(KEY_EXPORT),
        // 聊天附件与备份导入是两个独立的使用场景，共用 IMPORT 会互相干扰落点。
        ATTACHMENT(KEY_ATTACHMENT),
    }

    fun get(context: Context, kind: Kind): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(kind.key, null)
            ?.takeIf { it.startsWith('/') }

    /** 记录选择器落点；[pickedPath] 为空或不是绝对路径时保持原值不变。 */
    fun remember(context: Context, kind: Kind, pickedPath: String?) {
        val path = pickedPath?.trim().orEmpty()
        if (!path.startsWith('/')) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(kind.key, path) }
    }

    fun clear(context: Context, kind: Kind) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(kind.key) }
    }
}

/**
 * 导入时该记住哪个目录：**所选文件所在的目录**。
 *
 * 抽成纯函数是为了能单测：如果把文件路径直接当成「上次目录」记下来，下次选择器
 * 会落在一个文件上，表现为一进选择器就是「无法列出该目录」—— 功能看着像坏了。
 * 父目录拿不到时返回 null，调用方保持原值不变。
 */
internal fun importHistoryDirectory(pickedFile: String): String? =
    File(pickedFile).parent?.takeIf { it.startsWith('/') }

/**
 * 选择器从公共下载目录开始浏览：导入的卡、备份文件、导出默认都落在这里。
 *
 * 但公共目录不是任何设备都碰得到：没有「所有文件访问」也没有 Root 时，它既列不出也写不进。
 * 那种情况下退到应用私有目录，至少还能选中/写出东西，而不是点完只得到一句权限错误。
 */
internal fun pickerStartPath(context: Context): String {
    if (!Environment.isExternalStorageManager() && !RootAccess.isGranted) {
        context.getExternalFilesDir(null)?.let { return it.absolutePath }
        return context.filesDir.absolutePath
    }
    return File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS).absolutePath
}
