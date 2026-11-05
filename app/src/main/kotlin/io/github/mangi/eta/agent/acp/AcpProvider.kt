package io.github.mangi.eta.agent.acp

import io.github.mangi.eta.data.model.CustomHeader

/**
 * 把 Eta 自己的模型提供商配置翻译成 codex-acp 的启动配置。
 *
 * 为什么需要：codex 的 provider 层不在 ACP 协议里，默认只能靠
 * `/root/.codex/config.toml` 的 `model_provider` 决定。那样每换一个 provider
 * 就要手改文件，新设备还得让 agent 重新映射一遍。
 *
 * 这里改成「每次委派给 codex 生成一个临时 CODEX_HOME」：
 * - 不碰用户的全局 config.toml；
 * - provider / model / key 都来自 Eta 的模型提供商设置；
 * - 换 provider 只是 `acp_delegate(provider=...)` 的参数变化。
 */
internal object AcpProvider {

    /** codex 通过 env_key 读取这个变量；值只在 guest 环境内 export。 */
    const val ENV_KEY = "ETA_CODEX_API_KEY"

    /**
     * 订阅登录（ChatGPT OAuth）写进 auth.json 的三个字段。
     *
     * 为什么拆成三个变量而不是直接传一整段 JSON：secret 单值上限 4096 字符，
     * 而 access_token 与 id_token 两个 JWT 加起来通常会超。拆开后每个都在限内。
     *
     * 值仍然只走「一次性 0600 脚本」那条通道，不进 argv —— 脚本里只出现变量名，
     * 所以 `ps` 看不到 token。
     */
    const val ENV_ACCESS_TOKEN = "ETA_CODEX_ACCESS_TOKEN"

    /** 订阅登录的 id_token；codex 只解码它读 account_id / plan_type，不验签。 */
    const val ENV_ID_TOKEN = "ETA_CODEX_ID_TOKEN"

    /** 订阅登录的账号 id；缺了它 auth.json 无效。 */
    const val ENV_ACCOUNT_ID = "ETA_CODEX_ACCOUNT_ID"

    /**
     * ChatGPT 订阅登录的认证材料。
     *
     * 注意**故意不带 refresh_token**：codex 会用它刷新并写回自己那份文件，
     * 而 OpenAI 的 refresh token 会轮换 —— 一旦 codex 刷过，Eta 手里这份就失效了，
     * 用户下次要重新登录。相比之下，不带 refresh token 的代价只是
     * 「超过 access_token 有效期（约 1 小时）的委派无法续期」，
     * 而委派本身的超时上限是 30 分钟，够用。宁可长任务失败一次，也不碰用户的登录态。
     */
    data class Subscription(
        val accessToken: String,
        val idToken: String,
        val accountId: String,
    ) {
        val usable: Boolean
            get() = accessToken.isNotBlank() && accountId.isNotBlank() && tokensAreSafe()

        /**
         * 三个值都要嵌进 shell 拼的 JSON 里，所以必须确认它们不含双引号、反斜杠、换行。
         *
         * JWT 与账号 id 本来就只有 base64url / UUID 字符，这里是把「本来如此」
         * 当成必须校验的前提 —— 否则一个带 `"` 的值能凭空造出额外 JSON 字段。
         */
        fun tokensAreSafe(): Boolean = listOf(accessToken, idToken, accountId).all { value ->
            value.all { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_' }
        }
    }

    /**
     * codex 0.164+ 只支持 Responses API：`wire_api = "chat"` 会在加载 config.toml 时
     * 直接报 "no longer supported"，请求根本发不出去。
     *
     * 这**不是** Eta 自己的 endpointMode —— 那个字段只决定 Eta 请求
     * `/chat/completions` 还是 `/responses`。所以同一条 provider 配置可以两用：
     * Eta 走它选的协议，委派 codex 固定走 Responses。
     */
    const val CODEX_WIRE_API = "responses"

    data class Config(
        val id: String,
        val name: String,
        val baseUrl: String,
        val apiKey: String,
        /** codex 的 wire_api；codex 0.164+ 只有 `responses` 可用，见 [CODEX_WIRE_API]。 */
        val wireApi: String,
        /** 未显式指定 model 时使用的默认模型。 */
        val defaultModel: String? = null,
        /**
         * 自定义请求头。中转站常把鉴权 / 路由 / 版本号放在自定义头里：
         * 不写进 config.toml，codex 侧就是 401 / 404，而同一份配置在 Eta 自己的
         * 请求里完全正常（那条链路本来就会带 `customHeaders`）。
         */
        val customHeaders: List<CustomHeader> = emptyList(),
        /**
         * 非空表示这次委派用「订阅登录」而不是 API key。
         *
         * 两种模式的差别不只是凭据位置：API key 要写 `[model_providers.*]` + `env_key`，
         * 而订阅登录必须**不指定 model_provider**（走 codex 内置的 openai provider，
         * 由同目录的 auth.json 提供认证）。两边同时写会让 codex 拿着 API key 那套去请求，
         * 订阅的 token 根本不会被读到。
         */
        val subscription: Subscription? = null,
    ) {
        val usable: Boolean
            get() = id.isNotBlank() && wireApi == CODEX_WIRE_API &&
                if (subscription != null) {
                    subscription.usable
                } else {
                    baseUrl.isNotBlank() && apiKey.isNotBlank()
                }
    }

    /**
     * 生成 codex 的 config.toml 内容。
     *
     * 注意：API key 不写进文件，只通过 [ENV_KEY] 环境变量传给 codex。
     * image_generation 对 ACP 委派没有意义，而且部分中转商未开通该工具会直接 403，
     * 所以在这里固定关掉（不影响 Eta 自己的模型请求）。
     */
    fun configToml(provider: Config, model: String): String = buildString {
        append("model = ").append(tomlString(model)).append('\n')
        // 订阅登录：**不能**写 model_provider。写了 codex 就会走下面那套
        // 「自定义 provider + env_key」的路径，而那条路径要的是 API key；
        // 订阅的 token 只会躺在同目录的 auth.json 里没人读。
        if (provider.subscription == null) {
            append("model_provider = ").append(tomlString(provider.id)).append('\n')
        }
        append("model_reasoning_summary = \"none\"\n\n")
        append("[features]\n")
        append("image_generation = false\n\n")
        if (provider.subscription != null) return@buildString
        append("[model_providers.").append(tomlKey(provider.id)).append("]\n")
        append("name = ").append(tomlString(provider.name)).append('\n')
        append("base_url = ").append(tomlString(provider.baseUrl)).append('\n')
        append("env_key = ").append(tomlString(ENV_KEY)).append('\n')
        append("wire_api = ").append(tomlString(provider.wireApi)).append('\n')
        // 上游挂起时 codex 默认会重试到 6 次才彻底失败：实测（本机，假 server）
        // 默认 6 次请求 / 7 秒（连接层很快拒），而真实中转站每次要 40 秒「等不到响应」时
        // 就是 240 秒黑洞 —— 用户只看到「一直在跑」。1 次重试（= 最多 2 次尝试）足够吸收抖动。
        append("request_max_retries = 1\n")
        append("stream_max_retries = 1\n")
        // 自定义头必须作为 `[model_providers.<id>.http_headers]` 子表跟在父表键之后。
        // 键统一用引号包：`X-Foo` 虽也是合法裸键，但写死成引号能兼容任意头名（含 `.`、`:`）。
        val headers = provider.customHeaders.filter { it.name.isNotBlank() }
        if (headers.isNotEmpty()) {
            append('\n')
            append("[model_providers.").append(tomlKey(provider.id)).append(".http_headers]\n")
            headers.forEach { header ->
                append(tomlString(header.name.trim())).append(" = ")
                    .append(tomlString(header.value)).append('\n')
            }
        }
    }

    /**
     * Claude Code（经 `claude-code-acp`）读的变量名。
     *
     * 与 codex 的差别：codex 靠 `config.toml` 里的 `env_key` 指一个自定义变量名，
     * Claude 的 SDK 直接认 `ANTHROPIC_*` —— 没有「登记 provider」这一层，
     * 只能把 base_url 与 key 塞进这两个固定名字里。
     */
    const val CLAUDE_BASE_URL_ENV = "ANTHROPIC_BASE_URL"

    /** Claude 的 key 变量；写在这里是为了让「写了哪个」在代码里只有一处。 */
    const val CLAUDE_API_KEY_ENV = "ANTHROPIC_API_KEY"

    /**
     * Claude 走 Eta 提供商时需要注入的环境变量。
     *
     * 只返回**键名**，值由 `acpLaunchEnv` 与 codex 共用同一条安全通道（rootfs 内一次性 0600 脚本），
     * 不在这里拼进命令行 —— 那串会出现在 `ps` 里。
     *
     * 注意 `ANTHROPIC_AUTH_TOKEN` 与 `ANTHROPIC_API_KEY` 不是一回事：前者是 OAuth，
     * 这里给的是 provider 的 key，必须用 API_KEY。写错会得到一个 401，
     * 而错误信息不会告诉你用错了变量。
     */
    fun claudeEnvKeys(): List<String> = listOf(CLAUDE_API_KEY_ENV, CLAUDE_BASE_URL_ENV)

    /**
     * 生成启动前的 shell 片段：创建临时 CODEX_HOME 并写入 config.toml。
     *
     * 目录名带 sessionId，多个并发委派不会互相覆盖；/tmp 由系统回收，
     * 即使异常退出也不会把用户的 ~/.codex 搞脏。
     */
    fun launchScript(sessionId: String, provider: Config, model: String): String {
        val home = "/tmp/eta-codex-$sessionId"
        val configPath = "$home/config.toml"
        return buildString {
            append("export CODEX_HOME=").append(shellQuote(home)).append("; ")
            // 0700 / 0600：config.toml 里可能有自定义头的值（部分中转站把 key 放在头里），
            // 不能给同机其他用户留可读权限。
            append("mkdir -p ").append(shellQuote(home)).append(" && chmod 700 ").append(shellQuote(home))
            append("; ")
            append("cat > ").append(shellQuote(configPath))
            append(" <<'ETA_CODEX_CONFIG'\n")
            append(configToml(provider, model))
            append("ETA_CODEX_CONFIG\n")
            append("chmod 600 ").append(shellQuote(configPath)).append("; ")
            // 订阅登录：把登录态写成 codex 认的 auth.json。
            //
            // 写的是「变量名」而不是值：这一段会作为内层脚本的一部分进命令行，
            // 直接把 token 拼进去等于昭告 `ps`。值由一次性 0600 脚本 export，
            // 这里只做拼接与重定向。
            if (provider.subscription != null) {
                val authPath = "$home/auth.json"
                append("printf '").append(AUTH_JSON_TEMPLATE).append("' \"\$").append(ENV_ID_TOKEN)
                append("\" \"\$").append(ENV_ACCESS_TOKEN)
                append("\" \"\$").append(ENV_ACCOUNT_ID)
                append("\" \"\$(date -u +%Y-%m-%dT%H:%M:%SZ)\" > ").append(shellQuote(authPath))
                append("; chmod 600 ").append(shellQuote(authPath)).append("; ")
            }
        }
    }

    /**
     * auth.json 的 printf 格式串（四个 %s：id_token、access_token、account_id、last_refresh）。
     *
     * `refresh_token` 故意留空，理由见 [Subscription]。
     * 内嵌引号已由 [Subscription.tokensAreSafe] 在写入前校验，值里不可能出现能破出 JSON 的字符。
     */
    private const val AUTH_JSON_TEMPLATE =
        "{\"auth_mode\":\"chatgpt\",\"OPENAI_API_KEY\":null," +
            "\"tokens\":{\"id_token\":\"%s\",\"access_token\":\"%s\"," +
            "\"refresh_token\":\"\",\"account_id\":\"%s\"}," +
            "\"last_refresh\":\"%s\"}"

    /** TOML 基本字符串；JSON 的转义集与 TOML 足够接近，这里单独实现避免依赖。 */
    internal fun tomlString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    /** TOML 键：普通标识符直接输出，其余用引号包住。 */
    internal fun tomlKey(value: String): String =
        if (value.matches(Regex("^[A-Za-z0-9_-]+$"))) value else tomlString(value)

    internal fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
