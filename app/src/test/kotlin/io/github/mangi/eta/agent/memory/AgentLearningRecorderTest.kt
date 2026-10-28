package io.github.mangi.eta.agent.memory

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentLearningRecorderTest {

    @Test
    fun terminalFailureUsesExitCodeAndFirstStderrLine() {
        // 真机实测的 terminal 失败结果形状：没有 code / message，只有 exit_code 与 stderr。
        val json = JSONObject(
            """{"ok":false,"tool":"terminal","status":"failed","exit_code":1,"stdout":"","stderr":"ls: /nonexistent-eta-probe-abc: No such file or directory"}""",
        )

        val (code, message) = AgentLearningRecorder.describeFailure(json)

        assertEquals("EXIT_1", code)
        assertEquals("ls: /nonexistent-eta-probe-abc: No such file or directory", message)
    }

    @Test
    fun stderrFirstLineOnlyKeepsTheActionablePart() {
        val json = JSONObject(
            """{"ok":false,"exit_code":2,"stderr":"\n  sh: cd: no such file  \nsecond line"}""",
        )

        val (code, message) = AgentLearningRecorder.describeFailure(json)

        assertEquals("EXIT_2", code)
        assertEquals("sh: cd: no such file", message)
    }

    @Test
    fun explicitCodeAndMessageWinOverExitCode() {
        val json = JSONObject(
            """{"ok":false,"code":"MEMORY_TOO_LARGE","message":"记忆文件不能超过 1 MiB","exit_code":9}""",
        )

        val (code, message) = AgentLearningRecorder.describeFailure(json)

        assertEquals("MEMORY_TOO_LARGE", code)
        assertEquals("记忆文件不能超过 1 MiB", message)
    }

    @Test
    fun errorFieldIsUsedWhenMessageMissing() {
        val json = JSONObject("""{"ok":false,"code":"TIMEOUT","error":"请求超时"}""")

        val (code, message) = AgentLearningRecorder.describeFailure(json)

        assertEquals("TIMEOUT", code)
        assertEquals("请求超时", message)
    }

    @Test
    fun stdoutIsTheLastResortBeforeGenericText() {
        val json = JSONObject("""{"ok":false,"stdout":"partial output\nmore"}""")

        val (code, message) = AgentLearningRecorder.describeFailure(json)

        assertEquals("ERR", code)
        assertEquals("partial output", message)
    }

    @Test
    fun zeroExitCodeFallsBackToGenericCode() {
        // exit_code=0 不能写成 EXIT_0：那不是“退出码 0 的失败”，而是工具没给可用的错误码。
        val json = JSONObject("""{"ok":false,"exit_code":0}""")

        val (code, message) = AgentLearningRecorder.describeFailure(json)

        assertEquals("ERR", code)
        assertEquals("工具返回失败", message)
    }

    @Test
    fun successfulResultsAreNeverRecorded() {
        val json = JSONObject("""{"ok":true,"exit_code":0}""")

        // record() 在 ok=true 时直接返回 false，不会碰记忆仓库（单测里 store 未初始化也安全）。
        assertEquals(false, AgentLearningRecorder.record("terminal", json.toString()))
    }
}