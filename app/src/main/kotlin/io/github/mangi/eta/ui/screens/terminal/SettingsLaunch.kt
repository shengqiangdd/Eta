package io.github.mangi.eta.ui.screens.terminal

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.Display
import android.view.View
import androidx.core.net.toUri

/**
 * 「所有文件访问」系统设置页的启动意图。
 *
 * 为什么先判断屏幕：本机实测（Android 16 / ColorOS），从虚拟屏上的界面启动
 * `Settings$AppManageExternalStorageActivity` 时，系统会把该 Activity 所在任务整体迁到
 * 默认屏（Display #0），虚拟屏随即变空、任务「跳屏」。
 * 普通应用没有 `INTERNAL_SYSTEM_WINDOW` 权限，无法用 `ActivityOptions.setLaunchDisplayId`
 * 指定落点（`am start --display` 走的正是被系统拒绝的那条路），因此这里不硬闯：
 * 不在默认屏时只给出提示，避免整个任务被搬走。
 */
internal fun allFilesAccessIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        "package:${context.packageName}".toUri(),
    )

/** 当前界面是否在默认屏；取不到 display 时按默认屏处理（保守，保持原有行为）。 */
internal fun isOnDefaultDisplay(view: View): Boolean =
    (view.display?.displayId ?: Display.DEFAULT_DISPLAY) == Display.DEFAULT_DISPLAY
