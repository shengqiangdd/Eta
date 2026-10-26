package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAskUserToolCatalogTest {

    @Test
    fun questionWithOptionsIsAccepted() {
        val ask = AgentAskUserToolCatalog.parse(
            JSONObject().put("question", "要清理哪个应用？").put("options", JSONArray().put("微信").put("QQ")),
        )
        assertEquals("要清理哪个应用？", ask.question)
        assertEquals(listOf("微信", "QQ"), ask.options)
    }

    @Test
    fun questionWithoutOptionsAllowsAFreeAnswer() {
        assertTrue(AgentAskUserToolCatalog.parse(JSONObject().put("question", "继续吗？")).options.isEmpty())
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        val ask = AgentAskUserToolCatalog.parse(
            JSONObject().put("question", "  继续吗？ ").put("options", JSONArray().put(" 是 ")),
        )
        assertEquals("继续吗？", ask.question)
        assertEquals(listOf("是"), ask.options)
    }

    @Test
    fun blankQuestionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentAskUserToolCatalog.parse(JSONObject().put("question", "   "))
        }
    }

    @Test
    fun oversizedQuestionIsRejected() {
        val question = "问".repeat(AgentAskUserToolCatalog.MAX_QUESTION_CHARS + 1)
        assertThrows(IllegalArgumentException::class.java) {
            AgentAskUserToolCatalog.parse(JSONObject().put("question", question))
        }
    }

    @Test
    fun tooManyOptionsAreRejected() {
        val options = JSONArray()
        repeat(AgentAskUserToolCatalog.MAX_OPTIONS + 1) { index -> options.put("选项$index") }
        assertThrows(IllegalArgumentException::class.java) {
            AgentAskUserToolCatalog.parse(JSONObject().put("question", "选一个").put("options", options))
        }
    }

    @Test
    fun blankAndDuplicateOptionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentAskUserToolCatalog.parse(
                JSONObject().put("question", "选一个").put("options", JSONArray().put("A").put(" ")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentAskUserToolCatalog.parse(
                JSONObject().put("question", "选一个").put("options", JSONArray().put("A").put("A")),
            )
        }
    }

    @Test
    fun schemaRequiresTheQuestionAndForbidsExtraFields() {
        val parameters = AgentAskUserToolCatalog.schema()
            .getJSONObject("function")
            .getJSONObject("parameters")
        assertEquals(JSONArray().put("question").toString(), parameters.getJSONArray("required").toString())
        assertFalse(parameters.getBoolean("additionalProperties"))
        assertEquals(
            AgentAskUserToolCatalog.MAX_OPTIONS,
            parameters.getJSONObject("properties").getJSONObject("options").getInt("maxItems"),
        )
    }
}
