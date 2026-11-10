package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐项禁用的端到端行为：在**真实工具目录**上验证，而不是只测过滤器本身。
 *
 * 这一层是必须有的。此前只有 [AgentToolDisableFilterTest]，它用手写的 JSON 数组做输入，
 * 在顶层放 `name` —— 而真实目录是 OpenAI 风格，名字藏在 `function.name`。结果过滤器
 * 读到的永远是空串：功能**从未生效**，而单元测试全绿。这个测试就是为了让那种情况不可能
 * 再发生：它跑 `AgentToolCatalog.build()`，产出的就是模型实际看到的那份 tools。
 */
class AgentToolDisableEndToEndTest {

    // 全部能力打开：只考察逐项禁用，不让能力缺失把工具提前裁掉。
    private val capabilities = AgentToolCapabilities(rootAvailable = true)

    private fun catalog(
        terminal: Boolean = true,
        browser: Boolean = true,
        deviceDirect: Boolean = true,
        read: Boolean = true,
        action: Boolean = true,
    ): JSONArray = AgentToolCatalog.build(
        terminalTools = terminal,
        browserTools = browser,
        deviceDirectTools = deviceDirect,
        deviceSensitiveReadTools = read,
        deviceSensitiveActionTools = action,
        skillGitHubDiscovery = true,
        skillGitHubInstall = true,
        memoryTools = true,
        memoryWritable = true,
        capabilities = capabilities,
        localWebSearch = true,
    )

    private fun names(array: JSONArray): List<String> = AgentToolDisableFilter.namesOf(array)

    @Test
    fun `真实目录里能找到 terminal 和 read_file`() {
        val all = names(catalog())
        // 这是所有断言的前提。名字对不上，「禁用生效」可能只是空气。
        assertTrue("目录缺少 terminal：${all.take(20)}", "terminal" in all)
        assertTrue("目录缺少 read_file：${all.take(20)}", "read_file" in all)
        assertTrue("目录规模异常，可能没构建成功：${all.size}", all.size > 50)
    }

    @Test
    fun `禁用 terminal 后模型看不到它，但保留 read_file`() {
        val filtered = names(AgentToolDisableFilter.apply(catalog(), setOf("terminal")))
        assertFalse("terminal 仍可被模型调用", "terminal" in filtered)
        assertTrue("误删了 read_file", "read_file" in filtered)
    }

    @Test
    fun `禁用 terminal 不会连带它的同类工具`() {
        // terminal / terminal_job / terminal_daemon 是三个独立工具名，
        // 只禁用 terminal 时另外两个应保留（用户禁的是一个名字，不是前缀）。
        val filtered = names(AgentToolDisableFilter.apply(catalog(), setOf("terminal")))
        assertTrue("误删了 terminal_job", "terminal_job" in filtered)
        assertTrue("误删了 terminal_daemon", "terminal_daemon" in filtered)
    }

    @Test
    fun `禁用一组工具后剩余顺序与目录一致`() {
        val all = names(catalog())
        val banned = setOf("terminal", "read_file", "write_file")
        val filtered = names(AgentToolDisableFilter.apply(catalog(), banned))
        assertEquals(all.filterNot { it in banned }, filtered)
        assertEquals(all.size - banned.count { it in all }, filtered.size)
    }

    @Test
    fun `禁用不存在的名字不会误删任何工具`() {
        val all = names(catalog())
        val filtered = names(AgentToolDisableFilter.apply(catalog(), setOf("no_such_tool")))
        assertEquals(all, filtered)
    }

    @Test
    fun `目录里的工具名全是小写，逐项大小写敏感不会失效`() {
        // parse 不改写大小写。若目录里出现大写名，用户存的小写就不会命中，禁用静默失效。
        val upper = names(catalog()).filter { name -> name.any(Char::isUpperCase) }
        assertTrue("目录出现大写工具名，逐项禁用会静默失效：$upper", upper.isEmpty())
    }

    @Test
    fun `分类开关关闭时逐项禁用仍然正确`() {
        val filtered = names(AgentToolDisableFilter.apply(catalog(terminal = false), setOf("read_file")))
        assertFalse("terminal 组已关，不该出现", "terminal" in filtered)
        assertFalse("read_file 已被逐项禁用", "read_file" in filtered)
    }
}
