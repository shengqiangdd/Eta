# 合并上游 3.3.5（本地分叉）

本文件记录把上游 `origin/main`（Eta 3.3.5，`6bedba9`）合并进本地分叉（原 `v3.3.1`，`5cdc1fa`）的过程、取舍与验证结果。

## 1. 合并策略

本地分叉在 3.3.1 之上带了上游没有的能力（ACP 委派、设备探针/修复、提示条、浏览器会话、steering 补充指令等），因此**不是**用上游替换本地：

1. 用 `git replace --graft` 让 git 认 3.3.1 为合并基，使 58 个冲突文件可控；
2. 冲突处默认保留本地实现（`--ours`），机械冲突（多语言资源、原生库、版本号、构建脚本）手工处理；
3. 用编译器逐个找出「上游新结构、本地缺接口」的位置补齐；
4. 用单测作为行为契约：非 Robolectric 失败逐个修，直到只剩环境性失败。

合并完成后 graft 已删除（`git replace -d`），历史恢复为真实父子关系；备份分支 `backup/pre-merge-3.3.5` 保留。

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

## 3. 上游功能的接线状态

合并提交时有一批上游功能只进仓库、没进调用链。后续几轮已把其中三项接到真实路径，其余仍保持未接（不参与编译错误，也不改变现有行为）：

| 上游功能 | 状态 | 说明 |
|---|---|---|
| 子代理 `SubagentExecutor` | 已接线 | 开关打开时注册工具 schema，执行前再按当前权限裁决一次；`SUBAGENT_DISABLED` 是明确失败码 |
| 聊天队列 / 插话 | 已接线 | `AgentMessageQueueReducer.enqueue/promoteToInterrupt/recallToDraft/remove` + `AgentChatQueueStrip`；运行中发送仍优先走 steering，队列项可升级为插话 |
| 历史分页 `ChatHistoryPaging` | 已接线 | `AgentAppState.loadEarlierMessages` + `AgentHomeAction.LoadEarlierMessages` + 上翻加载状态 |
| `AgentRunStore` | 未接 | 本地 `runConversationIds` / `currentRunId` 已覆盖 |
| `ConversationSearchRepository` | 未接 | 本地 `ContentMatchCache` 搜索已覆盖 |
| `RemoteModelReconciliation` | 未接 | 模型列表刷新走本地 `ProviderRepository` |
| `MainActivityLaunchRequests` | 未接 | 本地 `MainActivity.updateAssistantHandoff` 覆盖全部 action |
| `StaticMarkdownDocumentCache` | 未接 | `ChatMarkdown` 用本地缓存实现 |

## 4. 验证结果（提交前实测）

- `:app:compileDebugKotlin`：通过，零告警（`AgentLocalTools` 的冗余 Elvis 已清）。
- `:app:testDebugUnitTest`：**3071 个用例，446 失败，9 跳过**。446 个失败**全部**是 `DefaultNativeRuntimeLoader.java:212` 的 `Robolectric native runtime is not supported on Linux (aarch64)`，与本机架构有关；**非环境性失败为 0**。
- 定向回归：记忆增强 26 个用例、恢复/回放/投影 46 个用例，共 **74/74 通过**。
- `:app:assembleDebug`：成功，`Eta-v3.3.5-debug.apk` **35,751,316 字节**，sha256 `f1af1145…80caf0`。
- dex 核对：`经验教训`、`recordLearning`、`draftsByConversation`、`eta:agent-runtime` 均在包内（避免「以为装了新包」）。
- 真机（PKX110 / Android 16）：`d832040b…` 覆盖安装成功，`versionName=3.3.5-debug`、`versionCode=2026102801`；启动无崩溃、无 ANR。
- **数据库迁移真实生效**：升级前 `user_version=24`，启动后 `eta.db` 的 `user_version=25`，说明 `MIGRATION_24_25` 已在真实数据上执行。
- 构建告警：Chroot 内 NDK `llvm-strip` 是 x86_64 二进制，无法运行，3 个第三方 `.so` 未 strip 直接打包（不影响安装与运行）。

## 5. 合并后的真机缺陷修复（3.3.5 分叉追加）

真机安装后暴露的问题与修复（均已有针对性单测，真机行为见 §8 的待验清单）：

| 现象 | 根因 | 修复 |
|---|---|---|
| 重启后重复补发回答、会话里多出「任务已中断」 | checkpoint 已含 `RunFinished`，却按「系统中断」重放 | `AgentRunRecoveryCoordinator.finishedBeforeResult` 区分终态；`insertRunNotice` 按 id 幂等 |
| 恢复出来的旧 run 轨迹排到最新消息之后 | 回放直接追加到末尾 | 新增 `AgentRunReplayProjection.project`：先算 anchor（跟随自己的 user/补充输入，否则原轨迹首条位置），剔除本 run 消息后重建再原位 splice |
| 已收到终态的 run 仍留着 `interrupted-` 提示 | 原先只在 live 路径清理，存量只在 applied 分支改状态 | `AgentPendingResultRecovery.apply` 的 `alreadyApplied` 分支调用 `repairAppliedRun`：删除旧 notice，正文为空/仅为终态前缀时回填，不覆盖更完整内容 |
| 息屏后任务「停住」，亮屏才继续 | root 兜底路径下没有前台服务、也没有 wake lock，息屏后 CPU suspend | `AgentRuntimeService` 自持 `PARTIAL_WAKE_LOCK`（10 分钟续期、60 分钟兜底），按 session 存活增删；`AgentExecutionService` 续期 30→10 分钟 |
| 多会话运行状态不同步 | 只有 primary 会话有事件流，其他会话看不到运行/暂停态 | `MSG_QUERY_ACTIVE_RUNS` 上报全部非终态 session；`syncActiveSessions` + `attachRunningRun` 逐个接回，`paused` 随 snapshot 同步；deferred 会话先登记再挂载 |
| 输入框被「任务已中断」清空、切换会话丢失草稿 | 流式开始时 `clearText()`；草稿从未跨会话保存 | 去掉该 `clearText`（只保留取消听写）；新增 `LocalAgentDraftSink` + 草稿权威表，`updateConversation` 统一用草稿校正 `input`，发送/导入时显式消费（避免刚发送的文字又被「恢复」） |
| 工具失败经验无法沉淀复用 | 没有失败记忆通道 | MEMORY.md 增加「经验教训」页：工具+错误码签名去重（工具只给 `exit_code`/`stderr` 时回退到 `EXIT_n` + 错误首行，避免全部记成 `ERR · 工具返回失败`）、`hits` 累加、60 秒冷却、80 条上限，按预算注入系统提示。**该页必须是顶级 `#`**，写成 `##` 会被「# 核心记忆 → 下一个 #」的自动注入吞并（挤预算且重复注入）；角色会话只读，不写 |

## 6. 收尾动作

- `app/schemas/io.github.mangi.eta.data.db.EtaDatabase/25.json` 已纳入版本控制（`exportSchema=true` 的产物）。
- 4 个 splash 资源（`splash_counter_rotation.xml`、`ic_splash_animated.xml`、`splash_exit.xml`、`values/splash.xml`）随上游删除，工作区确认无引用。
- 临时 graft 已去掉（`git replace -d`），备份分支 `backup/pre-merge-3.3.5` 保留。

## 7. 测试源集

`app/src/testPending/` 保留两个上游新增的 Robolectric 用例（`AgentRuntimeScreenOnTest`、`AgentAppStateConversationLoadingTest`）：它们依赖 PC 端 Robolectric native runtime，在 aarch64 Linux 上既不能跑也不能编进测试源集，故移出 `src/test` 留档，待换 x86 环境或改用无 native 依赖的断言后再放回。

## 8. 仍未真机验证的项（如实记录）

- 息屏 5–10 分钟后任务是否继续、亮屏后状态是否自洽；
- 切换会话草稿保留、运行中收到恢复提示不清空输入框；
- 多会话运行圆点 / 暂停态同步；
- 重启后不再重复补发、旧轨迹回原位、存量 `interrupted-` 清理；
- 「经验教训」真实写入与下一次系统提示注入。

以上代码与单测就绪，但**行为未在真机上确认**，在验收前不得当作已完成。