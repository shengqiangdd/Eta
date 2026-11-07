package io.github.mangi.eta.agent.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败结果要不要带「下一步」。
 *
 * 真机数据（`eta_self_audit(scope="agent")`，72 小时）：
 * `tap_element` 的 `NO_OBSERVATION` 出现 9 次、`virtual_display_launch` 的
 * `ALREADY_ON_TARGET_DISPLAY` 出现 3 次 —— 而 `AgentSelfBrief` 早就在系统提示里
 * 写过这两个码的改法。说明「知道」不够，失败现场得直接给出下一步。
 */
class AgentFailureHintsNextStepTest {
    @Test
    fun knownFailureCodesExposeAnExecutableNextStep() {
        val codes = listOf(
            "NO_OBSERVATION",
            "STALE_OBSERVATION",
            "READ_REQUIRED",
            "MATCH_NOT_FOUND",
            "ALREADY_ON_TARGET_DISPLAY",
        )
        codes.forEach { code ->
            assertTrue("$code 应有专属改法", AgentFailureHints.hasSpecificFix(code))
            val fix = AgentFailureHints.fixFor("", code)
            assertTrue("$code 的改法不能是空串", fix.isNotBlank())
            // 改法必须是可执行动作，不能是「注意一下」这类空话。
            assertTrue("$code 的改法看起来不可执行：$fix", fix.contains("重新") || fix.contains("先") || fix.contains("合并") || fix.contains("不要"))
        }
    }

    @Test
    fun unknownFailureCodeGetsNoFabricatedAdvice() {
        // 没有专属改法就不该注入「下一步」：一句通用废话会训练模型忽略这段。
        assertFalse(AgentFailureHints.hasSpecificFix("SOMETHING_NEW"))
        assertFalse(AgentFailureHints.hasSpecificFix(null))
    }

    @Test
    fun toolSpecificFallbackStillSaysSomethingActionable() {
        val fix = AgentFailureHints.fixFor("terminal", null)
        assertTrue(fix.isNotBlank())
        assertTrue(fix.contains("stderr") || fix.contains("退出码"))
    }
}
