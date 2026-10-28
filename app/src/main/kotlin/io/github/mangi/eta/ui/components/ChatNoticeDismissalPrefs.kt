package io.github.mangi.eta.ui.components

import android.content.Context
import android.content.SharedPreferences

/**
 * 聊天提示条的忽略记录。
 *
 * 分两类：
 * - **权限问题 id 集合**：跨会话、跨重启都成立，用户关掉后不该每次启动又弹回来；
 * - **会话内的提示 key**（载入中 / 历史过大 / 视图过滤）：按会话分开记。切走再回来、
 *   或者去设置页转一圈回来，都不该把已经关掉的提示又弹一次；也不能让 A 会话的「已读」
 *   影响 B 会话。
 *
 * 注意：这里只记录「用户主动关掉的」。条件消失后的清理由
 * `ChatStatusNoticeDismissal.reconcile` 负责，落盘也走同一个入口。
 */
internal object ChatNoticeDismissalPrefs {
    private const val FILE = "chat_notice_dismissals"
    private const val KEY_PERMISSION_ISSUE_IDS = "dismissed_permission_issue_ids"
    private const val KEY_SCOPE_PREFIX = "dismissed_keys:"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun dismissedPermissionIssueIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PERMISSION_ISSUE_IDS, emptySet())?.toSet().orEmpty()

    fun setDismissedPermissionIssueIds(context: Context, ids: Set<String>) {
        val editor = prefs(context).edit()
        if (ids.isEmpty()) {
            editor.remove(KEY_PERMISSION_ISSUE_IDS)
        } else {
            editor.putStringSet(KEY_PERMISSION_ISSUE_IDS, ids)
        }
        editor.apply()
    }

    /** 某个会话里已经关掉的提示 key；会话 id 为空表示新建会话，调用方不该落盘。 */
    fun dismissedKeys(context: Context, scopeKey: String): Set<String> =
        prefs(context).getStringSet(KEY_SCOPE_PREFIX + scopeKey, emptySet())?.toSet().orEmpty()

    fun setDismissedKeys(context: Context, scopeKey: String, keys: Set<String>) {
        val editor = prefs(context).edit()
        val name = KEY_SCOPE_PREFIX + scopeKey
        if (keys.isEmpty()) {
            editor.remove(name)
        } else {
            editor.putStringSet(name, keys)
        }
        editor.apply()
    }

    /** 会话被删除时清掉它的忽略记录，避免 prefs 里留下永远不会再读的键。 */
    fun clearScope(context: Context, scopeKey: String) {
        prefs(context).edit().remove(KEY_SCOPE_PREFIX + scopeKey).apply()
    }
}
