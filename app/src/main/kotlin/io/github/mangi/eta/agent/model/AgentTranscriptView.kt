package io.github.mangi.eta.agent.model

/**
 * 「工作过程」显示多少细节，对齐 dsh 的 `transcriptView`。
 *
 * dsh 的四档不是一个「折叠开/关」开关，而是四个独立维度（foldCompletedTurns /
 * stepGrouping / liveProcessDetail / settledReasoningPreview）的组合；四档只是常用组合。
 * 这里照抄取值与组合，落到 Eta 的呈现结构上：
 *
 * | 档位 | 完成即收 | 步骤收束 | 运行中显示实时细节 |
 * |---|---|---|---|
 * | compact | 是 | 全部轮 | 否 |
 * | standard | 是 | 全部轮 | 是 |
 * | detailed（默认） | 是 | 只有历史轮 | 是 |
 * | verbose | 否 | 不收束 | 否 |
 *
 * dsh 的 `settledReasoningPreview`（已结束的推理行在标题旁预览首行）在 Eta 没有对应结构：
 * 折叠态只有一条整轮汇总行，没有独立的推理行标题，因此不实现；不要为了对齐而硬造一个。
 */
internal enum class AgentTranscriptViewMode(val id: String) {
    COMPACT("compact"),
    STANDARD("standard"),
    DETAILED("detailed"),
    VERBOSE("verbose"),
    ;

    companion object {
        /** 默认 detailed：与 dsh 的 `DEFAULT_TRANSCRIPT_VIEW_MODE` 一致，也最接近升级前的观感。 */
        val DEFAULT: AgentTranscriptViewMode = DETAILED

        /**
         * dsh 上一代的两档取值：`normal` / `expanded` 一律读作 detailed。
         *
         * 这两个值「接受但不再提供、也不再写回」，与 dsh 的注释同一口径 —— 手改过配置或从
         * 旧备份恢复的用户不会因为一个陌生取值掉回默认档之外的行为。
         */
        private val LEGACY_DETAILED = setOf("normal", "expanded")

        /** 未知或空值一律回退默认：写坏的值不能让聊天页没有档位。 */
        fun fromId(raw: String?): AgentTranscriptViewMode {
            val id = raw?.trim().orEmpty()
            values().firstOrNull { it.id == id }?.let { return it }
            return if (id in LEGACY_DETAILED) DETAILED else DEFAULT
        }
    }
}

/** 步骤收束粒度，对齐 dsh 的 `stepGrouping`。 */
internal enum class AgentStepGrouping {
    /** 每一轮的工作步骤都收在一条折叠行后面（现状）。 */
    COLLAPSED,

    /** 只有「已经成为历史」的轮收束；最新一轮完成的轮保持平铺，直到下一句发出。 */
    HISTORY,

    /** 完全不收束：步骤直接铺开，没有折叠行。 */
    NONE,
}

/** 一个档位对应的呈现能力，与 dsh 的 `ChatPresentationPolicy` 同名同义。 */
internal data class AgentTranscriptViewPolicy(
    val mode: AgentTranscriptViewMode,
    /** 正常结束的轮要不要收起工作过程。 */
    val foldCompletedTurns: Boolean,
    val stepGrouping: AgentStepGrouping,
    /** 折叠行在运行中要不要显示「正在跑的这一步」的具体细节。 */
    val liveProcessDetail: Boolean,
) {
    companion object {
        fun forMode(mode: AgentTranscriptViewMode): AgentTranscriptViewPolicy = when (mode) {
            AgentTranscriptViewMode.COMPACT -> AgentTranscriptViewPolicy(
                mode = mode,
                foldCompletedTurns = true,
                stepGrouping = AgentStepGrouping.COLLAPSED,
                liveProcessDetail = false,
            )

            AgentTranscriptViewMode.STANDARD -> AgentTranscriptViewPolicy(
                mode = mode,
                foldCompletedTurns = true,
                stepGrouping = AgentStepGrouping.COLLAPSED,
                liveProcessDetail = true,
            )

            AgentTranscriptViewMode.DETAILED -> AgentTranscriptViewPolicy(
                mode = mode,
                foldCompletedTurns = true,
                stepGrouping = AgentStepGrouping.HISTORY,
                liveProcessDetail = true,
            )

            AgentTranscriptViewMode.VERBOSE -> AgentTranscriptViewPolicy(
                mode = mode,
                foldCompletedTurns = false,
                stepGrouping = AgentStepGrouping.NONE,
                liveProcessDetail = false,
            )
        }
    }
}

/**
 * 档位 × 折叠时机 → 这一块工作过程「展开吗」「有折叠行吗」。
 *
 * 抽成纯函数是为了能单测：界面里还掺着「用户手点过没有」「搜索命中」「语音浮层空间」
 * 三件事，而档位与折叠时机只决定自动态，逐帧真机几乎无法验证。
 *
 * 语义与 dsh 的 `grouped` 一致：
 * ```
 * grouped = stepGrouping === 'collapsed'
 *   || (stepGrouping === 'history' && turn.status !== 'open' && !deferCollapse)
 * ```
 * 其中 `deferCollapse` 只在 `collapseTiming=next-input` 且还没等到下一句时成立。
 */
internal object AgentTranscriptPresentation {
    /**
     * 自动模式下要不要展开。
     *
     * @param active 这一块是不是本轮仍在进行的那块。
     * @param hasUserMessageAfter 这一块之后是否已经出现用户消息（用户已经开始下一轮）。
     */
    fun autoExpand(
        policy: AgentTranscriptViewPolicy,
        timing: AgentWorkCollapseTiming,
        active: Boolean,
        hasUserMessageAfter: Boolean,
    ): Boolean {
        // verbose（foldCompletedTurns=false）连已完成的轮也不收；其余完全交给折叠时机 ——
        // 两处各写一份判断，早晚会漂移成「档位说展开、时机说收起」。
        if (!policy.foldCompletedTurns && !active) return true
        return AgentWorkCollapsePolicy.autoExpand(
            timing = timing,
            active = active,
            hasUserMessageAfter = hasUserMessageAfter,
        )
    }

    /**
     * 要不要画那条折叠行。false 表示步骤直接平铺 —— 没有可点的头，也没有折叠态。
     */
    fun grouped(
        policy: AgentTranscriptViewPolicy,
        timing: AgentWorkCollapseTiming,
        active: Boolean,
        hasUserMessageAfter: Boolean,
    ): Boolean = when (policy.stepGrouping) {
        AgentStepGrouping.COLLAPSED -> true
        AgentStepGrouping.NONE -> false
        // history：当前轮平铺（dsh 的 turn.status === 'open'），历史轮收束。
        AgentStepGrouping.HISTORY -> !active && !deferredToNextInput(timing, hasUserMessageAfter)
    }

    /** dsh 的 `deferCollapse`：next-input 下、本块之后还没有新的用户消息时保持展开。 */
    private fun deferredToNextInput(
        timing: AgentWorkCollapseTiming,
        hasUserMessageAfter: Boolean,
    ): Boolean = timing == AgentWorkCollapseTiming.NEXT_INPUT && !hasUserMessageAfter
}
