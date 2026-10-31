package io.github.mangi.eta.ui.app

/**
 * attach 失败后的收尾动作（纯逻辑，可单测）。
 *
 * 为什么单独抽出来：这里判错时用户看到的就是「明明还在跑，界面却说没在运行」。
 * 真机复现路径是「切到后台放久一点，再切回前台」—— 此时 runtime 服务还没重新绑定好，
 * `attachRun` 会超时返回 [Outcome.UNAVAILABLE]。它只说明**这一次没问出来**，
 * 不说明 run 已经结束。
 *
 * 历史教训：这个分支原来的注释写着「查询失败不等于 run 结束：保留运行中」，
 * 代码却在 `asPrimary` 时把 `isStreaming` 清成 false —— 注释与行为相反。
 * 服务端那条 run 还在跑，界面却已经变成「未运行」，且不会自己恢复，
 * 因为重试只挂在「下一次前台对账」上，而用户已经在前台了。
 */
internal object AgentAttachRecovery {

    /** 一次 attach 的结论，与 `AgentRuntimeClient.AttachOutcome` 一一对应。 */
    enum class Outcome {
        /** 已经拿到终态结果。 */
        COMPLETED,

        /** 服务端明确回答「这条 run 不在了」（已结束、被取消或从未登记）。 */
        NOT_ACTIVE,

        /** 没问出来：绑定未就绪、服务正忙或超时。不代表 run 结束。 */
        UNAVAILABLE,
    }

    enum class Action {
        /** 拿到终态结果：交给结果应用逻辑。 */
        APPLY_RESULT,

        /** 收掉运行态，并立刻重新对账。 */
        CLEAR_AND_RECONCILE,

        /** **保留运行态**，稍后重新对账。 */
        KEEP_RUNNING_AND_RETRY,
    }

    fun decide(outcome: Outcome): Action = when (outcome) {
        Outcome.COMPLETED -> Action.APPLY_RESULT
        Outcome.NOT_ACTIVE -> Action.CLEAR_AND_RECONCILE
        Outcome.UNAVAILABLE -> Action.KEEP_RUNNING_AND_RETRY
    }
}
