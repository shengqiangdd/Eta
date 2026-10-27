package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 从库恢复工具状态：Running 只有在「所属 run 仍然活跃」时才保留，否则降级成 Unknown。
 *
 * 这条判据以前是「无条件把 Running 转 Unknown」，结果切会话回来后正在跑的工具
 * 丢掉计时与呼吸动画（用户看到的就是「卡住了」）。
 */
class ToolStatusRestoreTest {

    private val runA = "run-fae46d13-0e33-4ae1-934e-28f89604f76b"
    private val runB = "run-7de0839f-f7f5-4b53-9898-6de597cec16c"

    private fun id(runId: String, round: Int = 2, call: String = "call_00_abc") =
        "$runId-tool-$round-$call"

    @Test
    fun `running tool of active run stays running`() {
        assertEquals(
            ToolActivityStatusUi.Running,
            ToolStatusRestore.resolve("Running", id(runA), setOf(runA)),
        )
    }

    @Test
    fun `running tool of another run is not active`() {
        assertEquals(
            ToolActivityStatusUi.Unknown,
            ToolStatusRestore.resolve("Running", id(runA), setOf(runB)),
        )
    }

    @Test
    fun `running tool with no active run is stale`() {
        assertEquals(
            ToolActivityStatusUi.Unknown,
            ToolStatusRestore.resolve("Running", id(runA), emptySet()),
        )
    }

    @Test
    fun `finished statuses are preserved`() {
        assertEquals(ToolActivityStatusUi.Success, ToolStatusRestore.resolve("Success", id(runA), emptySet()))
        assertEquals(ToolActivityStatusUi.Failed, ToolStatusRestore.resolve("Failed", id(runA), emptySet()))
        assertEquals(ToolActivityStatusUi.Unknown, ToolStatusRestore.resolve("Unknown", id(runA), emptySet()))
    }

    @Test
    fun `missing or malformed status falls back to unknown`() {
        assertEquals(ToolActivityStatusUi.Unknown, ToolStatusRestore.resolve(null, id(runA), setOf(runA)))
        assertEquals(ToolActivityStatusUi.Unknown, ToolStatusRestore.resolve("", id(runA), setOf(runA)))
        assertEquals(ToolActivityStatusUi.Unknown, ToolStatusRestore.resolve("running", id(runA), setOf(runA)))
    }

    @Test
    fun `blank run id never matches`() {
        assertEquals(
            ToolActivityStatusUi.Unknown,
            ToolStatusRestore.resolve("Running", "tool-2-call_x", setOf("", "  ")),
        )
    }

    @Test
    fun `prefix match must be the whole run id`() {
        // runA 是 runB 的前缀关系不存在时也要保证不会因为「前缀相似」误判。
        assertEquals(
            ToolActivityStatusUi.Unknown,
            ToolStatusRestore.resolve("Running", id("${runA}extra"), setOf(runA)),
        )
        // 但工具消息 id 必须以 "-tool-" 紧跟 runId，别的类型（assistant/thinking）不参与判定。
        assertEquals(
            ToolActivityStatusUi.Unknown,
            ToolStatusRestore.resolve("Running", "$runA-assistant-1-0", setOf(runA)),
        )
    }

    @Test
    fun `multiple active runs are all honored`() {
        assertEquals(
            ToolActivityStatusUi.Running,
            ToolStatusRestore.resolve("Running", id(runB), setOf(runA, runB)),
        )
    }
}
