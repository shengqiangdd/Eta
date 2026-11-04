package io.github.mangi.eta.agent.acp

import org.json.JSONArray
import org.json.JSONObject

/**
 * ACP 会话的模型信息与选择（纯逻辑，可单测）。
 *
 * 背景：委派时用的是哪个模型，过去完全不可见 —— codex-acp 在 `session/new` 的
 * `models` 里明确回了 `currentModelId` 与 `availableModels`，而 Eta 既不读、也不展示，
 * 更没法按用户说的「用 X 模型，别用默认的」去切换。
 */
internal object AcpModels {

    data class Info(
        /** 代理自己报的当前模型；null 表示代理没有提供模型信息。 */
        val currentModelId: String? = null,
        val available: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = currentModelId == null && available.isEmpty()
    }

    sealed interface Choice {
        /** [verified] 为 false 表示代理没报清单，无法本地校验（只能让它自己判断）。 */
        data class Use(val modelId: String, val verified: Boolean) : Choice

        /** 请求的模型不在代理清单里：当场失败并给出可用项，而不是让它后面诡异失败。 */
        data class Reject(val message: String) : Choice
    }

    const val MAX_MODEL_ID_CHARS = 120
    const val MAX_LISTED_MODELS = 20

    fun parse(sessionNewResult: JSONObject?): Info {
        val models = sessionNewResult?.optJSONObject("models") ?: return Info()
        val current = models.optString("currentModelId").trim().takeIf { it.isNotEmpty() }
        val array = models.optJSONArray("availableModels") ?: JSONArray()
        val available = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("modelId").trim()
                if (id.isNotEmpty()) add(id)
            }
        }
        return Info(currentModelId = current, available = available)
    }

    /**
     * 这个代理是否会把模型**写进配置文件**（从而不必再走 `session/set_model`）。
     *
     * 只有 codex 成立：它读临时 `CODEX_HOME/config.toml` 的 `model`。
     * Claude **没有这一层** —— `ANTHROPIC_MODEL` 是 Claude Code（CLI）自己的变量，
     * `claude-code-acp` 适配器根本不读它。2026-11-04 实测：设了
     * `ANTHROPIC_MODEL=claude-sonnet-5`，代理仍去请求内置的
     * `claude-sonnet-4-5-20250929`，然后回 Internal error。
     *
     * 为什么单独抽出来：这个判断错了不会报「配置有问题」，只会表现成
     * 「指定了模型却报另一个模型不存在」—— 排查成本极高。
     */
    fun modelComesFromConfig(agentId: String): Boolean = agentId == "codex"

    /**
     * 决定「要不要设置模型」：null = 不指定（用代理默认）。
     *
     * 为什么必须本地校验：真机实测（PKX110，2026-10-27）codex-acp 的 `session/set_model`
     * **不校验** modelId —— `no-such-model-xyz` 也返回 `{}`。错名字不会当场报错，
     * 只会在后面用着用着诡异失败，用户根本看不出是模型名写错了。
     */
    fun choose(requested: String?, info: Info): Choice? {
        val wanted = requested?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (wanted.length > MAX_MODEL_ID_CHARS) {
            return Choice.Reject("模型名过长（≤$MAX_MODEL_ID_CHARS 字）：${wanted.take(40)}…")
        }
        if (info.available.isEmpty()) return Choice.Use(wanted, verified = false)
        if (wanted in info.available) return Choice.Use(wanted, verified = true)
        val listed = info.available.take(MAX_LISTED_MODELS).joinToString("、")
        val suffix = if (info.available.size > MAX_LISTED_MODELS) {
            " …（共 ${info.available.size} 个，全部见 available_models）"
        } else {
            ""
        }
        return Choice.Reject("代理没有模型「$wanted」。可用：$listed$suffix")
    }

    /**
     * 带 config 的选择：只有当「值得切」时才切。
     *
     * 有临时 config 时，模型其实已经写进去了。此时：
     * - 请求的就是 config 里的那个 → 不切（切了没意义）；
     * - 请求的名字不在代理清单里（含「只有 `model/low` 这类带档位变体」的情况）→ 不切，用 config
     *   里的，**不失败**。真机上清单是 `gpt-5.6-sol/low` 这种带档位的名字，而 config 里不带档位的
     *   `gpt-5.6-sol` 本来就能跑；把带档位名字交给 `session/set_model` 反而 `Internal error`
     *   （2026-10-28 实测）。旧行为会直接 Reject，把可选参数变成「委派彻底不能用」。
     * - 其余（确实在清单里、且不是 config 那个）→ 走 [choose] 的本地校验。
     *
     * 实际用了谁看结果里的 `model` / `model_requested` 两列。
     */
    fun chooseWithConfig(requested: String?, configModel: String?, info: Info): Choice? {
        val wanted = requested?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (configModel == null) return choose(wanted, info)
        if (wanted == configModel) return null
        // 清单里没有精确同名项（含「只有 `model/low` 这类带档位变体」）时**不切**：
        // 带档位的名字交给 set_model 会 Internal error，而 config 里不带档位的名字本来就能跑。
        // 宁可实际用 config 的模型（结果里 model / model_requested 两列会暴露差异），也不要失败。
        if (wanted !in info.available) return null
        return choose(wanted, info)
    }

    /** 结果里回传的模型清单（数量有上限，避免把 41 个模型名塞进每一次工具结果）。 */
    fun availableJson(info: Info, limit: Int = MAX_LISTED_MODELS): JSONArray =
        JSONArray().also { array ->
            info.available.take(limit).forEach(array::put)
        }

    fun describe(info: Info): JSONObject = JSONObject()
        .put("current", info.currentModelId ?: JSONObject.NULL)
        .put("available_count", info.available.size)
        .put("available_models", availableJson(info))
}
