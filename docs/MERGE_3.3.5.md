# 合并上游 3.3.5（本地分叉）

本文件记录把上游 `origin/main`（Eta 3.3.5，`6bedba9`）合并进本地分叉（原 `v3.3.1`，`5cdc1fa`）的过程、取舍与验证结果。

## 1. 合并策略

本地分叉在 3.3.1 之上带了上游没有的能力（ACP 委派、设备探针/修复、提示条、浏览器会话、steering 补充指令等），因此**不是**用上游替换本地：

1. 用 `git replace --graft` 让 git 认 3.3.1 为合并基，使 58 个冲突文件可控；
2. 冲突处默认保留本地实现（`--ours`），机械冲突（多语言资源、原生库、版本号、构建脚本）手工处理；
3. 用编译器逐个找出「上游新结构、本地缺接口」的位置补齐；
4. 用单测作为行为契约：非 Robolectric 失败逐个修，直到只剩环境性失败。

## 2. 已提取的上游改动（本轮）

### 2.1 结构与协议

- **浏览器会话按会话隔离**：`AgentBrowserSession` 由 `object` 变为 `class`，新增 `BrowserSessionRegistry`；`AppRoute.Browser` 改为带参数，路由按 `sessionKey` 取会话。
- **运行事件补齐**：`AgentEvent.SearchSuggestions` / `SubagentProgress` / `GuiControlChanged` 进入 `AgentRunMessageProjector`；搜索建议胶囊按「轮次 + 预算」挂到对应正文块（`SearchSuggestionsBudget`）。
- **运行时线上协议**：`AgentRuntimeWire` 增加 `MSG_QUERY_ACTIVE_RUNS` / `_RESPONSE`（20/21，避开本地已用的 16–19）、`RunPhase`、`ActiveRunSnapshot` 的 Bundle 编解码；`AgentRuntimeService` 上报活跃运行数。

### 2.2 模型请求

- **Codex `response.done` 终止事件**：按 `response.status` 映射为 completed / failed / incomplete；缺状态视为协议错误（原先直接忽略该事件，导致空回复被当成功）。
- **XAI Grok 正文保护**：代理按请求头路由模型，正文里的 `model` / `stream` / `messages` / `tools` 必须与配置一致，不能被 `extraBodyJson` 覆盖。
- **订阅凭据**：`OpenAiResponsesProvider` / `OpenAiChatCompletionsProvider` 接入 `AuthorizedProviderHttp`（请求前解析 token、401 刷新一次、禁止跟随重定向），并把 `authMode` / `model` 传给 `ProviderRequestHeaders.mergeInto`。

### 2.3 界面与文案

- 三语资源补齐：英文默认资源里 68 个中文值翻成英文（含 33 个 `speech_*`、35 个上游新增的 `web_tools_*` / `chat_queue_*`）；繁体补 45 个缺键；简体补 2 个。`LocaleResourceParityTest` 四项全过。
- 卡片文案恢复行范围：`edit_file` / `write_file` 在结果带行号或行数时优先用本地格式（`已编辑 · 第 12-14 行 · +3/-2`），只有字节数时回退上游精简格式。
- 补齐缺失路由：`AppRoute.WebToolsSettings`、`AppRoute.Subagents` 只有跳转没有 `entry`，点进去会空白/崩溃，现已挂上 `WebToolsSettingsScreen` / `SubagentSettingsScreen`。
- 网页工具运行时配置：`AgentLocalTools` 构造 `AgentWebTools` 时补 `runtimeConfig = { WebToolsSettingsRepository.resolveRuntimeConfig(context) }`，否则设置页保存后工具仍用内置默认值。

## 3. 未接线的上游功能（明确记录）

以下功能文件已随合并进入仓库，但**没有接到调用链**，本轮不启用（避免与本地等价实现冲突或引入未验证路径）：

| 上游功能 | 状态 | 本地现状 |
|---|---|---|
| 子代理 `SubagentExecutor` | 未接（工具未暴露给模型） | 无等价能力；`AgentSubagentToolCatalog` 仅有卡片文案映射 |
| 聊天队列 / 插话 `AgentMessageQueueReducer`、`AgentChatQueueStrip` | 未接 | 运行中发送走本地 steering（`sendCurrentMessage` → `steerCurrentRun`），可把补充指令送进当前 run |
| 历史分页 `ChatHistoryPaging`、`AgentConversationStore.loadEarlierMessages` | 未接 | 会话消息一次性整量加载（`loadMessagesWindow` 机制） |
| Markdown 缓存 `StaticMarkdownDocumentCache` / `StreamingMarkdownStateCache` | 未接 | `ChatMarkdown` 用本地缓存实现 |
| `AgentRunStore`、`ConversationSearchRepository`、`RemoteModelReconciliation` | 未接 | 功能由本地既有实现覆盖 |
| `MainActivityLaunchRequests` | 未接 | 本地 `MainActivity.updateAssistantHandoff` 已覆盖全部 action |

这些文件不参与编译错误、也不改变现有行为；后续若要启用，需要各自一轮真机验证。

## 4. 验证结果

- `:app:compileDebugKotlin`：通过，仅剩一处上游的 Elvis 警告（`AgentLocalTools.kt:1052`）。
- `:app:testDebugUnitTest`：**3050 个用例，446 失败，9 跳过**。446 个失败**全部**是 `Robolectric native runtime is not supported on Linux (aarch64)`（`DefaultNativeRuntimeLoader`），与本机架构有关；非环境性失败为 0。
- `:app:assembleDebug`：成功，`Eta-v3.3.5-debug.apk` 31,953,853 字节，sha256 `a8d34050…f54886b`。
- 真机（PKX110 / Android 16）：`pm install -r` 覆盖安装成功，`versionName=3.3.5-debug`、`versionCode=2026102801`；启动后无崩溃、无 ANR。
- **数据库迁移真实生效**：升级前 `user_version=24`，启动后 `eta.db` 的 `user_version=25`（读取大端第 60–63 字节），说明 `MIGRATION_24_25` 已在真实数据上执行。
- 构建告警：Chroot 内 NDK `llvm-strip` 是 x86_64 二进制，无法运行，3 个第三方 `.so` 未 strip 直接打包（不影响安装与运行）。

## 5. 收尾动作

- `app/schemas/.../25.json` 加入版本控制（`exportSchema=true` 的产物）。
- 4 个 splash 资源（`splash_counter_rotation.xml`、`ic_splash_animated.xml`、`splash_exit.xml`、`values/splash.xml`）随上游删除；工作区无引用。
- 去掉临时 graft（`git replace -d`），保留备份分支 `backup/pre-merge-3.3.5`。
