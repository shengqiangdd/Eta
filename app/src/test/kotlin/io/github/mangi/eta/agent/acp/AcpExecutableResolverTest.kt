package io.github.mangi.eta.agent.acp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACP 可执行文件的候选与探测脚本。
 *
 * 为什么要有这层：`/opt/eta/node/...` 是 guest 路径，App 进程里 `File(...).canExecute()`
 * 永远是 false —— 于是「装好的代理」被报成「未安装」。探测必须在 Linux 环境里做，
 * 所以这里只负责生成**候选清单与脚本**，执行交给 AcpClient。
 */
class AcpExecutableResolverTest {

    @Test
    fun planOrdersExplicitBeforePathCommands() {
        val plan = AcpExecutableResolver.plan(
            explicit = listOf("/opt/eta/node/26.8.1/bin/codex-acp"),
            globs = listOf("/opt/eta/node/*/bin/codex-acp"),
            commands = listOf("codex-acp"),
        )
        assertEquals(listOf("/opt/eta/node/26.8.1/bin/codex-acp", "codex-acp"), plan.exact)
        assertEquals(listOf("/opt/eta/node/*/bin/codex-acp"), plan.globs)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun planDropsBlankAndDuplicateEntries() {
        val plan = AcpExecutableResolver.plan(
            explicit = listOf("  /a/bin/x  ", "/a/bin/x", " "),
            globs = listOf("", "/opt/*/x", "/opt/*/x"),
            commands = listOf("x", "x"),
        )
        assertEquals(listOf("/a/bin/x", "x"), plan.exact)
        assertEquals(listOf("/opt/*/x"), plan.globs)
    }

    @Test
    fun emptyPlanIsReportedAsEmpty() {
        val plan = AcpExecutableResolver.plan(emptyList(), emptyList(), emptyList())
        assertTrue(plan.isEmpty)
        // 空计划也要产出可执行脚本（直接 exit 0），否则调用方会拿到半截 shell。
        assertEquals("exit 0", AcpExecutableResolver.probeScript(plan))
    }

    @Test
    fun probeScriptQuotesExactButLetsGlobsExpand() {
        val plan = AcpExecutableResolver.plan(
            explicit = listOf("/opt/eta/node/26.8.1/bin/codex-acp"),
            globs = listOf("/opt/eta/node/*/bin/codex-acp"),
            commands = listOf("codex-acp"),
        )
        val script = AcpExecutableResolver.probeScript(plan)
        assertTrue("显式路径要被引号包住", script.contains("'/opt/eta/node/26.8.1/bin/codex-acp'"))
        assertTrue("PATH 命令名也要被引号包住", script.contains("'codex-acp'"))
        assertTrue("通配要让 shell 自己展开，不能加引号", script.contains("for eta_p in /opt/eta/node/*/bin/codex-acp"))
        assertTrue("先检查精确候选", script.indexOf("command -v") < script.indexOf("/opt/eta/node/*/bin/codex-acp"))
        assertTrue("找不到时必须正常退出，不能报错", script.endsWith("exit 0"))
    }

    @Test
    fun classifyTellsWhereThePathCameFrom() {
        val plan = AcpExecutableResolver.plan(
            explicit = listOf("/opt/eta/node/26.8.1/bin/codex-acp"),
            globs = listOf("/opt/eta/node/*/bin/codex-acp"),
            commands = listOf("codex-acp"),
        )
        assertEquals(
            AcpExecutableResolver.Source.EXPLICIT,
            AcpExecutableResolver.classify(plan, "/opt/eta/node/26.8.1/bin/codex-acp"),
        )
        assertEquals(AcpExecutableResolver.Source.PATH, AcpExecutableResolver.classify(plan, "codex-acp"))
        assertEquals(
            AcpExecutableResolver.Source.GLOB,
            AcpExecutableResolver.classify(plan, "/opt/eta/node/27.1.0/bin/codex-acp"),
        )
        assertEquals(AcpExecutableResolver.Source.MISSING, AcpExecutableResolver.classify(plan, ""))
    }

    @Test
    fun resolutionTrimsProbeOutput() {
        val plan = AcpExecutableResolver.plan(listOf("/a/x"), emptyList(), emptyList())
        val resolution = AcpExecutableResolver.resolution(plan, "  /a/x\n")
        assertEquals("/a/x", resolution.path)
        assertEquals(AcpExecutableResolver.Source.EXPLICIT, resolution.source)
    }
}
