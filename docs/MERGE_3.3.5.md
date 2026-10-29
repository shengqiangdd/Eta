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

## 8. 真机验证记录（2026-10-29，本机 PKX110 / Android 16）

安装包：`Eta-v3.3.5-debug-ea39eb5c.apk`，base.apk sha256 `ea39eb5c348521dbb164e6850006e215d5349344868b9ed00df582c9c1955705`，
`lastUpdateTime=2026-10-29 07:37:42`，`versionName=3.3.5-debug`；安装后无崩溃（crash buffer 无 Eta 记录）。

| 项 | 结果 | 证据 |
|---|---|---|
| 息屏后任务继续 | **通过（7.9 分钟）** | 07:50:08–07:57:44 单次连续 Dozing 476 s（`mWakefulness=Dozing`，`mLastSleepTime` 未被刷新）；后台心跳每 30 s 一条共 30 条无缺口；期间 run 连续完成 4 次工具往返（`sleep` 连发并返回）。`dumpsys power` 只有 `eta:agent-runtime` / `eta:agent-execution` 两个 PARTIAL 锁 |
| 切换会话草稿保留 | **通过** | 输入「草稿保留测试DRAFT-0742」→ 切到另一会话 → 切回，输入框原文仍在；期间该会话处于运行中，未被恢复提示清空 |
| 经验教训新格式 | **写入通过；注入待下次运行** | MEMORY.md 出现 `2026-10-29 · tool=terminal · code=EXIT_1 · hits=1 · wc: ... No such file or directory`（不再是 `ERR · 工具返回失败`） |
| 多会话运行标记 | **部分** | 会话列表对运行中的会话显示「Eta 正在思考」，空闲会话无此标记；两个会话同时运行未测 |
| 重启后不重复补发 | **未测** | 需在无活跃 run 时重启；会打断当前会话，留待收尾后做 |
| 存量 `interrupted-` 清理 | **未完成（发现真实时序缺陷）** | 两次启动后 DB 仍留 `run-54650d8e`、`run-dcc2e794`（`runtime_results.ok=1`）与对应 `interrupted-` 提示；日志 `Agent UI pending result recovery failed`，`RESPONSE_TIMEOUT_SECONDS=8`，UI 主线程繁忙时取结果超时，且只在 `ON_RESUME` 重试 |

**2026-10-29 第二轮（用户手动杀进程后重开）**

- 重启不重复补发：**部分通过**。已完成的 `run-1b1451cd`（ok=1）重启后没有新增 `interrupted-`；被用户中途杀掉的 `run-693cb81e` 恰好一条 `interrupted-`（合法中断），位置在其最后一条工具消息之后、新用户消息之前（sort_index 20894）。
- 存量清理仍未触发：两次 `ON_RESUME`（08:02:24、08:06:09）都在运行中恢复，均在 8 s 后报 `Agent UI pending result recovery failed`；`runtime_results` 累计 3 条（`run-dcc2e794`、`run-54650d8e`、`run-1b1451cd`），`interrupted-` 132 条。
- 根因（已核对代码）：`AgentRuntimeService.IncomingHandler` 与客户端 `DrainHandler` 都跑在**同一进程的主线程**（`Handler(Looper.getMainLooper())`）。本机会话有 8k+ 消息，恢复/流式时主线程连续繁忙，`MSG_DRAIN_RESULTS` 排队超过客户端 8 s 超时。属上游 IPC 架构问题，不是本次合并引入。

**关于取结果超时**：`AgentRuntimeClient.queryCompletedRuns` 的超时与 `origin/main` 一致（同样日志、同样 8 s），属于上游既有行为，不是本次合并引入；但它的后果正是「存量 `interrupted-` 一直清不掉」。待真机空闲（无活跃 run、主线程不忙）时复验；若仍失败再单独修。

## 9. 仍未真机验证的项（如实记录）

- 重启后不再重复补发、旧轨迹回原位、存量 `interrupted-` 清理；
- 多会话并发运行（两个会话同时跑）时的圆点 / 暂停态同步；
- 「经验教训」在下一轮系统提示中的实际注入（本轮提示快照仍只有旧条目）。

以上代码与单测就绪，但**行为未在真机上确认**，在验收前不得当作已完成。
## 10. 2026-10-29 第三轮：假运行态让「停止 / 插话 / 发送」全部静默失效

**用户反馈**：切回应用后仍是运行状态，停止按钮没效果，只能点插话；但运行中插话没有实际作用、不会结束，主发送按钮也没反应，最后只能杀掉 APP。

**取证与限制**：logcat 环形缓冲被进程重启冲掉（只剩 08:13 之后）；`runtime_inflight_runs` 只剩当前 run，`conversations` 表没有 `isStreaming` 列 —— 说明运行态是内存态，问题发生在**同一进程内**。因此结论来自代码路径核对，不是日志直接证据。

**三个叠加的真缺陷**：

| # | 缺陷 | 位置 | 表现 |
|---|---|---|---|
| 1 | `runIdForConversation(...) ?: return` 静默返回 | `stopCurrentRun` / `requestInterruptCurrentRun` / `steerCurrentRun` / `toggleCurrentRunPause` | 界面显示运行中、但本地没有 runId 时，所有针对 run 的操作都不做任何事、也不提示 |
| 2 | `stopRequestedRunIds` 只增不减（只有 `applyRunResult` 移除） | `AgentAppState` | 第一次停止没生效后，再点停止/插话会被永久吞掉 |
| 3 | 服务端明确回报无活跃 run 时，本地无清理 | `recoverRuntimeRuns` | `plan` 全空时直接 `return`，假运行态一直留到下次重启 |

**修复**：

- `resolveRunIdForConversation`：本地映射缺失时向服务端要一次全量快照——找到就补映射继续；明确没有就清掉假运行态；**查询失败则什么都不动**（不把「问不到」当「没在跑」）。
- 停止 / 插话 / 暂停统一走它，每种结局都有 Toast（新增 `chat_run_already_finished`、`chat_stop_unavailable`、`chat_steering_no_active_run`）。
- `stopRequestedRunIds` → `stopRequestedAt`，3 秒去抖，不再永久抑制。
- `AgentStaleRunReconciler`（纯函数，8 例单测）：用快照校准本地残留，三道保护（`currentRunId` / 待恢复 checkpoint / 快照内 run）防误清；`recoverRuntimeRuns` 的提前返回条件也纳入「存在残留」。
- `selectConversation` 遇到「streaming 但无 run」时主动对账一次。
- `steerCurrentRun` 不再提前 return：解析不到时把文字还给用户并说明原因（原来「点了没反应」）。

**验证**：`compileDebugKotlin` 通过；`ui.app` 包 298 例中 53 失败全部为 aarch64 Robolectric `DefaultNativeRuntimeLoader` 环境限制，非环境失败 0；新增 8 例全绿。**真机行为待装包后验证**。