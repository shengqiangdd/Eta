package io.github.mangi.eta.agent.acp

/**
 * 「复杂编码任务要不要交给编码代理」的用户偏好。
 *
 * 为什么需要它：这不是能力问题，是**信任与成本**问题。
 * - 委派会拉起另一个进程、跑另一个模型、可能动工作区文件（于是要花另一份钱、等另一段时间）。
 * - 有人就希望「我的仓库只让当前这个模型改」；也有人希望「凡是要改代码都别让我陪着，
 *   直接交给最擅长的那一个」。
 *
 * 两种偏好都合理，所以做成显式策略而不是拍一个默认值：
 * - [OFF]：不委派，自己动手。提示里连这段都不写。
 * - [ASK]（默认）：够复杂就问一句，简单的不打扰。
 * - [AUTO]：复杂编码直接派给 [defaultAgent]，不打断用户。
 *
 * 默认放 [ASK] 而不是 [AUTO]：委派要花另一份 token 与时间，默认替用户决定
 * 「再跑一个模型」是越权 —— 但也不放 [OFF]，那等于把能力藏起来。
 */
enum class AcpDelegationMode(val wire: String) {
    OFF("off"),
    ASK("ask"),
    AUTO("auto");

    companion object {
        /** 未知值回退 [ASK]：与默认一致，旧配置/手改文件不会把功能关掉。 */
        fun fromWire(value: String?): AcpDelegationMode =
            entries.firstOrNull { it.wire == value?.trim() } ?: ASK
    }
}

/**
 * 把用户偏好翻译成注入系统提示的一段话。
 *
 * 单独抽出来是因为**这段话的内容决定了模型行为**，而它很难在真机上验证：
 * 写得太软模型不照做，写得太硬模型对着一行配置也去委派。
 * 纯函数 + 单测至少能钉住「三档语气不同」和「OFF 时完全不出现」。
 */
internal object AcpDelegationPrompt {

    /**
     * @param mode 用户偏好
     * @param defaultAgent 默认代理 id（`codex` / `claude`）
     * @param available 本轮已确认可用的代理 id；`null` 表示**未知**（还没探测）
     *
     * `null` 与空列表必须区分开：
     * - `null`：照常给提示，但加一句「先用 acp_agent_list 确认」——否则模型会把
     *   「没确认过」当成「不能用」，白白不用这个能力。
     * - 空列表：探测过，一个都用不了 → 干脆别写这段，模型不会去撞一个必然失败的调用。
     */
    fun section(
        mode: AcpDelegationMode,
        defaultAgent: String,
        available: List<String>?,
    ): String {
        if (mode == AcpDelegationMode.OFF) return ""
        // 已确认一个都不可用：不要写「可以委派」。模型会去调，然后拿到
        // ACP_AGENT_NOT_INSTALLED，白花一轮，还可能把「工具坏了」当成结论告诉用户。
        if (available != null && available.isEmpty()) return ""

        val agent = defaultAgent.takeIf { available == null || it in available }
            ?: available!!.first()
        val alternatives = available.orEmpty().filter { it != agent }
        val pick = if (alternatives.isEmpty()) {
            "默认用 agent=\"$agent\""
        } else {
            "默认用 agent=\"$agent\"；需要另一种实现风格时可换 ${alternatives.joinToString("、")}"
        }

        val head = "复杂编码任务优先委派给编码代理，而不是自己一步步改：" +
            "跨文件重构、按规格新建模块、批量改写、跑测试并修失败、" +
            "需要在陌生仓库里定位并修复问题这类**多文件、需要多轮试错**的活。" +
            "委派用 acp_delegate，它会在独立进程里跑完整回合并回传改动摘要。" +
            "$pick。"

        val verifyLine = if (available == null) {
            "动手前先用 acp_agent_list 确认这个代理已安装且有凭据。" +
                "如果它显示未安装或缺凭据，就自己动手完成并把情况告诉用户，不要反复重试委派。"
        } else {
            ""
        }
        val tail = "单文件小改、读一个函数、查一段配置这类事自己动手更快，不要委派 —— " +
            "委派要另起一个进程、另花一份 token，简单任务上纯属浪费。" +
            "结果是 ok=false 时按返回的 code 处理（NOT_INSTALLED / CREDENTIAL_MISSING 这类要如实告诉用户，不要重试硬撞）。"

        val modeLine = when (mode) {
            AcpDelegationMode.AUTO ->
                "当前偏好是**直接委派**：判断属于上述复杂编码任务时直接调 acp_delegate，不要先问用户，也不必先解释你要委派。"
            AcpDelegationMode.ASK ->
                "当前偏好是**先问再做**：判断属于上述复杂编码任务时，先用 ask_user 问一句" +
                    "（说明打算派给哪个代理、大概要做什么），得到同意再委派；用户已经明确说「交给 codex/claude」时直接派，不必再问。"
            // OFF 已在上面返回。
            AcpDelegationMode.OFF -> ""
        }
        return "$head$verifyLine$modeLine$tail"
    }
}
