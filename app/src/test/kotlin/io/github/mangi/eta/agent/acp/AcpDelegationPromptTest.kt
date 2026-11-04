package io.github.mangi.eta.agent.acp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「复杂编码要不要委派」的偏好如何变成提示词。
 *
 * 这段文字**决定了模型行为**，而且很难靠真机验收发现写错：
 * 语气软了模型不照做（用户以为开了「直接委派」其实还在被问），
 * 语气硬了模型对着一行配置也去委派（多花一份 token、多等一轮）。
 */
class AcpDelegationPromptTest {

    private fun section(
        mode: AcpDelegationMode = AcpDelegationMode.ASK,
        defaultAgent: String = "codex",
        available: List<String>? = null,
    ) = AcpDelegationPrompt.section(mode, defaultAgent, available)

    @Test
    fun offProducesNothingAtAll() {
        // 关闭时连「不要委派」都不要写：留着反而让模型惦记这个工具。
        assertEquals("", section(mode = AcpDelegationMode.OFF))
    }

    @Test
    fun noAvailableAgentProducesNothing() {
        // 已确认一个都不可用时写这段，模型会去调并拿到 NOT_INSTALLED，白花一轮，
        // 还可能把「工具坏了」当成结论告诉用户。
        assertEquals("", section(available = emptyList()))
    }

    @Test
    fun unknownAvailabilityStillGivesGuidanceButAsksToVerifyFirst() {
        // null = 还没探测过。不能当成「不能用」，否则等于把这个能力藏起来。
        val text = section(available = null)
        assertTrue("要鼓励委派复杂任务", text.contains("优先委派"))
        assertTrue("必须让它先确认代理存在", text.contains("acp_agent_list"))
        assertTrue("不能用「你确认过」的语气", text.contains("如果它显示未安装"))
    }

    @Test
    fun autoTellsTheModelNotToAsk() {
        val auto = section(mode = AcpDelegationMode.AUTO, available = listOf("codex"))
        assertTrue(auto.contains("直接调 acp_delegate"))
        assertTrue(auto.contains("不要先问"))
        assertFalse("自动模式不该再让它去问", auto.contains("先用 ask_user 问一句"))
    }

    @Test
    fun askTellsTheModelToConfirmButSkipsAskingWhenUserAlreadySaidSo() {
        val ask = section(mode = AcpDelegationMode.ASK, available = listOf("codex"))
        assertTrue(ask.contains("ask_user"))
        assertTrue(
            "用户已经点名 codex/claude 时再问一遍就是烦人",
            ask.contains("用户已经明确说"),
        )
        assertFalse(ask.contains("不要先问用户"))
    }

    @Test
    fun simpleWorkIsExplicitlyExcludedInEveryMode() {
        // 没有这句，模型会把「改一行配置」也委派出去 —— 那比不委派更糟。
        listOf(AcpDelegationMode.ASK, AcpDelegationMode.AUTO).forEach { mode ->
            val text = section(mode = mode, available = listOf("codex"))
            assertTrue("$mode 缺少「简单任务自己做」的约束", text.contains("不要委派"))
        }
    }

    @Test
    fun defaultAgentIsHonouredWhenItIsOneOfTheAvailable() {
        val text = section(defaultAgent = "claude", available = listOf("codex", "claude"))
        assertTrue(text.contains("agent=\"claude\""))
        assertTrue("另一个代理要作为可选项出现", text.contains("codex"))
    }

    @Test
    fun defaultAgentFallsBackWhenItIsNotInstalled() {
        // 用户选了 claude 但只装了 codex：不能让提示里写一个不存在的代理，
        // 否则每次委派都撞 ACP_AGENT_NOT_INSTALLED。
        val text = section(defaultAgent = "claude", available = listOf("codex"))
        assertTrue(text.contains("agent=\"codex\""))
        assertFalse(text.contains("agent=\"claude\""))
    }

    @Test
    fun unknownWireValueFallsBackToAsk() {
        // 手改配置或旧版本写进了别的值：默认行为必须与出厂一致，而不是关掉功能。
        assertEquals(AcpDelegationMode.ASK, AcpDelegationMode.fromWire("nonsense"))
        assertEquals(AcpDelegationMode.ASK, AcpDelegationMode.fromWire(null))
        assertEquals(AcpDelegationMode.OFF, AcpDelegationMode.fromWire("off"))
        assertEquals(AcpDelegationMode.AUTO, AcpDelegationMode.fromWire(" auto "))
    }
}
