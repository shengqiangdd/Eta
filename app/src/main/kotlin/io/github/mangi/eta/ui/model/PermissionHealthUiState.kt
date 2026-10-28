package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

internal const val LOCAL_NETWORK_PERMISSION_ITEM_ID = "local_network"

@Immutable
data class PermissionHealthUiState(
    val items: List<PermissionHealthItemUi>,
    /**
     * 首次检查是否已经跑完。
     *
     * 初值是 false：此刻 [items] 为空只代表「还没算」，不代表「一切正常」。
     * 聊天提示条据此决定要不要清掉持久化的忽略记录 —— 详见 `ChatStatusNoticeDismissal.reconcile`。
     */
    val loaded: Boolean = false,
) {
    /** 当前需要用户处理的项；权限页和聊天提示条共用同一口径。 */
    val issueItems: List<PermissionHealthItemUi>
        get() = items.filter { it.status != PermissionStatusUi.Available }

    val issueCount: Int
        get() = issueItems.size

    /** 只取 id：聊天提示条用它判断「用户关掉的是不是同一批问题」。 */
    val issueIds: Set<String>
        get() = issueItems.mapTo(linkedSetOf()) { it.id }
}

@Immutable
data class PermissionHealthItemUi(
    val id: String,
    val title: String,
    val summary: String,
    val status: PermissionStatusUi,
    val primaryActionLabel: String?,
)

@Immutable
enum class PermissionStatusUi {
    Available,
    Missing,
    Warning,
    Disabled,
}
