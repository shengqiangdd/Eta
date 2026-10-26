package io.github.mangi.eta.agent.terminal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `read_file` 的 `start_line` 定位。
 *
 * 真机踩到的坑：386 KiB / 5,217 行的文件传 `start_line=5148` 时，扫描预算（原 256 KiB）
 * 中途用完，回执是 `content=""` + `stop_reason=scan_limit` —— 看起来像「文件是空的」。
 * 这里钉住两件事：预算内的远距离定位必须成功；超预算时必须给出可执行的下一步。
 */
class AgentFileOperationsScanLimitTest {

    @get:Rule val temporary = TemporaryFolder()

    private fun operations(): AgentFileOperations = AgentFileOperations(LocalFileToolBackend(temporary.root))

    @Test
    fun startLineInsideRaisedBudgetIsReached() {
        // 600 KiB / 6,000 行：旧预算（256 KiB）在这里必然 scan_limit，新预算（1 MiB）应当直接命中。
        val line = "y".repeat(99) + "\n"
        val file = temporary.newFile("big.txt").apply { writeText(line.repeat(6_000)) }

        val result = JSONObject(operations().readFile(file.name, startLine = 5_900))

        assertEquals(5_900, result.getInt("start_line"))
        assertTrue("应直接定位到第 5900 行：$result", result.getBoolean("start_line_reached"))
        assertTrue(result.getString("content").startsWith("y".repeat(99)))
    }

    @Test
    fun startLineBeyondBudgetExplainsHowToContinue() {
        // 2.1 MiB / 21,000 行：远超 1 MiB 预算，必然命中 scan_limit。
        val line = "x".repeat(99) + "\n"
        val file = temporary.newFile("huge.txt").apply { writeText(line.repeat(21_000)) }

        val result = JSONObject(operations().readFile(file.name, startLine = 20_000))

        assertEquals("scan_limit", result.getString("stop_reason"))
        assertEquals("", result.getString("content"))
        // 关键：必须说清「不是空文件」，并给出可执行的两条出路。
        val hint = result.getString("hint")
        assertTrue("要说清不是空文件：$hint", hint.contains("不代表文件为空"))
        assertTrue("要给出续读方式：$hint", hint.contains("offset_bytes="))
        assertEquals(
            result.getLong("next_offset_bytes"),
            result.getJSONObject("resume").getLong("offset_bytes"),
        )

        // 按 resume 续读能真的拿到正文。
        val resumed = JSONObject(
            operations().readFile(file.name, offsetBytes = result.getJSONObject("resume").getLong("offset_bytes")),
        )
        assertTrue("续读应拿到正文：$resumed", resumed.getString("content").isNotEmpty())
    }
}
