package io.github.mangi.eta.ui.components

import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合并后的「委派提供商与模型」选择器：分组与搜索。
 *
 * 为什么要单测这两件事：它们直接决定「用户能不能找到自己要的模型」。
 * 选择器一旦把整组过滤没了、或者搜索只搜提供商不搜模型，
 * 用户看到的就是一个空对话框 —— 而这正是上一轮的 bug 形态。
 */
class AcpModelGroupsTest {
    private fun model(id: String) = Model(id = id, modelId = id, displayName = id)

    private fun custom(id: String, name: String, vararg models: String) = CustomProviderSetting(
        id = id,
        name = name,
        baseUrl = "https://$id.example.com/v1",
        apiKey = "sk-$id",
        models = models.map { model(it) },
    )

    @Test
    fun providerWithoutEnabledModelsIsDropped() {
        // 委派必须带一个模型名。列出「一个模型都没有」的提供商，
        // 只会制造一次注定失败的点选。
        val empty = custom("empty", "空提供商")
        val full = custom("full", "有模型", "m1")

        val groups = acpModelGroups(listOf(empty, full), agentId = "codex")

        assertEquals(listOf("full"), groups.map { it.provider.id })
    }

    @Test
    fun groupsKeepProviderOrderAndModelOrder() {
        val a = custom("a", "A 商", "a1", "a2")
        val b = custom("b", "B 商", "b1")

        val groups = acpModelGroups(listOf(a, b), agentId = "codex")

        assertEquals(listOf("a", "b"), groups.map { it.provider.id })
        assertEquals(listOf("a1", "a2"), groups[0].models)
        assertEquals(listOf("b1"), groups[1].models)
    }

    @Test
    fun searchMatchesModelName() {
        val groups = acpModelGroups(
            listOf(custom("a", "A 商", "gpt-4o", "o1-mini"), custom("b", "B 商", "claude-3")),
            agentId = "codex",
        )

        val hit = groups.filterByQuery("4o")

        assertEquals(1, hit.size)
        assertEquals(listOf("gpt-4o"), hit[0].models)
    }

    @Test
    fun searchMatchesProviderNameAndKeepsAllItsModels() {
        // 搜提供商名时应该给出它下面的**全部**模型，而不是一个都不给。
        val groups = acpModelGroups(
            listOf(custom("a", "硅基流动", "m1", "m2"), custom("b", "其他", "m3")),
            agentId = "codex",
        )

        val hit = groups.filterByQuery("硅基")

        assertEquals(1, hit.size)
        assertEquals(listOf("m1", "m2"), hit[0].models)
    }

    @Test
    fun searchIsCaseInsensitiveAndTrims() {
        val groups = acpModelGroups(listOf(custom("a", "A", "GPT-4O")), agentId = "codex")

        assertEquals(1, groups.filterByQuery("  gpt-4o  ").size)
    }

    @Test
    fun blankQueryKeepsEverything() {
        val groups = acpModelGroups(listOf(custom("a", "A", "m1"), custom("b", "B", "m2")), agentId = "codex")

        assertEquals(groups, groups.filterByQuery("   "))
    }

    @Test
    fun noMatchYieldsEmptyNotEverything() {
        // 反向断言：搜不到时不能把全部结果原样返回（那是「搜索框没接上」的典型症状）。
        val groups = acpModelGroups(listOf(custom("a", "A", "m1")), agentId = "codex")

        assertTrue(groups.filterByQuery("完全不存在的名字").isEmpty())
    }

    @Test
    fun claudePoolShowsAnthropicProviders() {
        // 与 acpSelectableProviders 的联动：Claude 该看到 anthropic 类型。
        val anthropic = AnthropicProviderSetting(
            id = "anthropic",
            name = "Anthropic",
            baseUrl = "https://api.anthropic.com",
            apiKey = "sk-ant",
            models = listOf(model("claude-sonnet")),
        )

        val groups = acpModelGroups(listOf(anthropic), agentId = "claude")

        assertEquals(listOf("anthropic"), groups.map { it.provider.id })
        assertEquals(listOf("claude-sonnet"), groups[0].models)
    }

    // ---- 两级下钻：模型页只过滤当前这一组的模型 ----

    private fun group(vararg models: String) = AcpModelGroup(
        provider = custom("p", "提供商", *models),
        models = models.toList(),
    )

    @Test
    fun modelFilterReturnsAllWhenQueryIsBlank() {
        // 进了模型页还没打字时，必须把该提供商的模型全列出来。
        // 这里若返回空，用户点进去看到的就是一个空列表。
        val g = group("gpt-5", "gpt-5-mini")
        assertEquals(listOf("gpt-5", "gpt-5-mini"), g.filterModelsByQuery(""))
        assertEquals(listOf("gpt-5", "gpt-5-mini"), g.filterModelsByQuery("   "))
    }

    @Test
    fun modelFilterMatchesIgnoringCase() {
        val g = group("GPT-5", "claude-sonnet")
        assertEquals(listOf("GPT-5"), g.filterModelsByQuery("gpt"))
    }

    @Test
    fun modelFilterReturnsEmptyWhenNothingMatches() {
        // 空结果由界面渲染成「没有匹配的模型」，不能悄悄退回全量 ——
        // 那会让用户以为搜索没生效。
        assertEquals(emptyList<String>(), group("gpt-5").filterModelsByQuery("claude"))
    }

    @Test
    fun modelFilterNeverLeaksOtherProvidersModels() {
        // 两级下钻的意义就在这里：模型页只看得到这一组。
        val a = AcpModelGroup(custom("a", "A", "a-1", "a-2"), listOf("a-1", "a-2"))
        val b = AcpModelGroup(custom("b", "B", "b-1"), listOf("b-1"))

        assertEquals(listOf("a-1", "a-2"), a.filterModelsByQuery(""))
        assertEquals(emptyList<String>(), a.filterModelsByQuery("b-1"))
        assertEquals(listOf("b-1"), b.filterModelsByQuery("b"))
    }
}
