package io.github.mangi.eta.agent.terminal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 环境提示合并。
 *
 * 真机踩到的缺陷：工具回执里 `environment_note` 是 JSON null 时，
 * `optString` 返回字面量 "null"，合并后变成「null 本次 environment=android…」。
 * 这几条用例把它钉住 —— 一旦有人再把 NULL 当字符串用，这里会红。
 */
class EnvironmentNoteMergeTest {

    private val note =
        "本次 environment=android，但路径属于 linux 环境；已自动切换为 environment=linux。"

    @Test fun nullValueDoesNotLeakLiteralNull() {
        val merged = EnvironmentNoteMerge.merge("""{"ok":true,"environment_note":null}""", note)
        assertEquals(note, JSONObject(merged).getString("environment_note"))
    }

    @Test fun missingKeyIsFilledWithNote() {
        val merged = EnvironmentNoteMerge.merge("""{"ok":true}""", note)
        assertEquals(note, JSONObject(merged).getString("environment_note"))
    }

    @Test fun existingTextIsKeptBeforeNewNote() {
        val merged = EnvironmentNoteMerge.merge(
            """{"ok":false,"environment_note":"设备侧没有该目录"}""",
            note,
        )
        assertEquals("设备侧没有该目录 $note", JSONObject(merged).getString("environment_note"))
    }

    @Test fun literalNullTextIsNotTreatedAsContent() {
        // 旧实现写坏的存量数据：值就是字符串 "null"。
        val merged = EnvironmentNoteMerge.merge("""{"ok":true,"environment_note":"null"}""", note)
        assertEquals(note, JSONObject(merged).getString("environment_note"))
    }

    @Test fun nullNoteKeepsResultUntouched() {
        val original = """{"ok":true,"environment_note":null}"""
        assertEquals(original, EnvironmentNoteMerge.merge(original, null))
    }

    @Test fun nonJsonResultIsReturnedAsIs() {
        val original = "not a json"
        assertEquals(original, EnvironmentNoteMerge.merge(original, note))
    }
}
