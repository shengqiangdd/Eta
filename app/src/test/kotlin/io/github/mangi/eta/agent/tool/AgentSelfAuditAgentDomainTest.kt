package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.trace.AgentSelfStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AGENT 域（「我自己做得怎么样」）的判定。
 *
 * 这里守的主要是**不误报**：自省最容易变成自我批评表演，
 * 所以样本不足、数据缺失、整体正常这三种情况都必须安静。
 */
class AgentSelfAuditAgentDomainTest {

    private fun tool(name: String, calls: Int, failures: Int, codes: Map<String, Int> = emptyMap()) =
        AgentSelfStats.ToolBehavior(name = name, calls = calls, failures = failures, failureCodes = codes)

    private fun stats(
        runs: Int = 20,
        runsWithTools: Int = 20,
        toolCalls: Int = 200,
        toolFailures: Int = 10,
        retries: Int = 0,
        retryRuns: Int = 0,
        compactions: Int = 0,
        compactionRuns: Int = 0,
        failedRuns: Int = 0,
        idleRuns: List<AgentSelfStats.IdleRun> = emptyList(),
        tools: List<AgentSelfStats.ToolBehavior> = emptyList(),
    ) = AgentSelfStats.Stats(
        runs = runs,
        runsWithTools = runsWithTools,
        toolCalls = toolCalls,
        toolFailures = toolFailures,
        retries = retries,
        retryRuns = retryRuns,
        compactions = compactions,
        compactionRuns = compactionRuns,
        failedRuns = failedRuns,
        idleRuns = idleRuns,
        tools = tools,
    )

    private fun audit(selfStats: AgentSelfStats.Stats?) = AgentSelfAudit.audit(
        AgentSelfAudit.Input(
            packageName = "io.github.mangi.eta",
            versionName = "2.0",
            nowMillis = 1_700_000_000_000,
            windowHours = 48,
            domains = setOf(AgentSelfAudit.Domain.AGENT),
            rootAvailable = false,
            selfStats = selfStats,
        ),
    )

    // ---- 数据缺失 vs 确实没数据 ----

    @Test
    fun `读不到追踪时进 unavailable 而不是报无缺陷`() {
        val report = audit(null)
        assertTrue(report.findings.isEmpty())
        assertTrue(report.unavailable.any { it.contains("自我表现") })
        // 关键：不能出现「健康」这类正面断言。
        assertFalse(report.unavailable.any { it.contains("健康") })
    }

    @Test
    fun `追踪为空时只记 notes，不算缺陷也不算测不了`() {
        val report = audit(stats(runs = 0, runsWithTools = 0, toolCalls = 0, toolFailures = 0))
        assertTrue(report.findings.isEmpty())
        assertTrue(report.unavailable.isEmpty())
        assertTrue(report.notes.any { it.contains("还没有可用的运行记录") })
    }

    @Test
    fun `口径说明总是写进 notes`() {
        val report = audit(stats())
        assertNotNull(report.notes.firstOrNull { it.contains("自我表现口径") })
        assertTrue(report.notes.any { it.contains("20 次运行") })
    }

    // ---- 样本不足不报 ----

    @Test
    fun `调用次数不足门槛时即使失败率 100% 也不报`() {
        // 7 次全失败 = 100%，但低于 MIN_CALLS_FOR_RATE(8)，属于噪声。
        val report = audit(
            stats(tools = listOf(tool("edit_file", calls = 7, failures = 7, codes = mapOf("READ_REQUIRED" to 7)))),
        )
        assertTrue(report.findings.none { it.id.startsWith("agent-tool-fail") })
    }

    @Test
    fun `刚好达到门槛且超过高失败率才报`() {
        val report = audit(
            stats(tools = listOf(tool("edit_file", calls = 8, failures = 2, codes = mapOf("READ_REQUIRED" to 2)))),
        )
        // 2/8 = 25% ≥ SEVERE(0.25) → P1
        val finding = report.findings.single { it.id == "agent-tool-fail-edit_file" }
        assertEquals(AgentSelfAudit.Severity.P1, finding.severity)
        assertEquals(AgentSelfAudit.Confidence.OBSERVED, finding.confidence)
    }

    @Test
    fun `失败率低于阈值时不报`() {
        // 10/100 = 10% < HIGH_FAILURE_RATE(12%)
        val report = audit(
            stats(tools = listOf(tool("terminal", calls = 100, failures = 10, codes = mapOf("EXIT_1" to 10)))),
        )
        assertTrue(report.findings.none { it.id.startsWith("agent-tool-fail") })
    }

    @Test
    fun `中等失败率给 P2 而不是 P1`() {
        // 3/20 = 15%，高于 12% 但低于 25%
        val report = audit(
            stats(tools = listOf(tool("grep_files", calls = 20, failures = 3, codes = mapOf("NOT_DIRECTORY" to 3)))),
        )
        val finding = report.findings.single { it.id == "agent-tool-fail-grep_files" }
        assertEquals(AgentSelfAudit.Severity.P2, finding.severity)
    }

    // ---- 改法必须与失败码对应 ----

    @Test
    fun `READ_REQUIRED 的改法指向先读再改`() {
        val report = audit(
            stats(tools = listOf(tool("edit_file", calls = 10, failures = 5, codes = mapOf("READ_REQUIRED" to 5)))),
        )
        val fix = report.findings.single().fix
        assertTrue("改法应指向先读文件：$fix", fix.contains("read_file"))
    }

    @Test
    fun `FILE_CHANGED 的改法与 READ_REQUIRED 不同`() {
        val changed = audit(
            stats(tools = listOf(tool("edit_file", calls = 10, failures = 5, codes = mapOf("FILE_CHANGED" to 5)))),
        ).findings.single().fix
        val required = audit(
            stats(tools = listOf(tool("edit_file", calls = 10, failures = 5, codes = mapOf("READ_REQUIRED" to 5)))),
        ).findings.single().fix
        // 两个码需要完全不同的动作；文案必须区分，否则用户按哪条改都不知道。
        assertTrue("FILE_CHANGED 应提示重新读取", changed.contains("重新"))
        assertTrue("两条改法不应相同", changed != required)
    }

    // ---- 真实错误码：表里每一个都要给得出不同的、可执行的改法 ----

    @Test
    fun `真实追踪里出现过的每个失败码都有自己的改法`() {
        // 这些码来自本仓 72 份真实追踪记录，不是凭印象编的。
        val codes = listOf(
            "READ_REQUIRED", "FILE_CHANGED", "MATCH_NOT_FOUND", "AMBIGUOUS_MATCH", "NO_CHANGES",
            "FILE_TOO_LARGE", "INVALID_ARGUMENT", "NOT_DIRECTORY", "FILE_NOT_FOUND", "NOT_REGULAR_FILE",
            "STALE_CONTENT", "NO_OBSERVATION", "STALE_OBSERVATION", "MAIN_INPUT_ACTIVE",
            "APP_ALREADY_RUNNING", "ALREADY_ON_TARGET_DISPLAY", "VIRTUAL_DISPLAY_NOT_FOUND",
            "SEARCH_CHALLENGE", "ACP_PROTOCOL_ERROR", "ACP_DELEGATION_DISABLED",
            "TOOL_NOT_AVAILABLE", "INVALID_QUERY",
        )
        val fixes = mutableMapOf<String, String>()
        codes.forEach { code ->
            val report = audit(
                stats(tools = listOf(tool("edit_file", calls = 10, failures = 5, codes = mapOf(code to 5)))),
            )
            val fix = report.findings.single().fix
            assertTrue("$code 应能给出非空改法", fix.isNotBlank())
            // 兜底文案的特征词：出现它就说明没命中专属分支。
            assertFalse("$code 落到了兜底改法：$fix", fix.contains("按失败返回里的 code"))
            fixes[code] = fix
        }
        // 不同码对应不同动作，不能全挤到同一句话上。
        val distinct = fixes.values.distinct().size
        assertTrue("改法应基本一一对应，实际只有 $distinct 种", distinct >= codes.size - 1)
    }

    @Test
    fun `STALE_CONTENT 与 NO_OBSERVATION 指向不同动作`() {
        val stale = audit(
            stats(tools = listOf(tool("tap_element", calls = 10, failures = 5, codes = mapOf("STALE_CONTENT" to 5)))),
        ).findings.single().fix
        val missing = audit(
            stats(tools = listOf(tool("tap_element", calls = 10, failures = 5, codes = mapOf("NO_OBSERVATION" to 5)))),
        ).findings.single().fix
        assertTrue("STALE_CONTENT 是界面变了，应提示重新观察内容：$stale", stale.contains("重新 observe_screen"))
        assertTrue("NO_OBSERVATION 是缺少快照 id：$missing", missing.contains("observation_id"))
        assertTrue(stale != missing)
    }

    @Test
    fun `未知失败码回退到按工具给的通用改法而不是留空`() {
        val report = audit(
            stats(tools = listOf(tool("terminal", calls = 10, failures = 5, codes = mapOf("SOMETHING_NEW" to 5)))),
        )
        val fix = report.findings.single().fix
        assertTrue(fix.isNotBlank())
        assertTrue("terminal 的兜底改法应提到 stderr", fix.contains("stderr"))
    }

    @Test
    fun `失败没有错误码时仍然报，但证据里说明未分类`() {
        val report = audit(stats(tools = listOf(tool("edit_file", calls = 10, failures = 5))))
        val finding = report.findings.single { it.id == "agent-tool-fail-edit_file" }
        assertTrue(finding.evidence.any { it.contains("没有错误码") })
    }

    // ---- 空转 ----

    @Test
    fun `单次空转只当线索，不报缺陷`() {
        val report = audit(stats(idleRuns = listOf(AgentSelfStats.IdleRun("r1", rounds = 3, toolCalls = 0))))
        assertTrue(report.findings.none { it.id == "agent-idle-runs" })
        assertTrue(report.clues.any { it.title.contains("没调工具") })
    }

    @Test
    fun `两次以上空转才报缺陷`() {
        val report = audit(
            stats(
                idleRuns = listOf(
                    AgentSelfStats.IdleRun("r1", rounds = 5, toolCalls = 0),
                    AgentSelfStats.IdleRun("r2", rounds = 3, toolCalls = 0),
                ),
            ),
        )
        val finding = report.findings.single { it.id == "agent-idle-runs" }
        assertEquals(AgentSelfAudit.Severity.P2, finding.severity)
        assertTrue(finding.title.contains("2 次"))
    }

    // ---- 压缩与重试：只做线索 ----

    @Test
    fun `压缩次数达到阈值时给出线索而不是缺陷`() {
        val report = audit(stats(compactions = 5, compactionRuns = 3))
        assertTrue(report.findings.none { it.id.contains("compact") })
        assertTrue(report.clues.any { it.title.contains("上下文压缩") })
    }

    @Test
    fun `重试给出线索并说明不是工具用法问题`() {
        val report = audit(stats(retries = 6, retryRuns = 4))
        val clue = report.clues.first { it.title.contains("重试") }
        assertTrue(clue.title.contains("非工具用法问题"))
    }

    @Test
    fun `重试与压缩次数不足时不打扰`() {
        val report = audit(stats(compactions = 1, compactionRuns = 1, retries = 1, retryRuns = 1))
        assertTrue(report.clues.isEmpty())
    }

    // ---- 整体失败率 ----

    @Test
    fun `整体失败率高且运行数够时报 P1`() {
        // 60/200 = 30%
        val report = audit(stats(runsWithTools = 20, toolCalls = 200, toolFailures = 60))
        val finding = report.findings.single { it.id == "agent-overall-fail" }
        assertEquals(AgentSelfAudit.Severity.P1, finding.severity)
    }

    @Test
    fun `运行数不足时不评价整体失败率`() {
        // 失败率 30% 但只有 5 次运行过工具，样本不足
        val report = audit(stats(runs = 5, runsWithTools = 5, toolCalls = 200, toolFailures = 60))
        assertTrue(report.findings.none { it.id == "agent-overall-fail" })
    }

    @Test
    fun `整体正常时一条缺陷都不报`() {
        // 真实基线：中位失败率 5.2%
        val report = audit(
            stats(
                toolCalls = 1000,
                toolFailures = 52,
                tools = listOf(tool("terminal", calls = 900, failures = 45, codes = mapOf("EXIT_1" to 45))),
            ),
        )
        assertTrue("正常数据不应产出缺陷：${report.findings.map { it.id }}", report.findings.isEmpty())
    }

    // ---- 域选择 ----

    @Test
    fun `scope 不含 agent 时完全不碰自我表现`() {
        val report = AgentSelfAudit.audit(
            AgentSelfAudit.Input(
                packageName = "io.github.mangi.eta",
                versionName = "2.0",
                nowMillis = 1_700_000_000_000,
                windowHours = 48,
                domains = setOf(AgentSelfAudit.Domain.CODE),
                rootAvailable = false,
                selfStats = null,
            ),
        )
        assertTrue(report.notes.none { it.contains("自我表现") })
        assertTrue(report.unavailable.none { it.contains("自我表现") })
    }

    @Test
    fun `all 域包含 agent`() {
        val report = AgentSelfAudit.audit(
            AgentSelfAudit.Input(
                packageName = "io.github.mangi.eta",
                versionName = "2.0",
                nowMillis = 1_700_000_000_000,
                windowHours = 48,
                domains = AgentSelfAudit.Domain.entries.toSet(),
                rootAvailable = false,
                capabilities = mapOf("accessibility" to true, "usage" to true),
                selfStats = stats(),
            ),
        )
        assertTrue(report.checkedDomains.contains("agent"))
        assertTrue(report.notes.any { it.contains("自我表现口径") })
    }

    // ---- 排序：自我表现不抢占设备级 P0 ----

    @Test
    fun `自我表现缺陷排在设备 P0 之后`() {
        val report = AgentSelfAudit.audit(
            AgentSelfAudit.Input(
                packageName = "io.github.mangi.eta",
                versionName = "2.0",
                nowMillis = 1_700_000_000_000,
                windowHours = 48,
                domains = setOf(AgentSelfAudit.Domain.AGENT, AgentSelfAudit.Domain.CAPABILITY),
                rootAvailable = false,
                capabilities = mapOf("accessibility" to false),
                selfStats = stats(toolCalls = 200, toolFailures = 60, runsWithTools = 20),
            ),
        )
        // 能力缺失是 P1，整体失败率也是 P1；按 severity 排序后同类才可能交错。
        // 这里只断言：不存在 agent 的 P1 排在任何 P0 之前的情况（当前样本没有 P0）。
        val firstAgent = report.findings.indexOfFirst { it.id.startsWith("agent-") }
        assertTrue("agent 缺陷应在清单里", firstAgent >= 0)
        // 所有 P0 都必须排在所有 agent 缺陷之前。
        val lastP0 = report.findings.indexOfLast { it.severity == AgentSelfAudit.Severity.P0 }
        assertTrue("P0 应排在 agent 缺陷之前", lastP0 < firstAgent || lastP0 < 0)
    }
}
