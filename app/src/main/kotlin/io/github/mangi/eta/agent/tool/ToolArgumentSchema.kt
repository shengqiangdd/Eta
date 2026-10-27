package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.acp.AgentAcpTools
import io.github.mangi.eta.agent.model.AgentAskUserToolCatalog
import io.github.mangi.eta.agent.model.AgentBrowserToolCatalog
import io.github.mangi.eta.agent.model.AgentContextAppToolCatalog
import io.github.mangi.eta.agent.model.AgentDeviceToolCatalog
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.AgentFileVisionToolCatalog
import io.github.mangi.eta.agent.model.AgentGestureToolCatalog
import io.github.mangi.eta.agent.model.AgentGoalToolCatalog
import io.github.mangi.eta.agent.model.AgentMemoryToolCatalog
import io.github.mangi.eta.agent.model.AgentPersonalSearchToolCatalog
import io.github.mangi.eta.agent.model.AgentSkillToolCatalog
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import io.github.mangi.eta.agent.model.AgentTextSystemToolCatalog
import io.github.mangi.eta.agent.model.AgentTodoToolCatalog
import io.github.mangi.eta.agent.model.AgentWebToolCatalog
import io.github.mangi.eta.agent.plugin.AgentPluginTools
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray

/**
 * 工具名 → schema 声明的参数名，供 [ToolArgumentHintPolicy] 判断「这个参数名到底存不存在」。
 *
 * 唯一真相源仍是各 catalog 的 schema：这里只是把它们的 `parameters.properties` 键收集起来，
 * 不另维护一份手写清单（那种表一定会漂移）。首次使用时构建一次并缓存。
 *
 * 某些 catalog 要按开关拼装（memory 可写、web 是否含搜索等），这里一律用「最全」的参数：
 * 提示只需要知道参数名是否存在，多出来的名字不会造成误报（它们确实被 schema 声明了）。
 * 拼装失败（某个 catalog 抛异常）只跳过那一个，不影响其余工具。
 */
internal object ToolArgumentSchema {

    private val cache = ConcurrentHashMap<String, Set<String>>()
    @Volatile private var built = false

    /** 没登记的工具返回 null：宁可不提示，也不要对着不认识的工具乱报。 */
    fun declaredKeys(toolName: String): Set<String>? {
        ensureBuilt()
        return cache[toolName]
    }

    private fun ensureBuilt() {
        if (built) return
        synchronized(this) {
            if (built) return
            val tools = JSONArray()
            val appenders: List<(JSONArray) -> Unit> = listOf(
                { array -> AgentContextAppToolCatalog.appendTo(array) },
                // device 目录要按开关拼装（direct / 敏感读 / 敏感写）。这里一律传 true：
                // 提示只需要知道「这个名字到底被声明过没有」，多收录不会造成误报
                // （它们确实在 schema 里）。fix168 时因签名带参数被跳过，结果这一百多个
                // 设备工具收到错参数仍然静默 —— 与 fix168 修的是同一个病。
                { array ->
                    AgentDeviceToolCatalog.appendTo(
                        array,
                        directTools = true,
                        sensitiveReadTools = true,
                        sensitiveActionTools = true,
                    )
                },
                { array -> AgentGestureToolCatalog.appendTo(array) },
                { array -> AgentVirtualDisplayTools.appendTo(array) },
                { array -> AgentAcpTools.appendTo(array) },
                { array -> AgentPluginTools.appendTo(array) },
                { array -> AgentTextSystemToolCatalog.appendTo(array) },
                { array -> AgentTodoToolCatalog.appendTo(array) },
                { array -> AgentGoalToolCatalog.appendTo(array) },
                { array -> AgentAskUserToolCatalog.appendTo(array) },
                { array -> AgentBrowserToolCatalog.appendTo(array) },
                { array -> AgentTerminalToolCatalog.appendTo(array) },
                { array -> AgentFileToolCatalog.appendTo(array) },
                { array -> AgentFileVisionToolCatalog.appendTo(array) },
                { array -> AgentPersonalSearchToolCatalog.appendTo(array) },
                { array -> AgentMemoryToolCatalog.appendTo(array) },
                { array -> AgentWebToolCatalog.appendTo(array) },
                { array -> AgentSkillToolCatalog.appendTo(array) },
            )
            appenders.forEach { append -> runCatching { append(tools) } }

            for (index in 0 until tools.length()) {
                val function = tools.optJSONObject(index)?.optJSONObject("function") ?: continue
                val name = function.optString("name")
                if (name.isBlank()) continue
                val properties = function.optJSONObject("parameters")?.optJSONObject("properties")
                val keys = properties?.keys()?.asSequence()?.toSet().orEmpty()
                if (keys.isNotEmpty()) cache[name] = keys
            }
            built = true
        }
    }

    /** 仅测试用：强制重建（catalog 是静态的，正常不需要）。 */
    internal fun resetForTest() {
        cache.clear()
        built = false
    }
}
