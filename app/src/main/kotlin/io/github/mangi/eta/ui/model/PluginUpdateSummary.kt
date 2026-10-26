package io.github.mangi.eta.ui.model

/**
 * 把逐个插件的检查结论汇成一个「这次检查到底得出了什么」。
 *
 * 为什么单独抽出来：真机验收时标题写的是「全部已是最新」，正文却是
 * 「有 1 个插件的来源版本比已装版本旧；不会提供降级」—— 标题与正文互相打架。
 * 原因是标题只分了「有更新 / 有失败 / 其他」三档，把「来源更旧」错归进了"最新"。
 *
 * 这类错误只有把**结论归类**和**文案拼接**分开才守得住：归类是纯逻辑，
 * 可以用「只有 OLDER 时绝不能说已是最新」这样的用例钉死；
 * 文案则由界面按 [Kind] 去取，不再自己判断。
 */
internal object PluginUpdateSummary {

    enum class Kind {
        /** 有可更新的插件（优先展示，即使同时有失败）。 */
        UPDATES,

        /** 没有更新，但有插件检查失败 —— 不能报"已是最新"。 */
        FAILURES,

        /** 没有更新也没有失败，但来源版本比已装旧（用户该换来源，不是等作者更新）。 */
        OLDER,

        /** 确实全部与来源一致。 */
        UP_TO_DATE,

        /** 没有任何可检查的目标。 */
        NOTHING,
    }

    data class Result(
        val kind: Kind,
        val updates: List<PluginUpdateStatusUi>,
        val failures: List<PluginUpdateStatusUi>,
        val older: List<PluginUpdateStatusUi>,
        val checked: Int,
    )

    fun of(statuses: Collection<PluginUpdateStatusUi>): Result {
        val updates = statuses.filter { it.state == PluginUpdateStatusUi.State.UPDATE_AVAILABLE }
        val failures = statuses.filter { it.state == PluginUpdateStatusUi.State.FAILED }
        val older = statuses.filter { it.state == PluginUpdateStatusUi.State.OLDER }
        val kind = when {
            updates.isNotEmpty() -> Kind.UPDATES
            failures.isNotEmpty() -> Kind.FAILURES
            older.isNotEmpty() -> Kind.OLDER
            statuses.isEmpty() -> Kind.NOTHING
            else -> Kind.UP_TO_DATE
        }
        return Result(kind, updates, failures, older, statuses.size)
    }
}
