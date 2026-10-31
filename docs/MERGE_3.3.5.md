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
| `RuntimeRunRegistry` | 未接 | 本地 `AgentRuntimeService` 仍是 fork 的会话表（`sessions` + `runConversationIds`） |
| `GuiControlCoordinator` | 未接 | **多会话并行时 GUI 动作没有互斥**，用户「暂停 / 恢复 GUI」也不可用；overlay 已能处理 `GuiControlChanged`，缺的是 service / executor / tools 三端接线 |
| `StreamingMarkdownStateCache` | 未接 | 本地 `ChatMarkdown` 自带缓存实现 |

> **后续更新（2026-10-29，见 §14\/§15）**：`GuiControlCoordinator` 已经接到
> service \/ executor \/ tools 三端并真机核对过（多会话抢屏幕会排队）；上表那一行是合并当时的状态。
> 仍未做的是上游「用户手动暂停 \/ 恢复 GUI」的**界面入口**（代码里没有任何对应入口）。

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

## 11. 2026-10-29 第四轮：同一会话真的并发两个 run（真机证据 + 修复）

**用户反馈**：「当前会话又没结束，要我手动结束」。

**真机证据（09:39 取自设备 DB + logcat）**：同一会话里同时挂着两条活跃 run：

| run | 创建 | 当时状态 |
|---|---|---|
| `run-63a56d6e…` | 09:29:44 | 带 `promptSupplement`（「继续，当前会话又没结束，只能插话」），跑到 round 31 |
| `run-1ea96fa2…` | 09:30:01 | 无 supplement，round 22 |

两条都在调模型、跑工具，都没有终态事件 → 界面一直有「运行中」，只能手动点停止。
09:38 `run-63a56d6e` 正常结束（`runtime_results` ok=1），`runtime_inflight_runs` 从 2 条降到 1 条：
**「终态即删 checkpoint」在真机生效**（ee701d9 的验收点）。

**根因**：服务端 `startRun` 的既定策略是「多会话并发，不取消已有 run」。同一会话并发时，两边往同一份
transcript 写消息、抢浮层、互相覆盖运行态。触发条件是客户端运行态与服务端脱节——客户端用本地状态判断
「是否运行中」，一旦判成没在跑，用户的下一条消息就以「新消息」而不是「插话」发出去，服务端顺势起了
第二个 run。

**修复（`08bbfd5`）**：

- `AgentRunExclusivityPolicy`（纯函数）：新 run 收掉同会话更早的活跃 run；不同会话的并发保持原样；
  归属未知（压缩 / 后台归档）不参与互斥。挂在 `startRun` 入口，是服务端兜底，不依赖客户端状态。
- `MSG_ACK_RESULT` 一并删 checkpoint；启动后 30 秒内重试对账（只在拿到明确活跃快照时才清理）。
- 单测 `AgentRunExclusivityPolicyTest` 6/6。

**装包后真机复核（09:46，包 md5 `62cffe0f567cf4602e5938b47f1c3953`）**：

- `runtime_inflight_runs` **1 条**（= 当前 run），无堆积；会话里也没有多出「任务已中断」提示。
- 虚拟屏 `virtual_display_create` 不传尺寸 → **1216×2640 @560dpi**，与主屏完全一致
  （`preset=tablet` 早前已验证 1600×2560）。

**未验证（如实记录）**：「两条同会话 run 真正相遇」的取消路径需要用户在运行中再发一条消息才会触发。
代码已编译进包（dex 里核验到 `AgentRunExclusivityPolicy` 与 `same-conversation run superseded`），
但**尚无真机日志证明它真的取消过一次旧 run**。

**release 构建（10-29 09:52）**：`assembleRelease` 成功，产物 `Eta-v3.3.5.apk` **7,157,030 字节**
（md5 `d8798bef755ae82d9a4a532b1c87ba89`，R8 + 资源压缩；debug 版 34,366,921）。dex 里核到
`same-conversation run superseded` 字面量（类名被 R8 改写属正常）。**尚未在真机上启动过**：
混淆后能否正常启动、Compose / 协程相关反射有没有被剪掉，都要安装后实测。


## 12. 2026-10-29 第五轮：补上「旧 run 终态抹掉新 run 运行态」+ 装包复核

**补上的缺陷（`7869a14`）**：互斥取消旧 run 后，客户端会立刻续跑打断时排队的消息，同一会话里就出现
「旧 run 正在收尾、新 run 已经开始」。旧 run 的终态事件（`run_finished` / 取消）走
`setConversationStreaming(runId, false)`，它只看 `runId -> conversation`，不看这个会话**还有没有别的
run**——于是新 run 的运行态被抹成「未运行」，用户下一条消息又以「新消息」而不是「插话」发出去，
等于把互斥刚修好的问题从另一条路径放回来。

修法：`setConversationStreaming` 在 `isStreaming = false` 时，若 `runConversationIds` 里还有**别的**
run 属于同一会话就提前返回。暂停态不需要同样处理（它只跟着活着的 run）。

**装机与复核**：

| 时间 | 事件 |
|---|---|
| 10:27:46 | 用户实装 `versionCode=2026102901` / `3.3.5-debug`（含互斥 + 上面的保护），进程 pid 22049 |
| 10:32 | `runtime_inflight_runs` **1 条**（当前 run），无幽灵 run、无堆积 |

**release 未装的原因（如实记录）**：先前构建的 release 是 `versionCode=2026102802`，低于已装的
2026102901，`pm install` 会被判降级拒绝。已按新版本号重建 `2026102902`，等这一轮验证收尾后再装。

**仍未真机验证**：「两条同会话 run 真正相遇」的取消路径、插话、切走切回、双会话并发、
停止只作用于目标会话。这些都需要界面里制造场景，代码与单测就绪但行为未经真机确认。

**release 已按新版本号重建（10:35）**：`Eta-v3.3.5.apk`，`versionCode=2026102902`，
md5 `99f10ab16953066eefd0c875ab979556`，7,157,042 字节；已放到
`/sdcard/Download/eta-3.3.5-release.apk`，可覆盖当前的 2026102901 安装。仍待真机启动验证。

**真机项：切走切回（2026102901 debug，10:40）——通过**。按 HOME 回桌面、等 5 秒、再拉起 Eta 后：
会话仍显示「正在分析任务」，后续工具事件（等待·5 秒、打开应用·Eta）继续正常渲染，历史消息没有被释放；
同时刻 `runtime_inflight_runs` 仍只有 1 条（当前 run）。说明 `ON_RESUME` 的恢复流程没有把真在跑的
run 误判成中断，也没把 `isStreaming` 清掉。

**装 release 前的回退保险**：另构了一个 `versionCode=2026102903` 的 debug 包作回退，
已放到 `/sdcard/Download/eta-fallback-debug-2026102903.apk`（32,028,385 字节，md5 `77eea9cb1c34347e671ecaded72498bf`）。
万一 2026102902 的 release 启不来，直接装这个即可覆盖（它的 versionCode 更高，不需要卸载丢数据）。

## 13. 2026-10-29 第六轮：release（R8）真机启动验证 —— 通过

**安装**：10:59:48 装入 `versionCode=2026102902` / `versionName=3.3.5`（md5 `99f10ab16953066eefd0c875ab979556`，
7,157,042 字节）。`pm install` 覆盖成功，包名仍 `io.github.mangi.eta`，canary 包不受影响。

**启动（R8 之后）**：

- 进程 pid 19001 于 11:00 起来，11:18 复查仍存活（约 18 分钟），
  `topResumedActivity=io.github.mangi.eta/.ui.MainActivity`；
- `logcat -d -t 800` 过滤 `FATAL|AndroidRuntime|ClassNotFound|NoSuchMethod|NoClassDefFound` **零命中**；
- 无障碍树完整：工具卡（「终端 · 执行命令 · Linux」）、思考卡（「思考已完成 · 用时 3 秒」）、
  长期目标卡、任务计划卡（5/9）、输入框、附件 / 思考强度 / 语音按钮全部就位 ——
  Compose 界面在 R8 构建下正常渲染，没有空白页或崩在启动。

**历史**：`PRAGMA user_version=25`；21 个会话 / 79,266 条消息；当前会话 9,887 条，末尾正是
`user`「继续」→ `assistant` 上一轮回复。没有丢历史，也没有迁移报错。

**inflight**：`runtime_inflight_runs` **1 条** —— `run-404bc403…`，`handoff_source=agent_ui`，
`handoff_payload=conv-1a25f9f1…`（正是当前会话），`operation=chat`。无幽灵 run、无堆积。

**互斥代码确实进了 release 包**（R8 会改类名，故改用字符串取证）：两个 dex 中
`same-conversation run superseded` 出现 1 次；类名 `AgentRunExclusivityPolicy`、方法名 `supersededRuns`
均为 0（已被混淆）。说明这条路径的打点没有被裁剪，运行时可达。

**仍未验证（如实记录）**：logcat 里没有出现这条 supersede 日志（缓冲区已滚动，且这一轮没有两个 run 相遇），
所以「新 run 收掉旧 run」的**真实取消路径依然只有单测覆盖**。插话、双会话并发、
停止只作用于目标会话同理 —— 它们都需要在界面里制造「正在跑」的场景，不能靠读代码代替。

**2026-10-29 复核（第 13 节之后）**：把「fork 点之后上游新增的 74 个主源码文件」逐个查了引用数，
只有 5 个类在本地「只被自己文件引用」，其余 69 个都有调用点 —— `RuntimeRunRegistry`、
`GuiControlCoordinator`、`AgentRunStore`、`ConversationSearchRepository`、`StreamingMarkdownStateCache`。
前两者正是上游 `f527108 feat(runtime): 支持多会话并行与 GUI 阶段互斥` 的实现，而本地
`AgentRuntimeService` / `AgentRuntimeRunExecutor` / `AgentLocalTools` 里 `guiControl` 出现 **0 次**
（上游分别是 15+ / 5 / 5），`pauseForUser` / `resumeForUser` 也只有 coordinator 自己引用。
即：这项上游新功能在本地**只到协议与 overlay 层，没有进运行时** —— 多会话同时跑时，两个 run 仍会同时操作屏幕。

补它的成本不低：`AgentLocalTools` 的 execute 主入口要换成上游那种带 generation 的 `executeLocal`
（防止过期动作落到新界面），executor 要 openRun/close，service 要持有实例并广播 `GuiControlChanged`，
再加用户暂停/恢复入口与测试。属于一轮独立工程，本轮只记录、未动代码。

## 14. 2026-10-29 第七轮：把 GUI 阶段互斥真正接进运行时

上一节点出的缺口（`GuiControlCoordinator` 只在协议/overlay 层，运行时三处 `guiControl` 出现 0 次）这一轮接上了。

**改了三个文件（+88 / −6）**：

| 文件 | 改动 |
|---|---|
| `AgentLocalTools` | 新增 `guiControl: GuiControlCoordinator.RunControl?` 参数；`execute` 拆成「拿锁 → `executeLocal(toolCall, generation)`」；GUI 动作进入时校验观察代号，不一致就丢掉旧快照并对需要最新观察的工具返回 `NO_OBSERVATION`；`observe_screen` 把代号写进快照；`afterAction` 的刷新保留代号；`close()` 交还锁 |
| `AgentRuntimeRunExecutor` | 构造参数加 `guiControl`；在工具创建前 `openRun(runId, runController)`，状态变化以 `AgentEvent.GuiControlChanged` 进事件流；`finally` 里先关工具再 `guiRun.close()` |
| `AgentRuntimeService` | 持有 `GuiControlCoordinator()`，创建 executor 时传入；`pauseRun` / `resumeRun` 优先走 `pauseForUser` / `resumeForUser`（未持有锁的纯文本 run 仍直接 pause/resume） |

**顺手修掉一个真缺陷**：`release_gui` 在工具目录和 prompt 里都有，但 `AgentLocalTools` 的 `when` 里**没有分支** ——
模型调用它会落到 `else -> UNKNOWN_TOOL`，也就是「告知模型有这么个动作，但它其实不存在」。现在补上了：
清空快照 + `guiControl.release()` + 返回 ok。

**为什么 generation 必须一起接**：`withAction` 只保证串行；用户暂停/恢复会推进代号，若快照不带代号，
恢复后模型仍拿暂停前看到的节点去点现在的界面（点在完全不同的地方）。带代号后，旧快照自动作废，
需要最新观察的工具会收到 `NO_OBSERVATION`，被迫重新 `observe_screen`。

**编译与单测**：

- `./gradlew :app:compileDebugKotlin` **BUILD SUCCESSFUL**，零告警。
- 定向单测（`GuiControlCoordinatorTest` / `AgentRunExclusivityPolicyTest` / `AgentRunControllerTest` /
  `AgentRuntimeParallelWireTest`）共 **28 个用例，3 个失败**；3 个失败全部是 `DefaultNativeRuntimeLoader.java:212`
  的 Robolectric native runtime（Linux aarch64 不支持），与 §4 记录的 446 个环境性失败同源 ——
  **非环境性失败为 0**，`GuiControlCoordinatorTest` 全过。

**仍未做**：

- `RuntimeRunRegistry` 依旧是死代码：本地的 `sessions` + `runConversationIds` 已覆盖它的职责
  （run 生命周期 + 同会话互斥），接它是纯重构，收益低风险高。
- 子代理（`SubagentExecutor`）不共享这把锁 —— 上游同样如此，属已知边界。
- 真机多会话并发 + 暂停/恢复**尚未验证**（需要装新包）。

## 15. 2026-10-29 第八轮：GUI 互斥的真机核对，以及一个「等待中看不见」的缺口

**装了什么**：`Eta-v3.3.5-debug.apk`（md5 `7a16911040fd8dbff8329597a172ef11`，32,029,689 B，
`versionCode=2026102904` / `versionName=3.3.5-debug`），设备确认 `lastUpdateTime=2026-10-29 11:36:10`，
主进程存活、无 FATAL。

**单会话真机核对（通过）**：我自己就是这套运行时的用户——本轮的所有 GUI 工具调用（`observe_screen` 多次）
都走新的 `withAction` 路径。结果：调用正常返回、随后的再次观察也正常、没有 `NO_OBSERVATION` 误报、
没有挂死。说明「取锁 → 执行 → 放锁」的循环在真机上成立（若 `close()`/`release()` 漏放，第二次调用就会永久阻塞）。

**锁是同一个实例（静态核对）**：`AgentRuntimeRunExecutor(` 全仓只有 **1 处构造**（`AgentRuntimeService.kt:655`），
且固定传 `guiControl = guiControl`（service 字段）。所以所有 run 天然共享一把锁，不存在「每个 run 各一把」的假互斥。

**新发现的真缺口：`Waiting` 完全不可见。** 证据链：

| 位置 | 事实 |
|---|---|
| `RuntimeRunRegistry` | 全仓**从未实例化**（grep 只命中自己的定义行） |
| `RunPhase.WaitingForGui` | 只由上面那个死类产生 → 永远不会出现 |
| `AgentRunStore.updateGuiStatus` | **0 个调用点**（`guiStatus` 字段写了但没人填） |
| `GuiControlCoordinator.state` | **0 个收集者** |
| `res:run_waiting_for_gui` | 三个语言都有「等待手机控制权」，Kotlin 里 **0 处引用** |

后果：第二个会话被第一个挡住时，界面上没有任何提示 —— 用户看到的仍然是「正在思考…」，
和本次会话最开始抱怨的「看起来卡住了」是同一类体验问题。锁本身是对的，是**可见性**缺一块。

**为什么没用数据库取证**：`runtime_inflight_events` 有 350 条事件，`gui_control_changed` **0 条** ——
不是没发，而是 `AgentEventRecoveryProjection` 把它列入了「恢复时忽略」的事件（设计如此）。
所以真机上要证明跨会话排队，只能靠**第二个并发 run** 或**专门打点**，读库不行。

**未验证（如实记录）**：

- 跨会话并发排队（run B 等 run A）**未在真机制造**：需要在同一个进程里再起一个会调用 GUI 工具的 run，
  而唯一入口是在正在跑本轮会话的界面里新建会话并发送消息 —— 万一误触到「停止」会直接杀掉当前轮次，
  代价高于收益，本轮不做。
- 用户暂停 / 恢复**在持锁状态下**的分支（`pauseForUser` 返回 true 那条）只有单测覆盖；
  未持锁的纯文本 run 仍走原来的 `controller.pause()`，行为未变。

**结论**：互斥的接线正确、单会话无回归；跨会话排队与「等待可见性」两项待做，后者建议下一轮优先。

## 16. 2026-10-29 第九轮：让「排队等手机控制权」看得见

上一节查出的缺口（`Waiting` 在界面上完全没有出口）这一轮补上了。

**改动（5 个文件，+37 / −6）**：

| 文件 | 改动 |
|---|---|
| `AgentChatUiState` | 新增 `isGuiWaiting`（默认 false） |
| `AgentAppState` | `applyRunEvent` 加 `AgentEvent.GuiControlChanged` 分支 → `updateGuiWaiting()`；run 结束的三处复位都带上它；`clearStaleStreaming` 的提前返回条件加上它（自愈，防止 run 结束后残留「等待中」） |
| `AgentWorkProcess` | 新增 `LocalGuiControlWaiting`（`staticCompositionLocalOf`）；标题在排队时显示 `run_waiting_for_gui`「等待手机控制权」，图标换成灯泡，微光持续 |
| `AgentHomeScreen` | 在**已有的** `CompositionLocalProvider` 里多 provide 一行 |
| `app/build.gradle.kts` | `etaVersionCode` 2026102904 → 2026102905 |

**为什么用 CompositionLocal 而不是加参数**：`AgentChatBody → AgentChatScaffold → 时间线 → AgentWorkProcess`
是四层调用链，为一个只影响标题文案的布尔值改动四个签名，收益不足以抵消回归面。
CompositionLocal 只多一行 provide，其余调用方（如悬浮胶囊）默认 false，行为不变。

**为什么复位必须显式做**：`isGuiWaiting` 与 `isStreaming` 生命周期不同 —— 它可能在 run 已结束时
还是 true（Waiting 事件到达后、终态事件到达前）。只清 `isStreaming` 会让标题永远写着
「等待手机控制权」。所以三处终态复位都带上它，并让 `clearStaleStreaming` 把它也纳入判定条件。

**编译**：`:app:assembleDebug` **BUILD SUCCESSFUL**；`:app:compileDebugUnitTestKotlin` 一并通过。
注意 `AgentAppState` 编译时会报 2 条 **既有** 警告（`Condition is always 'false'`，行 1343 / 2271，
属于 `shouldRelease*` 的智能转换提示），与本轮改动无关（本轮未触碰这两处）。

**产物**：`Eta-v3.3.5-debug.apk`，32,843,646 B，md5 `0b34bd60dd0ba8a111153bff6ed91efc`，
`versionCode=2026102905` / `versionName=3.3.5-debug`。

**未验证**：文案要真的出现，得有两个会话同时抢屏幕。本轮的验证止步于「编译通过 + 单测编译通过」，
**没有在真机看到「等待手机控制权」** —— 这一条必须靠装包后制造并发才能算数。
另外悬浮胶囊（`AgentOverlayState`）对 `Waiting` 仍是忽略，只有会话内标题变了。

### 16.1 追加：产物与打包完整性核对

- debug：`Eta-v3.3.5-debug.apk`，32,843,646 B，md5 `0b34bd60dd0ba8a111153bff6ed91efc`，
  `versionCode=2026102905` / `versionName=3.3.5-debug` →
  `/sdcard/Download/Eta-v3.3.5-debug-2026102905.apk`
- release：`Eta-v3.3.5.apk`，7,158,010 B，md5 `fbb1a7f8772125471b98536673c7fd5f`，
  `versionCode=2026102906` / `versionName=3.3.5`，签名块存在 →
  `/sdcard/Download/Eta-v3.3.5-release-2026102906.apk`

**打包完整性核对**（因为新 debug 包 32.8MB 比更早的 35.7MB 小了 2.9MB，先怀疑少了东西）：

| 对比项 | 旧 debug（d3fa2792 系列） | 新 debug |
|---|---|---|
| zip 条目数 | 364 | 364 |
| 只在旧 / 只在新 | 0 / 0 | — |
| 未压缩合计 | 91,651,486 | 91,710,066（+58,580，即本轮 dex 增量） |
| `res/`、`assets/`、`lib/`、`kotlin/`、`org/` | — | 逐项差 **0** |
| APK Sig Block 大小 | 4,088 | 4,088 |

结论：**内容没有变少**，差异只在 `classes*.dex`（+58KB）与 `resources.arsc`（+688B）。
容器（文件）体积那 ~2.9MB 的差额当时**没有追到根因**，只留下「逐条目对齐 / 打包填充差异」的猜测。

> **2026-10-29 补记（真根因见 §18.2）**：方向猜对了，但形态不是「填充」。
> 真形态是包内存在**不被中央目录引用的孤儿本地条目**（`PK\x03\x04` 头还在、文件名字段被清零，
> 后面跟着一整份数据）。同一手法在 `2026102905` 上复现：多出 813,166 字节，
> 里面是 `classes8.dex` 的一份完整重复副本。新构建的包本地条目与中央目录**严格一一对应**。

## 17. 2026-10-29 第十轮：真机看到「等待手机控制权」了

装的是 `2026102905`（`lastUpdateTime=2026-10-29 12:02:11`，`versionName=3.3.5-debug`）。

**怎么造出并发（不碰当前会话）**：`ACTION_SEND` intent 直接把一句测试指令投进**新会话草稿**，
再点一次「发送」；第二个 run 就在另一个会话里跑起来了。全程没有在当前会话里点任何按钮
（那里的「停止」误触会直接杀掉正在跑的轮次）。

**取证不抢锁**：`uiautomator dump` 是 shell 侧，不经过 agent 的 GUI 锁，所以可以一边看界面一边做实验。
抓到的那一帧（`/data/local/tmp/eta/evidence-waiting.xml`）里同时有：

```
text="只调用一次工具：wait_for_text，参数 text=ZZZ_NOT_EXIST，timeout_ms=60000……"
text="等待手机控制权"          ← 新加的标题
text="详情" / text="思考已完成" / text="等待文本出现" / text="4 秒"
```

**第二轮证据（锁能正确交还）**：我 `release_gui` 之后，那个被挡住的 run **立刻继续并跑完**
（后来界面是「思考 2 次 · 调用 1 个工具」+「OK」）。排队的 run 不会永远挂住。

### 17.1 这次实验挖出一条必须写下来的行为（不是 bug，是上游设计）

`GuiControlCoordinator.RunControl.withAction` 结束时，只有 `releaseRequested || closed` 才放掉 owner ——
**锁是按 run 粘住的**：谁先碰了屏幕，谁一直持有，直到这个 run 结束、或模型主动调 `release_gui`。
我对比了 `origin/main` 的同名文件，`withAction` 一字不差，属于上游行为，不是移植错误。

后果：两个会话并行时，**第二个会话等的是第一个 run 整条跑完**，不是「每个动作轮流」。
这次就是亲眼看到我的 run 把另一个会话挡在那里（那时我的 run 其实只是在等），
所以「等待手机控制权」这条文案是**必需**的 —— 没有它，用户只会看到第二个会话莫名其妙不动。

### 17.2 一次假阴性，值得记

第一次实验用 `wait(duration_ms=25000)` 当持有者，结果对方的 `observe_screen` 一秒就跑完了。
原因：`wait` 确实是 GUI 工具（`requiresGuiControl` 为真），但我的工具派发有秒级延迟，
等它拿到锁时对方早就跑完了。**这类并发实验必须让一方长时间持有**（如 `wait_for_text` 超时 60s），
短窗口只会得到「看起来互斥没生效」的假结论。

### 17.3 残留

实验造了 **2 个测试会话**（内容就是那两句测试指令），**没有删**：删会话要在侧栏长按，
一旦点偏可能删掉真实会话，不值这个风险。要清理由用户决定。

## 18. 2026-10-29 第十一轮：Waiting 接进悬浮胶囊 + 查清包体积差额的真根因

### 18.1 胶囊里的等待文案

`AgentOverlayState.projectEvent` 原先对 `GuiControlStatus.Waiting` 直接 `-> this`，
于是排队等屏幕时胶囊仍写「思考中」—— 又一处界面说假话。改为：

```kotlin
GuiControlStatus.Waiting -> copy(status = AgentOverlayStatus.WaitingForGui)
```

`phase` 保持 `RUNNING`：run 并没有暂停，只是没拿到屏幕（暂停态另有 `UserPaused`）。
文案复用 `R.string.run_waiting_for_gui`，与会话内标题同源，不再另造一套字。

### 18.2 §16 那笔 ~2.9MB 体积差额，根因查到了

用**本地条目逐条走 + 与中央目录对账**定位（下面 18.2.1 有可复用脚本思路）：

| 包 | 文件体积 | 本地条目 | 中央目录 | 空名孤儿 | 孤儿字节 |
|---|---|---|---|---|---|
| `2026102905`（旧 debug） | 32,843,646 | **377** | 364 | **13** | **813,166** |
| `2026102907`（本轮中间产物，已作废） | 32,030,877 | 364 | 364 | 0 | 0 |
| `2026102909`（本批 debug） | 32,031,277 | 364 | 364 | 0 | 0 |
| `2026102910`（本批 release） | 7,161,966 | 307 | 307 | 0 | 0 |

孤儿的形态：`PK\x03\x04` 本地头仍在，但**文件名长度字段被清零**，后面跟着一整份数据。
`2026102905` 里那 813,008 字节就是 `classes8.dex` 的**完整重复副本** —— 同一个 CRC
`c3eca0a0`、同样 813,015 压缩字节，一份在数据区中部（孤儿位）、一份在尾部（中央目录真正指向的那份）。

**结论修正：旧包里是多存了一份，不是新包缺东西。** 新包本地条目数 == 中央目录数，
walk_end == 签名块起点，没有孤儿。

### 18.2.1 方法论：别只信 `namelist()`

`zipfile.namelist()` 读的是**中央目录**，孤儿根本不在里面 —— 这就是上次「条目全对得上、体积却差」的原因。
可靠做法：从 offset 0 起按 `PK\x03\x04` 逐条走**本地头**（用本地头的 `fnl`/`exl` 算步长，
别用中央目录的 extra 长度，两者在对齐时不同），走到「签名块起点」为止；
然后把「走出来的条目数/名字」与「中央目录」对账，再看有没有空名条目、有没有 `central-only`。
我第一版用中央目录的 extra 长度估算步长，结果误报出 82 个「间隙」和一条 813,008 的假洞 —— 教训同上。

### 18.3 这轮真挖到的问题：状态栏 chip 会写「思考中」（已修）

`AgentExecutionService.shortText()` 是「列举 + `else -> overlay_reasoning`」的写法，
新加的 `WaitingForGui` 落进 `else`，于是 Android 16 状态栏 chip 在排队时显示「思考中」。
补分支 + 三语短文案 `overlay_waiting_gui`（`Waiting` / `等待控制权` / `等待控制權`）：
没用 `run_waiting_for_gui` 是因为它的英文是 `Waiting for phone control`（24 字符），
而 chip 约 7 字符，会被截断成看不懂的样子。

### 18.4 已核对但**不改**的地方（并加测试钉住语义）

`thinkingUnlessWorking()` 的 `else` 会把 `WaitingForGui` 打回 `Reasoning`。
第一反应是「这又是 bug」，细看调用点后判定**这是对的**：它挂在 `RoundStarted`
等「引擎在推进」的事件上 —— 引擎已经开新一轮，就说明它并非在等锁。
已加单测 `waitingForGuiClearsWhenEngineMovesOn` 把这个语义钉住，将来谁改这里会红。

### 18.5 胶囊可见性有个边界，必须说清楚（未改，需产品决策）

`AgentOverlayVisibilityPolicy.shouldRevealFor` 只在 `GuiControlChanged(Acquired)`
（以及交互型工具结束）时唤出浮窗，**`Waiting` 不会主动弹胶囊**；再加上 `primaryRunId`
决定「只有最新那个 run 更新浮层」，所以胶囊上能看到等待文案的成立条件是：
**本 run 是 primary，且浮窗因先前的 Acquired 已经在显示**。

这符合既有的「浮窗跟随实际手机操作权、不在没操作屏幕时打扰」取向，不是本轮缺陷；
但因此**胶囊这条路覆盖面比会话内标题窄**。若要做到「等待时就弹胶囊」，得改
`shouldRevealFor` —— 那会变成「在别的应用上面弹一个我在等」，属产品决策，留待确认。

**所以本轮状态：胶囊的状态机与文案已正确，但「Waiting 时主动露出胶囊」尚未实现。**

### 18.6 产物（2026-10-29，本批可安装）

| | debug | release |
|---|---|---|
| versionCode | 2026102909 | 2026102910 |
| versionName | 3.3.5-debug | 3.3.5 |
| 字节 | 32,031,277 | 7,161,966 |
| md5 | `c6374dcf849885c6cf80067abb9057a3` | `2948ddf61526d38916f5debb2937630a` |
| zip 条目 | 364 | 307 |
| APK Sig Block | 4,096 | 12,288 |
| 孤儿条目 | 0 | 0 |

- `/sdcard/Download/Eta-v3.3.5-debug-2026102909.apk`
- `/sdcard/Download/Eta-v3.3.5-release-2026102910.apk`
- 单测：`AgentOverlayStateTest` **5/5 通过**（新增 1 个用例）；`assembleDebug` + `assembleRelease` BUILD SUCCESSFUL。

### 18.7 未验证

- 「Waiting 时主动弹出胶囊」按 §18.5 未实现（本轮真机验到的是**会话内工作过程行**与状态栏 chip 的取值，见 §18.8）。
- 两个测试会话与 `/sdcard/Download` 的历史包仍按 §17.3 留着，等用户定。

### 18.8 真机验证：会话内与状态栏都不再说「思考中」（2026-10-29 12:37–12:40，本机 PKX110/A16）

装机 `2026102909`（`base.apk` md5 `c6374dcf849885c6cf80067abb9057a3`，与
`/sdcard/Download/Eta-v3.3.5-debug-2026102909.apk` 一致）后实测。**上一版 §18.7 那条
「没装机验证」到此关闭。**

**造场景**：新建会话发一条「只调用一次 `wait_for_text`、timeout_ms=60000」的指令；
它派发工具时要抢手机控制权，而当前会话的 run 正持有同一把锁 → 新 run 排队。
不需要抢时间：`RunControl.withAction` 拿到锁后并不主动释放（锁按 run 粘住，§17.1），
「等待」窗口会一直开着，直到持有方 `release_gui` 或该 run 结束。

**同一时刻三处一致**：

| 出处 | 取到的值 |
|---|---|
| 通知 1107 `android.text` | `等待手机控制权` |
| 通知 1107 `android.shortCriticalText`（状态栏 chip 的取值来源） | `等待控制权` |
| `uiautomator dump` 会话内工作过程行（bounds `[305,1182][630,1238]`） | `等待手机控制权`，同屏工具行 `等待文本出现 30 秒` |

同一份 dumpsys 里还有 `android.requestPromotedOngoing=true`、
`android.template=ProgressStyle` —— 确实走了 Live Update 提升路径，不是普通通知。

**反向验证（确认不是卡死）**：`release_gui` 让出锁后，`android.text` 由「等待手机控制权」变为
`继续执行`、`shortCriticalText` 由「等待控制权」变为 `思考中`；被挡的 run 随即执行
`wait_for_text`（60 秒 TIMEOUT，本就要求它超时）并回复「OK …」。库内 `conversation_messages`
落盘：`tool | wait_for_text | Failed`、`assistant | OK …`。

**留档**（宿主 `/data/local/tmp/eta`，Linux 侧即 `/workspace`）：
`evidence-waiting-ui.xml`、`evidence-waiting-notif.txt`、`evidence-waiting-chip.png`。

**边界，别读过头**：
- 界面侧证据来自**会话内工作过程行**；`dumpsys window` 显示 Eta 当时只有 `MainActivity` 一个窗口
  （自由窗口形态），**没有浮层胶囊窗口** —— 与 §18.5 一致，Waiting 不主动唤出胶囊。
- 状态栏 chip 能确定的是**取值**（`shortCriticalText`）；SystemUI 把它画成什么样、
  当时有没有画出来，本轮没有单独取证（截图里状态栏区域没有可辨识的 chip）。

## 19. 2026-10-29 第十二轮：三条「只是读代码推断」的语义，真机都验了

§11（同会话并发修复）、§14（GUI 阶段互斥）、§16~§18（排队可见）之后，还有三件事一直停留在
「读代码推断」，没有真机证据：**跨会话能不能真并发**、**运行中插话到底是什么行为**、
**停止按钮的作用域有多大**。本轮把三条都验完，结论都是通过，但**验证手法本身值得写下来**。

### 19.1 造场景的方法：让「本会话自己的 run」当另一个会话

不需要专门造负载：**当前会话正在跑的这个 run 本身就是一条真实长跑 run**。另开一个测试会话发一条
「会去抢手机控制权」的指令，它与本会话的 run 天然构成「两条 run 并存」，而且因为
`RunControl.withAction` 的锁按 run 粘住（§17.1），抢不到锁的那条会**一直停在「等待手机控制权」**——
这既复用了 §18.8 的场景，又给出一个**稳定开着、不靠抢时间**的窗口，供后面点「停止」和「插话」。

本轮两个会话：

| | 会话 | run | 状态 |
|---|---|---|---|
| A（本会话） | `conv-1a25f9f1-0e16-483a-9618-82da1552e90b`（10560 条消息） | `run-fe3a24ed-ef40-4cef-9a61-a7edeb7cbef9`（12:50:34 起） | 一直活跃，逐轮推进 |
| B（测试会话） | `conv-2a3380a2-38fd-4be6-acd2-f215f3871afb` | `run-1f30e9b0-15f6-4659-b1de-9232d8f47a80`（12:56:35 起） | 排队等锁，`等待手机控制权` |

一手证据不是日志，而是库表 `runtime_inflight_runs`（此前没注意过它）：

```
CREATE TABLE runtime_inflight_runs (transcript_json, run_id, owner_instance_id,
  context_snapshot_json, operation, rewrite_target_message_id, handoff_id,
  handoff_source, handoff_payload, dismiss_entry_surface, created_at, updated_at,
  PRIMARY KEY(run_id));
```

`handoff_payload` 存的就是**该 run 属于哪个会话**（`conv-…`）。这一列是排查「现在到底谁在跑、
跑在哪个会话」最快的一手证据 —— `logcat` 的 `agent_round_model` 行里**不带会话**，只看日志分不清。

### 19.2 ①跨会话并发：成立（真机）

两条 run 同时出现在 `runtime_inflight_runs` 里（同一份快照，12:59）：

| run_id | 会话 | created_at |
|---|---|---|
| `run-fe3a24ed…` | `conv-1a25f9f1…`（A） | 12:50:34 |
| `run-1f30e9b0…` | `conv-2a3380a2…`（B） | 12:56:35 |

并且 B 被停止、其排队消息以 `run-407a…` 重新派发的同一秒，A 仍在出自己的轮次：

```
13:00:44.317 agent_round_model run=run-407a8368… round=1
13:00:44.511 agent_round_model run=run-fe3a24ed… round=52
```

两个 run 在同一秒内各自推进 —— 不是「一个跑完再到另一个」的串行假象。
与 `AgentRunExclusivityPolicy.supersededRuns` 的语义一致：**只清同一会话里更早的 run，跨会话不互相顶替**。

### 19.3 ②运行中插话：打断目标 run，再把输入作为**新 run** 紧随其后发出（真机）

B 排队到 **223 秒**（此时它连 `timeout_ms=60000` 都还没开始计时，因为卡在抢锁阶段），
在输入框写「插话：别等它超时了，立刻中断，只回复一行 INTERRUPT_OK。」，
点的是按钮 `desc="插话发送（打断当前运行）"`（不是普通「发送」）。

`logcat`（13:00:43–44，同一秒内走完）：

```
13:00:43.962  Agent runtime stopped
13:00:44.004  Agent runtime stop skipped: active run still executing
13:00:44.009  Agent run returned: id=run-1f30 ok=false err=已停止
13:00:44.144  Agent run prepare done: id=run-407a
13:00:44.187  Agent run dispatch: id=run-407a foreground=true rootFallback=true
13:00:44.214  stop watch: run 已退出活跃列表，收尾 runId=run-1f30e9b0… 第1次查询
13:00:49.079  Agent run returned: id=run-407a ok=true err=null
```

库内 B 会话落盘（`conversation_messages`，按 `sort_index` 倒序）：

| sort | type | 内容 |
|---|---|---|
| 12 | assistant | `INTERRUPT_OK` |
| 11 | thinking | `The wait_for_text was already interrupted (TOOL_INTERRUPTED)` |
| 10 | user | `插话：别等它超时了，立刻中断，只回复一行 INTERRUPT_OK。` |
| 9 | system_notice | `stopped` |
| 8 | tool | （空 —— 被中断的 `wait_for_text` 行） |

**三点必须说准**：

1. **不是自然超时**。它当时已排队 223 秒、`timeout_ms` 的计时还没起跑，返回的是 `err=已停止`，
   不是 `TIMEOUT`；模型侧也自述 `TOOL_INTERRUPTED`。
2. **插话内容没有被塞进旧 run**，也不是 supplement/steering —— 它作为 `system_notice=stopped`
   之后的**新 run**（`run-407a`）单独执行。这跟「普通发送按钮」走 `sendSupplement` 的路径是两条路。
3. **排队消息没丢**：旧 run 收尾后立刻 prepare + dispatch，5 秒内返回 `ok=true`。

### 19.4 ③停止只作用于目标会话（真机）

B 被停的那一刻，A 的 run **没有被打断**，反而继续出轮次，并完成了本轮后续的全部工具调用：

```
13:00:44.511  run=run-fe3a24ed… round=52
13:00:51.973  run=run-fe3a24ed… round=53
```

同一份 `runtime_inflight_runs` 快照里，B 那行消失、A 那行仍在（`updated_at` 继续推进）。
另有 `Agent runtime stop skipped: active run still executing` 一行 —— 应用层「停服务」在有别的 run
跑着时被**主动跳过**，不是一刀切。

**所以「停止」按的是「当前显示的那个会话」的 run**：这次能安全验，正是因为界面显示的是 B；
如果当时显示的是 A，同一个按钮点的就是本会话自己的 run（见 §19.5 风险）。

### 19.5 边界与风险（别读过头）

- 本轮验的是**跨会话**并存与作用域；**同一会话内「新 run 顶替旧 run」那条取消路径仍然只有代码与单测**（§11/§14），
  没有捕到对应 logcat。想验它，必须在一个会话里连续发两次「抢锁类」指令，风险是会误伤自己这一侧的 run。
- 「插话」这次打断的是一条**卡在抢锁阶段**的 run；**正在跑长工具（如 20 秒 terminal）中途被插话**未单独取证。
- **风险记一笔**：`停止`/`插话发送` 都是「按当前显示会话」生效。界面停在哪个会话，按钮就作用于谁 ——
  排查现场时若不小心，会把正在干活的会话（包括 agent 自己的 run）停掉。
- 本轮无代码改动，故无新产物、无新单测；装机版本仍是 §18.6 的 `2026102909`（debug）/`2026102910`（release）。

## 20. 2026-10-29 收尾：main 合入、正式包、以及清理

### 20.1 main 已合入

`git checkout main && git merge --ff-only trial-merge` —— **快进合并**，无冲突、无额外合并提交，
`main` 直接指到 `7260db5`。备份分支 `backup/pre-merge-3.3.5`（合并前 3.2.x 基线）保留。
合并前 `origin/main...HEAD` 是 `0 / 76`，即上游一条都没落下。

### 20.2 正式包（R8）

- `:app:assembleRelease` **BUILD SUCCESSFUL**（51 个 task，3 个执行、其余 up-to-date —— 源码没变，
  产物可复现）。
- `aapt2 dump badging` 读出：`package=io.github.mangi.eta`、`versionCode=2026102910`、
  `versionName=3.3.5`、`minSdk=33`、`targetSdk=37`。
- 文件 `\/sdcard\/Download\/Eta-v3.3.5-release-2026102910.apk`，
  **7,161,966 字节**，md5 `ae684f4c9a59f89f057cee30b444d0dc`。
- **与上一份同名包的差别只有内嵌 VCS 元信息**：229 个非 `META-INF` 条目的 CRC 全部一致
  （逐条比对），说明代码相同，只是 `META-INF\/version-control-info.textproto` 里的提交号变了 ——
  这就是 md5 从 `2948ddf6…` 变成 `ae684f4c…` 的全部原因。
- **签名必须说清**：本机没有 `ETA_RELEASE_STORE_FILE` 等环境变量，`hasReleaseSigning=false`，
  于是 release 构建按上游代码里的回退**用 debug 密钥签名**（`app\/build.gradle.kts` 的注释写明这是为
  本地真机验 R8 包）。所以这个包能覆盖当前的 debug 安装，但**不是发行密钥签的**；
  要出对外分发的包，得在带 `ETA_RELEASE_*` 的环境里重打。
- 装机验证见 §20.4（13:32 装上并跑起来，`pm path` 指向的 base.apk 与产物逐字节一致）。

### 20.3 清理

- `\/sdcard\/Download` 删掉 6 个已作废的 Eta 3.3.5 包，约 169MB：
  `Eta-v3.3.5-d3fa2792.apk`、`Eta-v3.3.5-debug-ea39eb5c.apk`、`Eta-v3.3.5-debug-f1af1145.apk`、
  `Eta-v3.3.5-debug-2026102905.apk`、`Eta-v3.3.5-release-2026102906.apk`、
  `eta-fallback-debug-2026102903.apk`（版号最低，已无法覆盖任何现存包，作为回退保险已失效）。
- 保留：`Eta-v3.3.5-debug-2026102909.apk`（当前装机版本）、`Eta-v3.3.5-release-2026102910.apk`（新正式包）、
  `eta-3.3.5-release.apk`（上游原件）、3.3.1 的两个包；`fix139.apk`、`opifexa-release.apk` 属别的项目，未动。
- 临时 DB 快照 33 个、共 8.1G 已在 §19 删除（`\/data\/local\/tmp\/eta` 33.3G → 25.1G，`.tooling` 与
  `dsh-work` 未动）。
- **测试会话已删（界面长按，逐条确认）**：`conv-878b102a…`（只调用一次工具：wait_for_text，参数）、
  `conv-79aeaf0b…`（只调用一次 observe_screen）、`conv-35171371…`（请只做一件事：调用 terminal）。
  动手前原本要删的 `conv-2a3380a2…`、`conv-9a0ab8fb…` **在库里已经不存在**（会话表 / 消息表 / 归档 run 全无），
  不是本轮删的。删后复核：20 个会话 / 80,450 条消息 / **孤儿消息 0** / `user_version=25`。

### 20.4 装机验证（2026-10-29 13:32）—— 通过

装上了 `2026102910`（release / R8）并实测：

| 项 | 值 |
|---|---|
| versionCode / versionName | `2026102910` / `3.3.5`（不再是 `-debug`） |
| lastUpdateTime | 2026-10-29 13:32:10 |
| `pm path` 指向的 base.apk | md5 `ae684f4c9a59f89f057cee30b444d0dc`，7,161,966 字节 —— 与 §20.2 的产物**逐字节一致** |
| 运行 | 进程存活；这一轮验证会话就是跑在这个 R8 包里完成的（工具链、DB、界面都正常） |
| 崩溃 / ANR | crash buffer 无 Eta 记录；`FATAL EXCEPTION` / `ANR in io.github.mangi.eta` 零命中 |
| 数据 | 20 个会话 / 80,450 条消息 / `user_version=25`，`runtime_inflight_runs` 1 条（本会话） |

**先前那次为什么没装上（如实）**：我排的后台脚本确实在 13:27:20 跑了，`pm install-create` 也成功
（session `1796699246`），但紧接着 `write` / `commit` 都返回
`Failure calling service package: Failed transaction (2147483646)`（`write_exit=2`、`commit_exit=2`），
安装中断，当时版本仍是 `2026102909`；13:32:10 那次成功安装**不是这个脚本做的**（它的日志 13:27:31 就结束了）。

机制上最可能的解释：root 会话挂在**被安装的那个进程**底下，`pm install` 在写入/提交阶段会先杀掉包进程，
连带把发起会话的 binder 事务一起撕掉 —— 即「自己装自己」这条路本身不稳，走外部 adb / 界面安装才可靠。
**这条没有做对照实验，属推断**，不要当结论用。

### 20.5 release 包上复验三条语义（2026-10-29 14:06–14:11）—— 通过

§19 验的是 debug 包（`2026102909`）。R8 会改类名、裁剪反射面，**同一份源码换了包也得重验**。
本轮在 `2026102910`（release）上把三条语义各走一遍，手法沿用 §19.1：本会话的 run 当 A 臂，
新建一个测试会话当 B 臂。

| 臂 | 会话 | run |
|---|---|---|
| A（本会话） | `conv-1a25f9f1…` | `run-748d89e9…`（14:06 起，逐轮推进） |
| B（测试会话，验完已删） | `conv-256138fe-3cc1-4221-8f28-6e342bcf49cc` | `run-e36002e0…` → `run-5d6482cb…` → `run-ac3ab6e4…` |

**① 跨会话并发：成立。** 两条 run 在同一秒各自出轮次：

```
14:08:10.681 Agent run dispatch: id=run-e360
14:08:10.745 agent_round_model run=run-e36002e0…  round=1
14:08:11.040 agent_round_model run=run-748d89e9…  round=17   ← A 同时在跑
14:09:03.421 agent_round_model run=run-ac3ab6e4…  round=1
14:09:03.766 agent_round_model run=run-748d89e9…  round=25
```

**② 插话：打断旧 run，输入作为新 run 紧随其后 —— 这次打在「正在跑长工具」的时刻。**
§19.5 记过一个缺口：上次插话打断的是「卡在抢锁」的 run，**长工具执行中被插话**没单独取证。
这轮 B 的指令是「只跑 `sleep 45; echo B_SLEEP_DONE`」，插话点在终端已跑 18 秒时：

```
14:08:33.482 Agent runtime stopped
14:08:33.489 Agent runtime stop skipped: active run still executing
14:08:33.495 Agent run returned: id=run-e360 ok=false err=已停止
14:08:33.555 Agent run prepare done: id=run-5d64
14:08:33.573 Agent run dispatch: id=run-5d64
14:08:33.745 agent_round_model run=run-748d89e9…  round=21   ← A 照旧
14:08:37.492 Agent run returned: id=run-5d64 ok=true err=null
```

库里 B 会话按 `sort_index` 落盘（`conversation_messages`）：

| sort | type | 内容 / 状态 |
|---|---|---|
| 0 | user | 任务指令（只跑 `sleep 45`） |
| 1 | thinking | 模型决定调用终端 |
| 2 | tool | `sleep 45; echo B_SLEEP_DONE` —— `tool_status=Failed`、`失败 · 退出码 137` |
| 3 | system_notice | `stopped` |
| 4 | user | `插话：立刻中断当前执行，只回复一行 INTERRUPT_OK。` |
| 5 | assistant | `INTERRUPT_OK` |

即：**被中断的工具确实被杀掉（137 = SIGKILL）并以失败落盘**；插话内容不是塞进旧 run，
而是 `stopped` 之后的新 run。界面同步显示「已停止 / 调用 1 个工具 · 1 个失败 / INTERRUPT_OK」。

**③ 停止只作用于当前会话。** 第二次给 B 发「只跑 `sleep 60`」，然后在 B 的界面点「停止」：

```
14:09:03.421 agent_round_model run=run-ac3ab6e4…  round=1
14:09:11.383 agent_round_model run=run-748d89e9…  round=26
14:09:17.947 Agent run returned: id=run-ac3a ok=false err=已停止
14:09:18.230 agent_round_model run=run-748d89e9…  round=27   ← A 未被误杀
```

同一时刻 `runtime_inflight_runs` 只剩 A 一行（`handoff_payload=conv-1a25f9f1…`）：
「停止」按的是**界面显示的那个会话**的 run，不是一刀切停掉所有 run。

**收尾**：B 会话（`conv-256138fe…`）已按界面「长按 → 删除 → 确认」删除；删后复核
20 个会话 / B 的消息 0 条 / 孤儿消息 0 / `user_version=25`；界面已切回本会话。
本轮**无代码改动**，装机包仍是 §20.4 的 `2026102910`。

**边界（别读过头）**：这里验的都是**跨会话**；同一会话内「新 run 顶替旧 run」那条取消路径
仍只有代码与单测（同 §19.5）。另外 `停止` / `插话` 都以「当前显示会话」为目标 ——
界面停在哪个会话，按钮就作用于谁。

## 21. 2026-10-29 第十四轮：补验「经验教训」注入，以及一条查清后发现「界面根本走不到」的路径

### 21.1 §9 第 3 项（经验教训注入）—— 通过（release 包）

证据是**这次运行自己的系统提示**（跑在 `2026102910` 上）：

- `MEMORY.md`（`/data/data/io.github.mangi.eta/files/memory/MEMORY.md`，452,729 字节）里
  `# 经验教训` 在第 **4436** 行，共 **49 条**；
- 本轮提示里确实有 `<memory_learnings>` 块，含 `terminal · EXIT_1 · ×26`、
  `tap_element · STALE_CONTENT · ×9` 这类条目，末尾还带
  `[还有 5 条未展示，需要时调用 memory_get(section="经验教训")]` —— 与
  `AgentPromptBuilder.kt:283` 的写法逐字一致。

两条一起看才能排除「只是把文件读进来」：条目是**按预算裁剪过**的（49 条只展示 44 条 + 隐藏计数），
说明走的是注入逻辑，不是原样拼接。

### 21.2 定向单测（`agent.runtime` + `ui.app`）

```
./gradlew :app:testDebugUnitTest --tests "io.github.mangi.eta.agent.runtime.*" \
  --tests "io.github.mangi.eta.ui.app.*" --offline
538 tests completed, 126 failed        （BUILD FAILED）
```

把 126 条失败按 message 聚合，只有一条：`The Robolectric native runtime is not supported on
Linux (aarch64)`。即 **412 通过、非环境性失败 0**，与 §4 的口径一致。

### 21.3 「同一会话新 run 顶替旧 run」：界面走不到，故无法真机取证

`AgentRunExclusivityPolicy.supersededRuns` 只在「新 run 与另一条**活跃且同会话**的 run」并存时取消旧的，
执行点在 `AgentRuntimeService.startRun`。查了三条界面路径，都被客户端挡住了：

| 想造并发的动作 | 挡在哪 |
|---|---|
| 运行中「编辑消息并重发」 | `beginMessageEdit` 开头 `if (homeState.isStreaming \|\| messageEdit != null) return` —— 运行中根本不进编辑 |
| 运行中「发送」 | 走队列 / steering（`AgentMessageQueueReducer`），不会立即下发第二条 run |
| 运行中「插话」 | 先停旧 run（`err=已停止`）再发新 run，旧 run 已是终态 → 被 `terminal` 集合排除 |

也就是说：**只有「客户端以为没在跑、服务端还在跑」的运行态失配**才走得到取消路径（§11 里真实发生过），
而失配的成因是进程被回收 / 停止没收到终态。本轮没有找到**可控、又不连带杀掉 runtime** 的注入点：
`am force-stop`、`pm clear`、覆盖安装都会把 runtime 一起带走，反而验不到。

**结论**：这条路径仍只有代码 + `AgentRunExclusivityPolicyTest` 的 6 个用例
（不同会话不动 / 终态不动 / 空会话不误杀 / 不含自己），**没有真机证据**，按惯例记未验证。
要真验，最省事的做法是加一个 **debug-only 注入入口**（喂一条「客户端已空闲」的假状态），
那需要改代码 + 重装；本轮没做（装包会打断当前会话）。

### 21.4 仍未验证（承接 §9）

- 重启后「不重复补发 / 旧轨迹回原位 / 存量 `interrupted-` 清理」—— 需要一次真机重启，会打断当前会话；
- 多会话并发时的**圆点 / 暂停态**同步 —— §20.5 只验了 run 层（两条 run 各自推进），没盯界面状态点。

### 21.5 产物与签名（口径不变）

- 装机包仍是 `2026102910`（release / R8，**debug 密钥签名**）；本轮**无代码改动**，未重打包、未重装。
- 要出可对外分发的包，得在带 `ETA_RELEASE_STORE_FILE` / `ETA_RELEASE_STORE_PASSWORD` /
  `ETA_RELEASE_KEY_ALIAS` / `ETA_RELEASE_KEY_PASSWORD` 的环境里打；或授权生成一套本机 keystore
  （那会成为今后所有包的签名身份，丢了就再也覆盖不了 —— **需要你点头**）。

## 22. 2026-10-29 第十五轮：加 debug-only 注入入口，真机验证「同会话 supersede」

§21.3 的结论是这条取消路径**界面走不到**，只能靠「客户端以为没在跑、服务端还在跑」的失配触发。
本轮把它补上了：加一个**只在 debug 构建存在**的注入入口，在真机上把失配造出来，跑通取消路径。

### 22.1 入口长什么样（release 里不存在）

| 文件 | 源集 | 作用 |
|---|---|---|
| `ui/app/AgentDebugInjections.kt` | main | 一个 `@Volatile var forgetLocalRunState: ((String?) -> Unit)?` 的空壳；release 里永远是 null |
| `ui/app/AgentAppState.kt` | main | `init` 里 `if (BuildConfig.DEBUG)` 注册实现；新增 `debugForgetLocalRunState()`，只清**本地** `runConversationIds` / `currentRunId` / `stopRequestedAt` 并调 `clearStaleStreaming()`，**不停服务端 run** |
| `debug/.../DebugRunStateReceiver.kt` | debug | 广播接收器，action `io.github.mangi.eta.DEBUG_FORGET_RUN_STATE`，extra `conversation_id` |
| `debug/AndroidManifest.xml` | debug | 只声明那个 receiver，`exported=true`；release 不合并该清单 |

关键点：`BuildConfig.DEBUG` 在 release 是编译期常量 `false`，注册分支会被 R8 裁掉；
receiver 类在 `src/debug`，release 源集根本没有这个类。

### 22.2 真机怎么造出「活跃的旧 run」

用测试包 `io.github.mangi.eta.canary`（与主包同签，互不干扰）：

- APK：`app/build/outputs/apk/debug/Eta-v3.3.5-debug.apk`，32,033,081 字节，
  md5 `112ac71bc2fccb026856f0b4ec954fe8`，`versionCode=2026102909` / `3.3.5-debug`；
- 假服务商：`model_providers` 插一行 `debug-hang-provider`（`openai_compatible`，
  `base_url=http://127.0.0.1:18080/v1`，`api_key=sk-debug`），配一个模型 `debug-hang`；
- 挂起端点：`/workspace/mock-llm-hang.py`（Linux chroot 与 Android 共用 loopback），
  收到请求就回 `200 + text/event-stream` 响应头，**永远不结束响应体**，只每 10 秒发一行 SSE 注释。
  → run 长期停在「等首包」，既不烧 token，也不需要真实 key，时长可控；
- 专用会话 `conv-debug-supersede-0001`，`model_id=debug-hang`，并设为当前会话。

**为什么不用真实服务商**：真实模型的响应时长不可控（可能 2 秒就结束），
而这条路径需要「旧 run 一定还活着」；挂起端点把窗口从「碰运气」变成「想多大有多大」
（本轮两次实测窗口分别约 17 秒、29 秒，都来自手工操作的耗时）。

### 22.3 两轮复现（第二轮留了持久日志）

底部输入条的状态是第一个证据：注入前是「停止」，广播后变回「发送」——说明**本地**运行态确实丢了，
而服务端那条 run 还在（挂起端点日志里连接没断）。

第二轮（C/D）的完整链条，取自持久化的 logcat 抓取（`/data/local/tmp/eta/evidence/supersede-canary.txt`）：

```
14:41:19.572 I Eta: Agent run dispatch: id=run-254a foreground=true rootFallback=true
14:41:19.720 I Eta: agent_round_model run=run-254ad226-… round=1 model=debug-hang provider=debug-hang-provider
14:41:19.759 D Eta: Agent runtime event: provider_response_started round=1, http_code=200
14:41:25.988 W Eta: Agent debug injection: forgot local run state conv=conv-debug-su dropped=run-254a leftRunning=true
14:41:54.744 W Eta: Agent runtime same-conversation run superseded: old=run-254a new=run-d947
14:41:54.746 I Eta: Agent run dispatch: id=run-d947 foreground=true rootFallback=true
14:41:54.865 I Eta: Agent run returned: id=run-254a ok=false err=已停止
14:42:09.022 I Eta: Agent run returned: id=run-d947 ok=false err=已停止      ← 这条是我们手动按的停止
```

第一轮（A/B）同样成立，注入与取消相隔 17 秒：

```
14:38:40.743 W Eta: Agent debug injection: forgot local run state conv=conv-debug-su dropped=run-ff98 leftRunning=true
14:38:57.470 W Eta: Agent runtime same-conversation run superseded: old=run-ff98 new=run-5e81
14:38:57.501 I Eta: Agent run returned: id=run-ff98 ok=false err=已停止
14:38:57.543 I Eta: agent_round_model run=run-5e81… round=1 model=debug-hang provider=debug-hang-provider
14:38:57.577 D Eta: Agent runtime event: provider_response_started round=1, http_code=200
```

两轮一致：**旧 run 被取消并回 `ok=false err=已停止`，新 run 正常下发、正常打到服务商**。

### 22.4 顺带看清的一件事：被顶掉的 run 不会「回来污染界面」

查会话落的账（测试库副本，只读）：

```
conversations.applied_runtime_run_ids_json = ["run-5e81e1ec-9b47-4234-a7a3-374e70aaf0e6"]
runtime_results / runtime_archive_runs 里都没有 run-ff98 的终态
```

也就是：因为本地映射已经被清掉，旧 run 的终态结果**在客户端被丢弃**，
不会在会话里冒出一条「已停止」气泡，也不会覆盖新一轮的流式内容。
这解释了 §11 里那种失配发生时「用户看不到旧任务收尾」的观感 —— 它是设计使然，不是丢消息。

### 22.5 环境里踩到的两个坑（下次直接照做）

1. **Android 16 的 `/data/data` 在 root shell 里是 tmpfs**（只有几个系统目录），
   看不到 `io.github.mangi.eta.canary`；真实目录要走 `/proc/1/root/data/data/<pkg>/…`，
   或者在目标进程活着时走 `/proc/<pid>/root/…`。用 `/data/data` 判断「目录不存在」会得到假结论。
2. **canary 的无障碍服务被系统绑定后，`am force-stop` 会立刻拉起来**，
   而且应用自身会把服务重新写回 `enabled_accessibility_services`（临时改设置会被还原）。
   所以「停掉它再改数据库」这条路不可靠；能用应用自己的界面改状态就别动数据库文件。

### 22.6 重装后再验一遍，并确认 release 包里真的没有它（2026-10-29 14:46–14:50）

**为什么再验**：§22.3 那次是「装完就验」，中间没经过一次重装；而这条路径的入口是 debug 源集 + `BuildConfig.DEBUG`，
最怕的正是「本地构建里有、装到机器上的包里没有」。所以按「构建 → 装 → 验」重新走了一遍。

1. 重装：`pm install -r -d …/Eta-v3.3.5-debug.apk` → `Success`，`lastUpdateTime` 由 14:31:24 变为 14:46:13，
   包名与版本不变（`2026102909` / `3.3.5-debug`）；APK md5 与设备侧副本一致（`112ac71b…`）。

2. 复验（同一条链路，新的 run id；时间取 logcat 的本地时区）：

```
14:48:23.875  I Eta: Agent run dispatch: id=run-8330 foreground=true rootFallback=true
14:48:24.025  I Eta: agent_round_model run=run-833066… round=1 model=debug-hang provider=debug-hang-provider
14:48:24.071  D Eta: Agent runtime event: provider_response_started round=1, http_code=200   ← 挂起
14:48:47.137  W Eta: Agent debug injection: forgot local run state … dropped=run-8330 leftRunning=true
14:49:02.827  W Eta: Agent runtime same-conversation run superseded: old=run-8330 new=run-359b
14:49:02.829  W Eta: Agent runtime stopped
14:49:02.829  I Eta: Agent run dispatch: id=run-359b foreground=true rootFallback=true
14:49:02.850  I Eta: Agent run returned: id=run-8330 ok=false err=已停止
14:49:02.931  D Eta: Agent runtime event: provider_response_started round=1, http_code=200
```

界面侧同样成立：「暂停」「停止」两个动作在注入前存在、注入后消失（回「发送」），
再发一条后轮次 5/5 → 6/6。证据落盘 `/data/local/tmp/eta/evidence/supersede-canary-v2.txt`。

3. 落账（`canary-after2` 副本，只读）：

```
conversations.applied_runtime_run_ids_json =
  ["run-5e81e1ec-…","run-d947ccd3-…","run-359bd472-…"]     ← 新 run 在；被顶掉的 run-8330 不在
runtime_results 全表 0 行                                  ← 含被顶掉的 run-8330，也含手动停掉的 run-359b
runtime_inflight_runs 对这两个 run 0 行
```

比 §22.4 多看清一点：**手动停止的 run 同样不落 `runtime_results`**。
会话里那条「已停止」是消息内容，不是 run 终态记录 —— 排查时别把 `runtime_results` 当「所有 run 的账本」。

4. release 包对照取证（`Eta-v3.3.5.apk`，7,161,982 字节；环境无 aapt，用 python3 读 zip + dex 字节串 + 二进制清单解 utf-16）：

| 检查项 | debug 包 | release 包 |
|---|---|---|
| dex 文件数 | 21 | 2 |
| dex 内出现 `DebugRunStateReceiver` | `classes7.dex` 命中 | **无** |
| dex 内出现 `DEBUG_FORGET_RUN_STATE` | 命中 | **无** |
| 二进制清单含 receiver 名 / action | 是 | **否** |

结论：注入确实只在 debug 构建里；release 包里连字符串都不存在，
不是「R8 改了名但仍可达」这种半清理状态。

### 22.7 收尾：本轮真机现场全部复位

- 停掉挂起端点；canary 的假服务商 / 假模型 / 测试会话随包一起消失；
- `pm uninstall io.github.mangi.eta.canary` → `Success`；`/proc/1/root/data/data/io.github.mangi.eta.canary` 已不存在；
- 系统自动把 `enabled_accessibility_services` 收回为只剩主包
  （`io.github.mangi.eta/.agent.accessibility.AgentAccessibilityService`），`accessibility_enabled=1`；
- 前台回到 `io.github.mangi.eta/.ui.MainActivity`；
- 删掉历史测试快照 `canary-db*` / `canary-clean` / `canary-after2` / `canarydb` 与 13 个一次性 APK，
  共约 2.1 GB（`/data/local/tmp` 可用：199.3 GB → 201.4 GB）。

**顺带一个坑**：`am start -W` 在冷启动 canary 时卡到 90 秒超时（无任何输出）；
换不带 `-W` 的 `am start` 立刻返回。判断是否真在前台，用
`dumpsys activity activities | grep topResumedActivity` 更实在。

## 23. 2026-10-29 第十六轮：两个会话同时跑，真机验证「运行标记」与「停止只打自己那个」

先说清楚**这轮不是从头验**：§20.5 已经在正式包上验过「跨会话并发成立」和「停止只作用于当前会话」
（手边是 A 臂本会话 + B 臂测试会话）。本轮补的是三件界面侧、以前没人看的事：

1. **列表上的运行标记**（有没有、跟不跟状态走）；
2. **切会话后底部按钮是不是各是各的**（不是一个全局 `isRunning`）；
3. **双向停止**（先停 B 再停 A）+ 把「为什么两次日志不一样」（`stop skipped` 只出现一次）查清。

另外顺带把 `runtime_results` 长期 0 行的原因从「不是账本」补到代码级（§23.6）。

### 23.1 装置：两个会话、一条挂起端点

沿用 §22 的挂起端点（`/workspace/mock-llm-hang.py`，回 `200` 后永不结束响应体），
新建两个会话 `conv-conc-a`（并发-A）/ `conv-conc-b`（并发-B），都指向假模型 `debug-hang`，
先发 A（15:00:22），46 秒后再发 B（15:01:16）—— 两条 run 同时活着。

`runtime_inflight_runs` 同时出现两行（与 §20.5 的结论一致，这里只是复核一遍现场）：

```
run-e0b8c4aa-76d8-4899-9ede-cd805e73701c | chat | 2026-10-29 15:00:22
run-585b578e-fc71-4534-9756-f094a98e72be | chat | 2026-10-29 15:01:16
```

服务端日志同样两条 `provider_response_started … http_code=200`（都在等首包，谁也没被谁挤掉）：

```
15:00:22.406 I Eta: Agent run dispatch: id=run-e0b8 foreground=true rootFallback=true
15:00:22.610 D Eta: Agent runtime event: provider_response_started round=1, http_code=200
15:01:16.709 I Eta: Agent run dispatch: id=run-585b foreground=true rootFallback=true
15:01:16.802 D Eta: Agent runtime event: provider_response_started round=1, http_code=200
```

挂起端点那边也确实收到了两次 `POST /v1/chat/completions → 200`（各自连接都还开着），
说明这不是「排队等前一个跑完」，而是真的并行。

### 23.2 会话列表的运行标记：有，而且跟着状态走

**注意：这个圆点不在无障碍树里**（列表项只有「标题 / 时间 / 预览」三个文本节点），
只能靠截图判断。`§22` 之后我一度以为没有标记，就是因为只读了节点树。

实测三段（同一块虚拟屏、同一份 DB）：

| 时点 | 并发-A | 并发-B |
|---|---|---|
| 两条 run 都在跑 | 时间「现在」+ 预览 `run-A 挂起` + **蓝点** | 「现在」+ `run-B 挂起` + **蓝点** |
| 停掉 B 之后 | 「现在」+ `run-A 挂起` + **蓝点** | 「15:04」+ `已停止`，**无圆点** |
| 再停掉 A 之后 | 「15:05」+ `已停止`，**无圆点** | 「现在」+ `run-B2 挂起` + **蓝点** |

两个方向都对照过，不是「选中项才有点」这种巧合（选中的那条在停下后也照样没点）。

### 23.3 切会话后的底部状态：各是各的

- 打开 A：预览是 `run-A 挂起`，底部是「暂停 / 停止」；
- 切到 B：预览是 `run-B 挂起`，底部仍是「暂停 / 停止」（B 自己也在跑）；
- 在 B 点「停止」：B 气泡出现 `已停止`、底部立刻回「发送」；此时再切回 A，A **仍然是**「暂停 / 停止」。

也就是底部按钮跟着**当前会话的交卷状态**走，不是全局一个 `isRunning`。

### 23.4 停止作用域：只停当前会话那条（双向都验了）

**B 先停**（A 还在跑）—— DB 前后对照：`runtime_inflight_runs` 由两行变一行，剩下的是 A 的 `run-e0b8`；
B 的会话里多出一条 `system_notice`，内容就是 `stopped`（界面上显示成 `已停止` 徽标）。

**A 再停**（B 换了一条新 run `run-2b9c` 在跑）—— 结果对称：`inflight` 只剩 `run-2b9cebe7-…`，
A 里多一条 `system_notice/stopped`，B 的 run 完好。

两次的日志（`--pid` 只抓 canary，见 §23.6）：

```
15:04:02.442 I Eta: Agent runtime stopped
15:04:02.448 W Eta: Agent runtime stop skipped: active run still executing
15:04:02.454 I Eta: Agent run returned: id=run-585b ok=false err=已停止
15:04:02.684 I Eta: stop watch: run 已退出活跃列表，收尾 runId=run-585b578e-… 第1次查询

15:05:47.872 I Eta: Agent runtime stopped
15:05:47.882 I Eta: Agent run returned: id=run-e0b8 ok=false err=已停止
15:05:48.108 I Eta: stop watch: run 已退出活跃列表，收尾 runId=run-e0b8c4aa-… 第1次查询
```

**两段日志不一样，一开始我以为是缺陷**：第一次有 `stop skipped` 警告，第二次没有。
查代码才明白，两处日志根本不是一件事：

1. `Agent runtime stopped` 来自 `AgentRuntimeRunExecutor.kt:516`，是**每条 run 被取消时**自己在 catch 里打的
   （`cancelled = true` → 消息文案「已停止」），所以两次各有一条，与作用域无关。
2. `stop skipped` 来自 `dismissAndStop()`（`AgentRuntimeService.kt:1853-1862`）：
   「收摊停服务」前检查 `sessions.isNotEmpty()`，还有别的 run 在跑就只收浮层、不 `stopSelf()`。
   它只在**结束的那条 run 就是 primary**时才会走到 —— `postTerminalOverlay` 里
   `showOverlay = primaryRunId == session.runId || primaryRunId == null`（`:855`），
   非 primary 的 run 走 `notifyBackgroundRunFinished` 直接返回。
   第一次结束时 `primaryRunId` 是 `run-585b`（后启动的那条），所以守卫触发；第二次结束时 primary 已换成
   `run-2b9c`，`run-e0b8` 不是 primary，根本没进 `dismissAndStop()`。
   → **两条日志都能用现有代码逐行解释，不是作用域漏了。**
   （§20.5 那次「A 在跑时插话打断 B」也出现过同一行 `14:08:33.489 W … stop skipped`，同一机制。）

### 23.5 停止到底怎么走到「只停一条」：代码位置

| 入口 | 消息/函数 | 停谁 |
|---|---|---|
| 聊天页底部「停止」、列表里「停止运行」 | `MSG_CANCEL` 带 `runId` → `cancelRun(runId)`（`AgentRuntimeService.kt:365-367`） | **指定的那一条** |
| 悬浮胶囊上的停止 | `requestStop()`（`:1171-1193`）：只 `cancelRun(primary)`；只有「没有 primary（浮层已收起）」时才回退成全部停掉 | primary |

会话列表里「停止运行」只对正在跑的会话出现（`ConversationPanePanel.kt:595`），
与「停止作用域按会话」是同一套语义，不是巧合。

另外确认了一点：**非 primary 的 run 收尾时，前台不弹任何东西**。
`notifyBackgroundRunFinished()`（`:750`）第一行就是 `if (AppVisibility.foreground.value) return`，
所以第二个会话跑完只落在自己的会话里，不会在你看 A 的时候弹一张 B 的结果卡。

### 23.6 落账：`runtime_results` 为什么一直是 0 行

本轮两次「停止」之后，`runtime_results` 仍然是 **0 行**（DB 采样四个时点都是 0，
§22 里两次「被顶替」之后也是 0 —— 两次是不同实例的库，结论一致）。
不是没落账，而是这张表**不是账本**：`AgentRuntimeResultStore` 的注释写得很清楚
「终态先落盘，入口成功提交后 ACK」，入口 ACK 后服务端 `AgentRuntimeResultStore.remove(...)`
（`AgentRuntimeService.kt:408`）把行删掉。所以长期为 0 是**正常**的，排查时别拿它当「所有 run 的账」。

真正看得见的是会话里的 `conversation_messages`：

```
conv-conc-a | 0 | user          | run-A 挂起
conv-conc-a | 1 | system_notice | stopped
conv-conc-b | 0 | user          | run-B 挂起
conv-conc-b | 1 | system_notice | stopped
conv-conc-b | 2 | user          | run-B2 挂起
```

「已停止」是**消息**（`system_notice`），不是 run 终态记录；`runtime_inflight_runs` 则是精确的：
停谁就少谁，另一条一动不动。

### 23.7 环境坑（本轮新增，都是花时间换来的）

1. **主包自己的 run 会把 `tag=Eta` 的日志淹掉。** 我用的是主包跑这个会话，它在不停地打
   `agent_round_model / chunktext / Agent terminal`，几分钟就把 canary 的日志顶出缓冲区
   （我去查时 `logcat -d --pid <canary>` 只剩 39 行，之前那些 `dispatch` 全被轮转掉了）。
   → 要留证据，**开局就 `logcat --pid <canary> -s Eta` 挂一个 `terminal_job` 收着**，别事后补。
2. **`ps -A -o args` 会把运行器自己的命令行打出来**，那串里带着注入的环境变量（含 token）。
   我这一下把凭据第三次打到了屏幕上。→ 看进程只查 `pidof` / `ps -A -o pid,args | grep <包名>`
   这类**限定输出**的写法，或者干脆别碰 `args`。
3. **canary 的无障碍服务会自愈，而且系统每 30 秒重绑一次。** 光 `settings put secure
   enabled_accessibility_services <只留主包>` 会被还原（进程还在 → 应用把自己写回去）。
   可靠顺序是 `pm disable-user`（或 `am force-stop`）→ 改设置 → 再 `pm enable`；
   最干净的还是**卸载测试包**，系统会把设置自动收回成只剩主包（本轮收尾就是这么做的）。
4. **没有干净备份就不要「整库还原」。** 上一轮记的 `canary-clean` 备份这次根本不存在
   （`/data/local/tmp/eta/canary-clean` 报 No such file），四个 `canary-conc*` 快照只有 `eta.db`
   没带 `-wal`，直接盖回去等于丢掉最近的全部写入。
   → 改成**精确 DELETE**：按 run / 会话 / provider 的 id 删，删完复核计数（见 §23.8）。
5. 设备上没有 `sqlite3`、`/data/data` 在 root shell 里是 tmpfs（§22.5 的老坑）：
   真实库在 `/proc/1/root/data/data/<pkg>/databases/`，把 `eta.db` + `-wal` + `-shm` 一起拷到
   `/data/local/tmp/eta/`，用 Linux 侧的 sqlite 打开（会自动合并 WAL），改完再拷回去、
   删掉旧的 `-wal/-shm`、`chown u0_a400:u0_a400` 且 `chmod 660`。

### 23.8 收尾：测试数据与测试包都清掉了

改库前先 `pm disable-user --user 0 io.github.mangi.eta.canary`（这一下进程真没了，
`am force-stop` 会被无障碍绑定立刻拉回来，不可靠）。删除范围只限本轮造的：

```sql
DELETE FROM runtime_inflight_events WHERE run_id IN (SELECT run_id FROM runtime_inflight_runs);
DELETE FROM runtime_inflight_runs;
DELETE FROM conversation_context_checkpoints WHERE conversation_id IN ('conv-conc-a','conv-conc-b');
DELETE FROM conversation_messages          WHERE conversation_id IN ('conv-conc-a','conv-conc-b');
DELETE FROM conversations                  WHERE id IN ('conv-conc-a','conv-conc-b');
DELETE FROM provider_models  WHERE id='debug-hang-model';
DELETE FROM model_providers  WHERE id='debug-hang-provider';
UPDATE conversation_state SET selected_conversation_id='' WHERE id='main';
```

复核（清完、写回设备后）：`conversations` 0 行、`conversation_messages` 0 行、
`runtime_inflight_runs` 0 行，内置 `model_providers` 12 行 / `provider_models` 31 行原样保留。
启动一次应用确认库没被改坏：进程起来、`eta.db-wal` 重新生成（0 字节）、
`grep -iE "FATAL|SQLiteException|room|corrupt"` 无命中，界面回到「有什么可以帮你？」的空态。

最后：`pm uninstall io.github.mangi.eta.canary` → `Success`；系统把
`enabled_accessibility_services` 自动收回成只剩
`io.github.mangi.eta/.agent.accessibility.AgentAccessibilityService`；
释放虚拟屏、停掉挂起端点（18080 无监听、无 mock 进程）；前台回到主包 `MainActivity`。
临时库副本、四个 `canary-conc*` 快照、`main-peek` 快照已删；
证据文件留在 `/data/local/tmp/eta/evidence/conc-*.txt`（§22.6 同样留档）。

### 23.9 本轮没验到的（别当成已验证）

- **「暂停」的作用域没做双跑交叉验证**：代码是 `MSG_PAUSE_RUN` 带 `runId → pauseRun(runId)`，
  但两次都是「停一个、另一个在跑」的停止场景，暂停只单条跑过，没验「暂停 A 时 B 是否照跑」。
- **列表长按菜单里的「停止运行」没实测**（只核到它只在运行中的会话上出现）。
- **非 primary 的 run 完成后的静默行为**只读了代码（前台直接 return），没等到「A 在跑时 B 自然跑完」的现场。


## 24. 2026-10-29 第十七轮：§23.9 那三条「没验到」逐条补验

§23.9 当时留了三条明确未验证：暂停的作用域、列表长按的「停止运行」、非 primary 的 run 自然收尾。
本轮在同一台机器（PKX110 / Android 16）上把三条现场都造出来跑完了。**全部实测**，
下面每个结论都带时间戳或数据库快照；读代码得来的部分单独标注。

### 24.1 现场怎么搭的

沿用 §23 的骨架，但换了两个 mock 端点：

| 端点 | 行为 | 对应会话 |
|---|---|---|
| `127.0.0.1:18080` | `mock-llm-hang.py 3600`：收到请求后永不返回 | `conv-pause-a`（DebugHang） |
| `127.0.0.1:18081` | 先 `hang 3600`，验第三项时换成 `short 45`（45 秒后回 `SHORT_OK` + `[DONE]`） | `conv-pause-b`（DebugShort） |

`conversations.model_id` 填的是 `provider_models.id`（`debug-hang-model`／`debug-short-model`），
不是模型名——这个坑 §22 已经踩过，本轮没再踩。

canary 进程 `pid 7946`，虚拟屏 `display=10`（`vdmvt7oe2x`），全部操作都在虚拟屏上完成，
主屏只用来收掉被 LSPosed 拉起的 `BugreportWarningActivity`（§23 的老面孔）。

### 24.2 补验一：暂停的作用域（A 暂停，B 照跑）

两条 run 同时在跑：

```
15:26:00.730  Agent run prepare done: id=run-9c20      ← conv-pause-b「pause-B 长跑验证」
15:26:01.012  provider_response_started http_code=200
15:26:31.802  Agent run prepare done: id=run-6127      ← conv-pause-a「pause-A 长跑验证」
15:26:31.948  provider_response_started http_code=200
```

`runtime_inflight_runs` 同时两行，与日志一致（第一步只是复现 §23 的结论，确保并发成立）。

然后在 **conv-pause-a** 点输入栏的「暂停」：

- 无障碍树里那一颗按钮的 `desc` 由 `暂停` 变成 `继续`，其余节点不变（`o152`）；
- 立刻切到 **conv-pause-b**：它的输入栏仍是 `[暂停][停止]`（`o156`）——**B 没有被连带暂停**；
- 再切回 **conv-pause-a**：仍是「继续」（`o160`）。这一下是关键：暂停状态**跨会话切换后还在**，
  说明它来自服务端 `activeRunBundle.paused`（`AgentRuntimeService.kt:1037` 用
  `sessions[runId]?.controller?.isPaused` 生成），不是某个界面的临时本地标志。
- 数据库侧：两条 run 都还在 `runtime_inflight_runs` 里（暂停不删 run），与语义相符。
- 最后点「继续」：按钮回到「暂停」（`o178`），恢复也各自独立。

代码对照（只读）：`toggleCurrentRunPause()`（`AgentAppState.kt:3006`）先
`resolveRunIdForConversation(selectedConversationId)` 解析出**当前会话自己的 runId**，
发命令后再 `queryActiveRun(runId)` 校准一次，客户端不把「命令已发出」当成「已暂停」。
实测行为与这段设计一致。

### 24.3 补验二：列表长按「停止运行」只停那一条

在 `conv-pause-b` 那一行长按，弹出菜单：置顶 / 重命名 / 导出 / 导出完整历史 / **停止运行** / 删除（`o163`）。
点「停止运行」后：

```
15:28:33.467  Agent runtime stopped
15:28:33.482  Agent run returned: id=run-9c20 ok=false err=已停止
```

被停的是 **run-9c20 = conv-pause-b 自己那条**，`conv-pause-a` 的 `run-6127` 一动不动
（`runtime_inflight_runs` 快照里只剩 `run-6127f43f-…`）。`conversation_messages` 里
`conv-pause-b` 追加一条 `(3, system_notice, 'stopped')`。

同时会话列表立刻把状态写在了行内（`o164`）：

```
conv-pause-b  15:28  「已停止」
conv-pause-a  现在   「pause-A 长跑验证」 + 「已暂停」
```

顺带一个对测试有用的发现：**「已暂停」「已停止」这两个状态文字是在无障碍树里的**，
可以用 `observe_screen` 直接读到，不必截图猜。§23 里说的「运行标记是蓝点、抓不到」只对
**正在运行**的那一行成立——运行中确实没有文字（列表项节点不带任何状态），停止/暂停之后才出现文字。
所以判「某会话此刻是否在跑」仍然要靠 `runtime_inflight_runs` 或截图看蓝点。

### 24.4 补验三：非 primary 的 run 自然收尾，前台保持静默

这一项要造「primary 正在等、另一个会话自己跑完」的现场：

1. `conv-pause-b` 换成 45 秒短回复端点，发「side-B 自然收尾静默测试」；
2. **发完立刻切回 `conv-pause-a`**（它此刻是 primary，run 处于暂停态）；
3. 等 `conv-pause-b` 自然收尾。

结果（日志 + 界面 + 数据库三方对齐）：

```
15:29:35.649  Agent run prepare done: id=run-432a      ← conv-pause-b「side-B …」
15:30:20.769  assistant_received round=1, content_chars=8
15:30:20.778  run_finished round=1, content_chars=8
15:30:20.804  Agent runtime stop skipped: active run still executing
15:30:20.808  Agent run returned: id=run-432a ok=true err=null
```

- `conv-pause-b` 正常落库：`(4, user, 'side-B 自然收尾静默测试')`、`(5, assistant, 'SHORT_OK')`；
- `conv-pause-a` 的界面**一个节点都没变**：收尾前 `o175` 与收尾后 `o177` 的节点集合完全一致
  （间隔约 57 秒，`display_event_age_ms` 已经 56.9 秒没有新事件，说明界面是真的静止）；
- 没有跳会话、没有弹结果卡片、没有把输入框里的东西动掉。前台就是安静地待着。

结论：**非 primary 的 run 自然收尾时，前台确实保持静默**，而那条 run 的结果照常写进自己的会话。

### 24.5 顺带修正：`stop skipped` 的条件不是「结束的 run 正好是 primary」

§23 里我把这条记成了「仅当结束的 run 恰为 primary 时触发」，本轮被现场推翻：
收尾的是 `run-432a`（`conv-pause-b`，非 primary），它照样打了
`Agent runtime stop skipped: active run still executing`。

代码写得比我的旧结论准确（`AgentRuntimeService.kt:1853`）：

```kotlin
if (sessions.isNotEmpty() || pendingStartRequests.isNotEmpty()) {
    AndroidAgentLogger.warn("Agent runtime stop skipped: active run still executing")
    return
}
```

条件是**「还有任意 run 在执行（或有待处理的启动请求）」**，与谁是 primary 无关。
语义也对：只要还有别的 run 活着，就不能 `stopSelf`、不能拆胶囊/光效，否则会把别人的 run 掐掉。

### 24.6 本轮发现的两个可观测性缺口（只记录，未改代码）

1. **暂停 / 恢复在引擎侧完全没有日志。** `pauseRun(runId)`（`:1219`）和 `resumeRun(runId)`（`:1236`）
   只改状态与 `state`，不打日志。于是「用户说点了暂停没反应」这类反馈，从 logcat 上**什么都看不到**，
   只能靠界面按钮的 `desc` 或 `queryActiveRun` 反推。真机上本轮就是靠按钮 `desc` 变化的（`o152`）
   才拿到证据。建议后续在这两处各加一行 `I/Eta` 日志（带 runId 与来源），改动很小，但能让
   「暂停到底有没有到引擎」变成可查事实。
2. **`Agent runtime stopped`（`:737`）不带 runId/scene。** 它出现在「长按停止」那条路径上，
   而紧接着的 `Agent run returned: id=…` 才有 id，两条日志的先后关系是唯一的定位依据。
   一旦中间隔了别的输出，就不好判断是谁触发的停止。

### 24.7 环境坑（本轮新增）

1. **`terminal_job` 的 `environment` 只认 `android` / `linux`**，传 `debian` 会直接
   `TOOL_ERROR: environment 仅支持 android/linux`。任务列表里回显的 `environment: debian`
   是**返回字段**，不是可传入的取值。这一处踩了两次（第二次换了参数才过）。
2. **虚拟屏上 `type_text` 不能同时带 `index` 与 `display_id`**：先 `tap_element` 把输入框点成焦点，
   再不带宽 `index` 写。带上 index 会被忽略（工具自己会提示），表现是「写进了别处」。
3. **抽屉开合有过渡帧**：点「会话历史」之后立刻 `tap_element` 会拿到只有一个节点的快照
   （`ui_nodes_shown: 1`），此时必须重新 `observe_screen` 再点，否则点空。
4. **换 mock 端点要在没有 run 的时候做**：`terminal_job stop` 会杀掉持连接的服务端进程，
   正在挂起的那条 run 会立刻以「网络错误」收尾。本轮都是在两条 run 都结束之后才切换的。
5. **两条 run 的 id 容易记混**：`run-9c20` 是**先发的 B**，`run-6127` 是**后发的 A**。
   我一度按「A 先发」的顺序去对日志，得出了「两个会话模型反了」的错误推断，回头对时间戳才纠正。
   → 认 run 一律用时间戳对齐，不靠发起顺序的记忆。

### 24.8 证据与收尾

- 原始记录：`/data/local/tmp/eta/evidence/section24-concurrency.txt`（时间线 + DB 快照 + 节点摘录）、
  `section24-logcat.txt`（整段 `logcat -v time --pid 7946`）、`section24-eta.db`（收尾前的库快照）。
- 收尾：停两个 mock 端点（18080/18081 均无监听）、卸载 `io.github.mangi.eta.canary`、
  系统把 `enabled_accessibility_services` 自动收回成只留主包、释放虚拟屏 `vdmvt7oe2x`、
  删掉 `/data/local/tmp/eta/snap` 与 Linux 侧 `/workspace/snap` 临时库、前台回主包。
- 仍未验证（如实留着）：暂停状态下**长按停止**是否也走同一条路径、暂停中收到用户追问（supersede）
  的行为、以及多会话时胶囊/浮层在**主屏**上的实际呈现（本轮全程虚拟屏，浮层属于系统层，
  虚拟屏上不显示）。


## 25. 2026-10-29 第十八轮：把 §24.6 那两个可观测性缺口补上

§24.6 记了两条「只记录、未改代码」的缺口。本轮把两条都改掉，并在同一台机器
（PKX110 / Android 16 / sdk 36）上真机跑通。下面每条结论都标了「实测」还是「只读代码」。

### 25.1 缺口是什么

1. `pauseRun(runId)` / `resumeRun(runId)` **一行日志都没有**。用户报「点了暂停没反应」时，
   logcat 上没有任何痕迹，只能靠界面按钮的 `desc` 或 `queryActiveRun` 反推（§24 就是这么拿到证据的）。
2. 收尾那条 `Agent runtime stopped` **不带 runId**。它和后面 `Agent run returned: id=…` 的先后关系
   是唯一线索，中间夹了别的输出就分不清是谁停的。

### 25.2 改了什么（代码）

`app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeService.kt`：

| 位置 | 新日志 | 语义 |
|---|---|---|
| `cancelRun` 的 pending 分支 | `Agent run stop requested: id=… state=pending` | 还没派发就点了停止（此时没有 session，也没有 primary） |
| `cancelRun` session 为 null | `Agent run stop ignored: id=… reason=session_missing` | 服务端没有这条 run |
| `cancelRun` 已终态 | `Agent run stop ignored: id=… reason=terminal` | 收到时它已经结束了 |
| `cancelRun` 正常路径 | `Agent run stop requested: id=… state=running primary=…` | **打在 `controller.cancel()` 之前** |
| `pauseRun` 前置判断失败 | `Agent run pause ignored: id=… reason=session_missing\|terminal` | 同一条命令为什么没生效 |
| `pauseRun` 正常路径 | `Agent run paused: id=… gui_lock=… primary=…` | 打完这行时暂停已经落实 |
| `resumeRun` 前置判断失败 | `Agent run resume ignored: id=… reason=…` | |
| `resumeRun` 正常路径 | `Agent run resumed: id=… gui_lock=… primary=…` | |

`AgentRuntimeRunExecutor.kt:516`：`Agent runtime stopped` → `Agent runtime stopped: id=…`。
打它的条件是 `cancelled`（执行器被取消后收尾），所以它和 `stop requested` 是同一个暂停/停止动作的两端。

几个字段的口径（都对照代码确认过）：

- `id` 是 `take(8)`，即 `run-` + UUID 前 4 位（`run-9cb7`）。这与本文件既有的
  `Agent run prepared / dispatch / superseded` 完全一致；完整 id 由
  `agent_round_model run=run-9cb7e398-…` 那行提供，用前缀对齐即可。
- `primary` = 这条 run 是否是界面正在显示的那条（`primaryRunId == runId`）。
- `gui_lock` = 暂停/恢复是否交给 `GuiControlCoordinator` 处理（即这个 run 当时持有 GUI 控制权）。
  `pauseForUser` / `resumeForUser` 返回 true 时，协调器会在「当前动作真正结束」后再置
  `UserPaused` 并推进代号（`GuiControlCoordinator.kt:43-76`）；false 表示纯文本推理 run，
  直接 `controller.pause()/resume()`。
- `stop requested` 放在 `cancel()` **之前**：如果 cancel 抛错或阻塞，这行就永远不会出现，
  事后分不清「没收到停止请求」和「收到了但停不下来」。

### 25.3 真机验证（第一版构建）：四条链路

现场：canary 包 `pid 2366`，虚拟屏 `display=11`（`vdmvt8gi7x`）；会话 `暂停-A`（DebugHang →
`127.0.0.1:18080` 永不返回）。全部操作在虚拟屏上完成，主屏未参与。

| 时间 | 操作 | 日志 | 界面侧对照（无障碍树） |
|---|---|---|---|
| 15:40:27.920 | 发「§25 暂停恢复日志验证」 | `Agent run dispatch: id=run-9cb7` | 输入栏出现 `[暂停][停止]` |
| 15:41:15.782 | 点「暂停」 | `Agent run paused: id=run-9cb7 gui_lock=false primary=true` | 同一颗按钮 `desc` 由 `暂停`→`继续` |
| 15:42:11.561 | 点「继续」 | `Agent run resumed: id=run-9cb7 gui_lock=false primary=true` | `继续`→`暂停` |
| 15:42:22.132 | 再点「暂停」 | `Agent run paused: id=run-9cb7 gui_lock=false primary=true` | `暂停`→`继续` |
| 15:42:38.731 | 会话列表长按 →「停止运行」 | `Agent run stop requested: id=run-9cb7 primary=true`（第一版格式） | 该行变「已停止」 |
| 15:42:38.736 | — | `Agent runtime stopped: id=run-9cb7` | |
| 15:42:38.747 | — | `Agent run returned: id=run-9cb7 ok=false err=已停止` | |

这一条同时补掉了 §24.8 里留的「**暂停状态下长按停止**」——点停止时 run 确实是暂停态
（15:42:22 那条 paused 之后 16.6 秒才 stop），走的是同一条 `cancelRun`，没有特殊分支。

接着在同一个会话发第二条（`run-a869`）验证**运行中直接点输入栏「停止」按钮**：

```
15:43:23.465  Agent run dispatch: id=run-a869 foreground=true rootFallback=true
15:43:28.483  Agent run stop requested: id=run-a869 primary=true
15:43:28.485  Agent runtime stopped: id=run-a869
15:43:28.494  Agent run returned: id=run-a869 ok=false err=已停止
```

两条路径（列表长按 / 输入栏按钮）最终都走同一个客户端出口：
`AgentAppState.stopConversationRun()` 与 `stopCurrentRun()` 都进 `requestStop()` →
`AgentRuntimeClient.cancelRun()`（读代码确认，`AgentAppState.kt:2860-2877`），
所以日志形态一致；服务端只有 `cancelRun` 一个入口。

### 25.4 第二版构建：`requested` 挪到动作之前 + 补 pending 分支，重验

第一版跑完发现 `stop requested` 是**先 `cancel()` 再打日志**，与「requested」这个名字的承诺不符，
于是改成先打日志、再 cancel；同时给 pending 分支补一行（否则「点了停止但什么都没发生」依然无痕）。
重装（`pm install -r`）后新进程 `pid 9061`，同一个会话发第三条：

```
15:46:40.572  Agent run dispatch: id=run-d70b foreground=true rootFallback=true
15:46:46.082  Agent run paused: id=run-d70b gui_lock=false primary=true
15:47:03.534  Agent run resumed: id=run-d70b gui_lock=false primary=true
15:47:14.734  Agent run stop requested: id=run-d70b state=running primary=true
15:47:14.736  Agent runtime stopped: id=run-d70b
15:47:14.748  Agent run returned: id=run-d70b ok=false err=已停止
```

`state=running` 这一行按代码保证打在 `cancel()` 之前，2ms 后出现 `runtime stopped`，
顺序就是「意图 → 结果」。

### 25.5 未实测的分支（如实留着）

1. **`ignored: reason=session_missing`**：要拿一个服务端根本没有的 runId 去停/暂停/恢复。
   界面走不到；§22 那个 debug 注入入口只支持「忘掉本地运行态」（`AgentDebugInjections.forgetLocalRunState`），
   不支持任意 runId 的命令 → **只读代码，未实测**。
2. **`ignored: reason=terminal`**：session 在 `postTerminalOverlay` 里被移除（`mainHandler.post`），
   从「执行器置终态」到「post 执行」之间是个很窄的窗口。它是竞态守卫，手工点不出来 → **未实测**。
3. **`state=pending`**：pending 窗口只存在于 prepare 到 dispatch 之间，本轮实测就是
   15:46:40.477 → 15:46:40.572（约 95ms）→ 界面点不进去 → **未实测**。
4. **`gui_lock=true`**：本轮两个 run 都卡在模型请求上（DebugHang 一个 token 都没回），从未持有 GUI 锁，
   所以看到的全是 `gui_lock=false`。持锁路径的暂停/恢复（协调器等动作结束再置 UserPaused、
   恢复后旧观察快照作废）本轮没造出来 → **未实测**。

### 25.6 单测与构建

- `./gradlew :app:assembleDebug -Peta.applicationIdSuffix=.canary --offline` **成功**。
- `./gradlew :app:testDebugUnitTest --tests "io.github.mangi.eta.agent.runtime.*" --offline`：
  **240 个用例，167 通过 / 73 失败**；把 73 条失败消息去重统计，**全部**是同一句
  `java.lang.AssertionError: The Robolectric native runtime is not supported on Linux (aarch64)`
  —— 本机是 aarch64 Linux，这是环境限制（与 §20 记录的口径一致），不是本次改动引入的失败。
  之所以要按失败消息去重、而不是数条数就说「都是环境问题」：这里 73 条失败来自 73 个不同测试类，
  只有对过消息才能排除「恰好也报了这个错」的巧合。

### 25.7 本轮新增的两个操作坑

1. **重装 APK 后旧 logcat job 会跟着旧 pid 一起变成死水**：`pm install -r` 杀掉进程，
   `logcat --pid 2366` 还在跑但永远不再产出。判 pid 要重新 `pidof`，日志 job 要重开。
   这轮一开始差点用旧日志的「没有新行」得出「停止没有触发日志」的错结论。
2. **虚拟屏动作返回的 `after` 快照可能落后于真实状态**：点「暂停」时 `after` 报
   `screen_changed=false`、树里连那颗按钮都不见了，但日志证明 15:46:46.082 暂停已生效，
   下一次 `observe_screen` 才看到 `desc=继续`。判「有没有生效」要看日志或重新观察，
   不能只看 `after` 的 `screen_changed`。

### 25.8 证据

- `/data/local/tmp/eta/evidence/section25-pause-resume-stop.log`
  （第一版构建：四条事件链 + `run-9cb7` / `run-a869` 全轨迹）
- `/data/local/tmp/eta/evidence/section25-logcat-build1.log`（`logcat -v time --pid 2366` 整段）
- `/data/local/tmp/eta/evidence/section25-pause-resume-stop-build2.log`
  （第二版构建：`state=running` + `run-d70b` 全轨迹）

## 26. 2026-10-29 第十九轮：补验 §25.5 第 4 条 —— 持锁路径的暂停/恢复

§25 把「暂停/恢复/停止」的引擎侧日志做完了，但留了四种没造出来的现场。本轮补的正是其中最要紧的一条：
**`gui_lock=true`**（run 真的持有 GUI 独占锁时被暂停/恢复）。顺带把「恢复后旧观察快照作废」也验了。
本轮**没有改代码**，产出是验证与文档。

### 26.1 怎么造出「真的持有 GUI 锁」的现场

- `wait` 在 `AgentToolRequirements` 里登记为 `gui=true`（`AgentToolRequirements.kt:90`），
  而 GUI 类工具的执行整段被包在 `control.withAction { generation -> executeLocal(toolCall, generation) }`
  （`AgentLocalTools.kt:342`）里 —— 所以 `wait` 睡的那一整段时间，这个 run 就是独占锁的 owner。
- 但**它并不能无限拖长**：`waitMs` 对时长做了 `coerceIn(100, 30_000)`
  （`RootShellDeviceController.kt:1457`）。界面上写「等待 · 90 秒」，实际 30 秒就 `tool_finished` ——
  本轮第一把就是照 90 秒的预期去点暂停，差点错过窗口。**要留操作窗口，只能靠 mock 反复回 `wait`，不能靠加大参数。**

### 26.2 两次独立 run 都拿到了 `gui_lock=true`

| 时间 | 事件（`logcat -v time -s Eta`） |
| --- | --- |
| 16:09:21.821 | `tool_started round=1, name=wait` |
| 16:09:21.822 | `gui_control_changed status=acquired` |
| **16:09:35.574** | **`Agent run paused: id=run-f2f5 gui_lock=true primary=true`** |
| 16:09:51.826 | `gui_control_changed status=user_paused`（`wait` 结束，动作结束时才落 userPaused） |
| 16:09:51.833 | `tool_finished round=1, name=wait` |
| **16:09:54.805** | **`Agent run resumed: id=run-f2f5 gui_lock=true primary=true`** |
| 16:09:54.962 | `run_finished round=2, content_chars=10` |

这一次也顺带证了「暂停是真的停住了」，而不只是打了一行日志：
`tool_finished`（16:09:51.833）之后到恢复（16:09:54.805）之间**没有任何 `provider_request_started`**，
即循环确实挂在检查点上等了约 3 秒，恢复后 157ms 就发出下一轮请求。

第二次独立复现（`run-f1d4`）：

```
16:12:03.233  tool_started round=1, name=observe_screen
16:12:03.234  gui_control_changed status=acquired
16:12:08.704  Agent run paused: id=run-f1d4 gui_lock=true primary=true
16:12:25.061  Agent run resumed: id=run-f1d4 gui_lock=true primary=true
16:12:33.529  tool_finished round=2, name=wait
```

注意这两次的性质不同，不能混为一谈：**A（run-f2f5）是「动作结束后仍处暂停」，能证明阻塞；
B（run-f1d4）的恢复发生在 `wait` 还没结束之前（25.061 < 33.529），只证明日志口径，不证明阻塞。**

### 26.3 恢复之后，暂停前的观察快照确实作废了（A/B 对照）

只验日志还不够：日志说「推进了代号」，不代表真的用得上。做法是让 mock 走一条固定剧本
（`观察 → wait → 用旧 observation_id 点`），**同一份剧本、同一个会话、都发旧快照**，
唯一差别是中途有没有点暂停+继续：

| | 中途是否暂停 | 工具结果（mock 收到的原文） |
| --- | --- | --- |
| C1 | 有（暂停 + 继续） | `{"ok":false,"code":"NO_OBSERVATION","message":"手机操作权已变化，请先调用 observe_screen 重新观察，再操作当前页面"}` |
| C2 | 无（对照组） | `{"ok":false,"tool":"tap_element","executor":"accessibility","code":"STALE_WINDOW","message":"活动窗口已经变化，请重新观察屏幕","auto_retried":true,"observation_id":"o4","index":0}` |

设备侧同一次拒绝也能看到：

```
16:12:33.698  tool_started round=3, name=tap_element
16:12:33.711  tool_finished round=3, name=tap_element, code=NO_OBSERVATION     ← C1（有暂停）
16:16:41.587  tool_started round=3, name=tap_element
16:16:41.778  tool_finished round=3, name=tap_element, code=STALE_WINDOW       ← C2（无暂停）
```

机制（读代码）：`executeLocal()` 开头会比对代号 ——

```kotlin
if (guiGeneration != null && publishedObservation.get().generation != guiGeneration) {
    publishedObservation.set(PublishedObservation())          // 旧快照直接丢掉
    if (AgentToolRequirements.requiresFreshObservation(toolCall.name)) {
        return textResult(errorResult("NO_OBSERVATION", "手机操作权已变化，请先调用 observe_screen 重新观察，再操作当前页面"))
    }
}
```

代号自增点在 `GuiControlCoordinator`：首次取得所有权、`pauseForUser`、`resumeForUser`、`release`/`close`。
所以「暂停一次 + 继续一次」会推进两次，暂停前那份快照必然失效；
而且**这段判据排在「比对 observation_id」之前**，所以文案是「手机操作权已变化」，不是「快照已过期」——
C2 走的是完全不同的分支（id 对不上 / 窗口变了），才得到 `STALE_WINDOW`。两条分支的**文案差异本身就是证据**。

如实标注两处不够干净的地方：
1. C1 里 mock 发的是假 id `o1`（正则没从观察结果里取到，取到的是兜底值），C2 里发的是真 id `o3`。
   因为代号检查在 id 比较之前，这个差异不影响结论；但**本轮没做到「同一份 id 分别在有/无暂停下各跑一次」**。
2. C2 的 `auto_retried:true` 说明它触发了「过期就自动重观察再重试一次」的兜底，所以最终结果里带的是重试用的
   `o4`；C1 在代号分支就直接返回，没有走到重试。

### 26.4 §25.5 的四种未实测分支，现状

| 分支 | 状态 |
| --- | --- |
| `ignored: reason=session_missing` | 仍未实测（界面走不到，debug 注入入口也不支持任意 runId） |
| `ignored: reason=terminal` | 仍未实测（`post` 之间的窄窗口） |
| `state=pending` | 仍未实测（prepare→dispatch 约 95ms） |
| **`gui_lock=true`** | **已实测**（本节，两次独立 run + 一次 A/B 对照） |

### 26.5 本轮踩的坑（都是可复用的）

1. **点「发送」后应用会从虚拟屏迁到主屏**：`observe_screen(12)` 变成「这块屏上没有任务」，
   而主屏上躺着的就是被测应用。要么 `virtual_display_launch(force=true)` 把它拉回来，
   要么先用长 `wait` 剧本留出足够的操作窗口再动手。
2. **`logcat` job 会自己超时死掉**：本轮那个 job 的 `timeout_ms=600000`，16:12:46 静默结束，
   16:13 之后的所有事件全无记录。差点把「日志里没有新行」读成「没有触发」。
   判事件一定要先确认 job 还活着。
3. **改数据库必须在应用停止之后做**：第一把是在应用运行时覆盖 `eta.db`，结果库被重建成空库
   （`conversations=0`，界面显示「还没有对话」）。改成 `pm disable-user` → 写库 → `pm enable` 之后一次就对了。
   （上一轮 §25 也是同一套流程，这次图快省掉停应用，直接买了个教训。）
4. **mock 的判据要用「最后一条消息」**：旧会话本身带历史工具结果，用「有没有 tool 消息」判断第一轮必然判错；
   改成「最后一条是不是 `role=tool`」才稳定。
5. **Python 函数内给全局变量赋值必须 `global`**：漏了 `_tap_attempts` 直接把 mock 打挂
   （`UnboundLocalError`）。副作用倒是看到了真实的重试文案：
   `模型请求暂时中断，4/8/14 秒后重试（2/4 3/4 4/4）` → `已重试 4 次仍未恢复，已保留此前完成的工具结果`。

### 26.6 证据

- `/data/local/tmp/eta/evidence/section26-guilock-and-stale-snapshot.log`
  （A/B 两次 run 的事件链 + C1/C2 对照的工具结果原文 + 结论）
- mock 剧本：`mock-llm-stale.py`（双轮 `wait` + 旧快照点击），`mock-llm-toolcall.py` 的判据本轮改成
  「最后一条消息是不是工具结果」。

## 27. 2026-10-29 第二十轮：想把「严格 A/B」补上，两次都被拦下（如实记录未达成）

§26.3 留了两处不干净：C1 发的是假 id `o1`（正则没取到，走了兜底），C2 发的是真 id `o3`；
而且两次都不是「同一个 id 的第一次使用」。本轮想把它做干净，**结果没做成**。
把过程、拦路原因和顺手挖到的两条坑记下来，免得下次再走一遍。

### 27.1 设计：一份剧本、两次 run、差别只有暂停

新剧本 `mock-llm-ab.py`（落盘在 `/data/local/tmp/eta/`）：

```
非 tool      → wait 5000                 （留给测试者把应用拉回虚拟屏）
wait#1 结果   → observe_screen(display_id)
observe 结果  → 记下 observation_id + 首个可点节点 index，回 wait 30000
wait#2 结果   → tap_element(同一 id, 同一 index)
tap 结果      → 文本，收尾
```

Run A 在 `wait#2` 的 30 秒里不碰界面；Run B 在这 30 秒里点「暂停」再点「继续」。
两次都是「observe 之后第一次使用这个 id」，且 id 由 `json.loads` 从观察结果里取（不再用正则兜底）。

同时从 §24 的证据副本派生了一份干净库（`prep-strict26.py`）：只留 `debug-hang-provider` /
`debug-hang-model` / 一条空会话 `conv-pause-a`，清掉 inflight 与历史消息。

### 27.2 拦路一：重装 canary 换掉 uid，KernelSU 授权随之失效

`pm install -r -d` 之后的第一次运行：

```
16:27:09.316 Agent run dispatch: id=run-75a5 foreground=true rootFallback=false
16:27:14.641 tool_finished round=2, name=observe_screen, code=ROOT_REQUIRED
```

`rootFallback=false` 是关键：canary 自己拿不到 root。而**虚拟屏动作是硬要求 root 的**：

```kotlin
// AgentToolRequirements.rootDenied
if (arguments.has("display_id") && arguments.optLong("display_id", 0L) > 0L) return true
```

所以 `observe_screen(display_id=13)` 直接被拦，剧本第一条就断了。KernelSU 的授权按 uid 存
（`/data/adb/ksu/.allowlist`，二进制 `USK` 格式，`strings` 里只有 `io.github.mangi.eta`，没有 canary），
`pm uninstall` + 重装后 uid 从 10615 变成 10616，旧授权不再适用。

**结论：验证虚拟屏路径前，先确认 canary 的 root 授权；没有就得重新授权，否则整条剧本跑不起来。**

### 27.3 拦路二（部分成功）：退回主屏能跑通剧本，但不算严格对照

为了不等授权，改让 mock 不带 `display_id`（主屏不需要 root）。这一次剧本完整跑完了：

```
16:24:42.318 Agent run dispatch: id=run-974c foreground=true rootFallback=false
16:24:42.514 tool_started round=1, name=observe_screen
16:24:43.186 tool_finished round=1, name=observe_screen
16:24:43.317 tool_started round=2, name=wait
16:25:13.327 tool_finished round=2, name=wait            ← 30 秒整，中途没碰界面
16:25:13.494 tool_started round=3, name=tap_element
16:25:13.715 tool_finished round=3, name=tap_element, code=STALE_WINDOW
```

即：**无暂停组在「同一份剧本、同一份快照的第一次使用」下拿到的是 `STALE_WINDOW`，不是 `NO_OBSERVATION`。**
和 §26 的 C2 一致，说明这条分支与「代号变化」是两条不同的路。

但这一组观察的是**主屏**（`"display":{"scope":"default","id":0}`）：点「发送」之后 canary 会被系统迁到主屏，
而 mock 的 `observe_screen` 不带 `display_id` 就落主屏，抓到的 `ui_nodes` 是状态栏与桌面。
两份快照不在同一块屏上，所以**还不能算严格对照**。

### 27.4 一次真实误触：主屏前台不是被测应用

退回主屏方案时踩到的：`observe_screen` 显示 `package=io.github.mangi.eta.canary`，
但主屏真正的前台是 QQ —— 一次按坐标（输入框位置）的点击落进了用户的 QQ 聊天页。
已立刻按 `BACK` 退出聊天页（**没有点发送按钮**，没有发出消息），随后释放操作权、停掉 mock、卸载 canary。

教训（下次直接照做）：
1. 任何主屏点击之前，先读 `observe_screen` 的 `focus.package` 与 `ui_nodes` 里节点的 `package` ——
   两者都得是被测应用；只看「上一次观察是谁」不够，主屏前台可能已经换人。
2. **主屏不该作为这类验证的场地**：虚拟屏不打扰用户，而主屏上任何误点都可能落到用户正在用的应用里。

### 27.5 结论

| 项 | 状态 |
| --- | --- |
| 严格 A/B（同一份 id 在有/无暂停下各用一次，同一块屏） | **未达成**（root 授权缺失 + 主屏前台被占）→ 已在 §28 补上 |
| 无暂停组在同一剧本下的分支 | 已实测：`STALE_WINDOW`（主屏快照，非 `NO_OBSERVATION`） |
| 有暂停组 | 本轮未重跑（§26 的 `run-f2f5` / `run-f1d4` 已实测 `gui_lock=true` + `NO_OBSERVATION`） |

§26.3 里「没做到同一份 id 分别在有/无暂停下各跑一次」的说明**保持有效**，不因本轮而改变。

### 27.6 下次复现清单

1. `pm install -r -d app/build/outputs/apk/debug/Eta-v3.3.5-debug.apk`（applicationId `io.github.mangi.eta.canary`）；
2. **检查 canary 的 root 授权**（`dispatch` 行出现 `rootFallback=true` 才算有；否则先在管理器里授权）；
3. `python3 prep-strict26.py` 派生干净库 → `pm disable-user` → 写回 `databases/eta.db`
   （`chown 10616`、删 `-wal/-shm`）→ `pm enable`；
4. `python3 mock-llm-ab.py 18080 13`（13 = 虚拟屏 displayId）；
5. 虚拟屏启动 canary → 发消息 → 剧本自动推进；Run A 不动、Run B 在 `wait#2` 里暂停+继续。

### 27.7 证据

- `/data/local/tmp/eta/evidence/section27-canary-no-root.log`
  （289 行：`rootFallback=false`、`ROOT_REQUIRED`、`STALE_WINDOW` 三处关键行）
- mock 剧本 `/data/local/tmp/eta/mock-llm-ab.py`、准备脚本 `/data/local/tmp/eta/prep-strict26.py`
- 收尾：canary 已卸载（`/proc/1/root/data/data/io.github.mangi.eta.canary` 不存在、`pidof` 空）、
  mock 无残留进程、虚拟屏已释放、临时库 `strict26.db` 已删

## 28. 2026-10-29 第二十一轮：严格 A/B 补上了（同一份剧本、同一节点，只差一次暂停）

§26.3 的「没做到同一份 id 在有/无暂停下各跑一次」、§27.5 的「未达成」，本轮都补上了。
**没有改代码**，产出是验证与几条新坑。前提有两条：用户已授权 canary 的 root；
并且明确要求**不要再卸载 canary**（卸载会换 uid，KernelSU 按 uid 授权，等于每次都要用户重新点一遍）。

### 28.1 结论

| | Run A（无暂停） | Run B（有暂停） |
| --- | --- | --- |
| 屏 | display 18（canary 自建 `vdmvtaxenl`） | display 17（canary 自建 `vdmvtaug0g`） |
| 观察 | `o7`，26 节点 | `o5`，26 节点 |
| 点的节点 | index 0「以浮窗显示计算器」 | index 0（同一节点） |
| 中间 30 秒 | 不碰界面 | 16:47:13 暂停 → 16:47:19 继续 |
| tap 结果 | `ok=true`、无 code（08:49:50） | `ok=false`、`code=NO_OBSERVATION`（08:47:31） |
| 提示文案 | — | 「手机操作权已变化，请先调用 observe_screen 重新观察，再操作当前页面」 |

两次 run 的工具序列完全一致（7 个）：`virtual_display_create` → `virtual_display_launch`
→ `wait 20000` → `observe_screen` → `wait 30000` → `tap_element` → `virtual_display_release`。
Run A 0 个失败，Run B 1 个失败（就是这一次 tap）。

这就是 §26.3 缺的那一块：同一份剧本、同一类屏、同一个节点、同样的「observe 之后第一次使用」，
唯一差别是中途有没有「暂停 + 继续」——结果一个成功，一个被判「手机操作权已变化」。

### 28.2 之前跑不起来的真原因：虚拟屏是「进程内」的

§27 把失败归到 root 授权；授权补上之后，剧本**仍然**断在同一步，而且现象很迷惑：

```
08:41:09 观察结果：{"ok":false,"code":"DISPLAY_RELEASED","message":"虚拟屏 14 已释放或不存在；本次动作未执行…"}
（同一时刻）virtual_display_list → {"display_id":14,"task_count":1,"foreground_package":"io.github.mangi.eta.canary"}
```

屏显然还活着，前台就是被测应用。换成**主应用**（Eta 本体）立刻 `observe_screen(display_id=14)` 就成功（`o310`）。
差别只在「谁在调用」：display 14 是主应用所在进程创建的，canary 进程里的 Runtime 注册表没有这块屏，
于是把「不是我的」判成 `DISPLAY_RELEASED`。

**结论：虚拟屏必须由发起工具调用的那个进程自己创建。** 剧本因此改成让 canary 自己
`virtual_display_create` + `virtual_display_launch`，观察对象换成无害的 `com.coloros.calculator`
（点它的「以浮窗显示计算器」不会产生持久影响）。

顺带一条可复用点：`virtual_display_release` 返回 `closed_tasks=1`，会把屏上的任务关掉而不是迁回主屏
——在虚拟屏里启动应用不会污染用户的桌面。

### 28.3 mock 剧本 v2（`mock-llm-ab2.py`）

```
非 tool          → virtual_display_create(preset=main)
create 结果      → virtual_display_launch(id, com.coloros.calculator)
launch 结果      → wait 20000              （等界面稳定）
wait#1 结果      → observe_screen(display_id)
observe 结果     → 记 observation_id + 首个可点 index，回 wait 30000
wait#2 结果      → tap_element(同一 id, 同一 index, 同一 display)
tap 结果         → virtual_display_release(id)
release 结果     → 文本收尾
```

两处安全阀：观察结果的 `display.id` 必须等于本次创建的屏（不等就 ABORT，**绝不跨屏点击**）；
失败时不做任何重试，避免把一次失败伪装成成功。

### 28.4 时间线（mock 日志与设备 logcat 对齐）

Run A（干净对照组）：

```
08:48:56 create 成功 display=18 id=vdmvtaxenl
08:48:58 launch 成功 display=18
08:49:18 wait#1 结束 → observe → o7，节点数 26，选中 index 0
08:49:49 wait#2 结束 → tap_element(o7, 0, display=18)
08:49:50 ★ ok=True code=None
16:49:50.315 Eta: tool_finished round=6, name=tap_element        ← 设备侧，无 code
08:49:51 release（closed_tasks=1）
```

Run B（有暂停）：

```
08:46:38 create 成功 display=17 id=vdmvtaug0g
08:46:40 launch 成功 display=17
08:47:00 wait#1 结束 → observe → o5，节点数 26，选中 index 0
16:47:13.544 Eta: Agent run paused:  id=run-bd9e gui_lock=true primary=true
16:47:19.789 Eta: Agent run resumed: id=run-bd9e gui_lock=true primary=true
08:47:30 wait#2 结束 → tap_element(o5, 0, display=17)
08:47:31 ★ ok=False code=NO_OBSERVATION（「手机操作权已变化…」）
16:47:30.984 Eta: tool_finished round=6, name=tap_element, code=NO_OBSERVATION
08:47:32 release（closed_tasks=1）
```

如实标注一处：Run A 还多跑过一版——屏 16、`o2`、tap 同样 `ok=true`，但那一次 `launch` 先吃了
`APP_ALREADY_RUNNING` 再 `force` 重试，工具数是 8 个（1 个失败是 launch）。
为了让两组序列完全对齐（7 对 7），才又跑了上面这个干净版；两次无暂停组的结论一致。

### 28.5 新踩的坑（都能复用）

1. **失败的工具结果常常没有 `tool` 字段**。`APP_ALREADY_RUNNING` / `DISPLAY_RELEASED` 的返回是
   `{"ok":false,"code":…,"message":…}`，mock 按 `tool` 判分支会掉进「else → 收尾文本」，
   把整条剧本**静默**结束（第一次就是这么失败、且看起来像「正常结束」）。
   修法：失败时回溯最近一条 `assistant` 的 `tool_calls` 还原工具名。
2. **`APP_ALREADY_RUNNING` 与 `pidof` 不是一回事**：`pidof com.coloros.calculator` 为空，
   但任务栈里有实例，`virtual_display_launch` 仍会拒绝；`force=true` 之后才搬到虚拟屏。
3. **主屏在用输入法时，虚拟屏的坐标点击会被暂缓**：
   `MAIN_INPUT_ACTIVE`（`retry_after_ms=1200`，本次动作未执行）。本轮真实遇到一次，
   等 15 秒后重试成功。要稳定推进，优先用 `tap_element`（节点动作），或挑用户不用手机的时候。
4. **不要靠卸载来「清场」**：卸载重装会换 uid，KernelSU 的授权按 uid 存，等于每次都要用户重新授权。
   清理只做：停进程、释放虚拟屏、删临时库；安装包留着。

### 28.6 证据

- `/data/local/tmp/eta/evidence/section27-strict-ab-a-b-mock.log`（16.7 KB，Run A/B 全剧本原文）
- `/data/local/tmp/eta/evidence/section27-strict-ab-canary.log`（Run A 的 `tool_finished`，无 code）
- `/data/local/tmp/eta/evidence/section27-strict-ab-canary-pause.log`
  （Run B 的 `paused` / `resumed` / `NO_OBSERVATION` 五行摘录；缓冲区随后已滚掉，故以摘录留档）
- mock 剧本：`/workspace/mock-llm-ab2.py`（设备侧副本 `/data/local/tmp/eta/mock-llm-ab2.py`）
- 收尾：mock 已停；canary **未卸载**（`am force-stop` 后仍被系统/自身服务拉起，pid 换了但仍存活，属它的常驻行为）；
  虚拟屏全部释放（`virtual_display_list` → 0 块）；临时库 `strict26.db` 已删；仓库工作区 clean

## 29. 2026-10-29 第二十二轮：三处「把话说对」的改进，以及三个真机新坑

§28.5 第 1 条（失败结果缺 `tool`）和 §28.2 的根因（跨进程的屏被判 `DISPLAY_RELEASED`）本轮都改掉了；
另外把 `MAIN_INPUT_ACTIVE` 的文案从「故障口气」改成「说明口气」。三处的共同点是：
**工具做的事是对的，但把话说错了**，模型与用户都会因此走错路。

### 29.1 改了什么

| 位置 | 改动 |
| --- | --- |
| `agent/device/DisplayScopeErrors.kt` | 新增 `FOREIGN = "DISPLAY_FOREIGN"` |
| `agent/tool/AgentLocalTools.kt` `resolveDisplayId` | 注册表查不到目标屏时，先看「屏上还有没有可见窗口」：有 → 抛 `DISPLAY_FOREIGN`（跨进程屏）；无 → 仍是 `DISPLAY_RELEASED`（真释放） |
| `AgentLocalTools.execute()` | 所有工具结果统一过 `tagToolResult`：JSON 对象缺 `tool` 时补上，已有不覆盖，非 JSON 原样返回 |
| `agent/device/RootShellDeviceController.kt` | `MAIN_INPUT_ACTIVE` 文案改为「主屏正在输入，已暂缓虚拟屏点击（此时切输入法会抢走用户的键盘）；这不是故障，等用户停止输入后重试即可」 |

判定用「有窗口」而不是「display 还在」，原因写在代码注释里：`virtual_display_release` 之后系统回收是异步的，
那段时间 display 段还在、窗口已经没了；按存在性判会把「刚释放」误报成「别人的屏」。
`displayHasVisibleWindows()` 只在**注册表查不到**的错误路径上跑一次 dumpsys（`dumpsys window displays`
里从目标 display 段到下一段之间数 `Window{`），正常路径零开销。

### 29.2 怎么验的（角色互换）

主应用自己建的屏不能用来自证 FOREIGN（注册表里有它）。上一轮试过让 canary 建屏、主应用观察，
但 canary 的屏会在**回合结束时被回收**（见 29.4），等主应用去观察时屏已经没了。

本轮换成反向：**主应用建屏（display 19，上面跑着 canary），给 canary 装上含新代码的包，
让 canary 去 observe 19** —— 在 canary 的注册表里 19 就是「别人的屏」，而屏上有 canary 自己的窗口。

| 场景 | 目标 | 结果 |
| --- | --- | --- |
| 跨进程 + 有窗口 | display 19（主应用建，canary 的 MainActivity 在上面） | `code=DISPLAY_FOREIGN`、`tool=observe_screen`、`retryable=false` |
| 已释放 | display 21（canary 上一轮自建、已回收） | `code=DISPLAY_RELEASED`、`tool=observe_screen`、`retryable=false` |
| 本进程建的屏 | 主应用 observe display 19 | 正常返回节点（`o12` / `o16` / `o32`） |

两条失败结果的原文（mock 日志 `/workspace/mock-foreign-observe.log`）：

```
09:18:36 ★ RESULT ok=False code=DISPLAY_FOREIGN tool=observe_screen retryable=False
{"ok":false,"code":"DISPLAY_FOREIGN","message":"虚拟屏 19 存在，但不是本进程创建的；跨进程的屏在这里无法操作
（观察与注入都会落到别处）。请在本进程内 virtual_display_create 再 virtual_display_launch 目标应用；
不要原样重试这次调用","retryable":false,"tool":"observe_screen"}

09:20:44 ★ RESULT ok=False code=DISPLAY_RELEASED tool=observe_screen retryable=False
{"ok":false,"code":"DISPLAY_RELEASED","message":"虚拟屏 21 已释放或不存在；本次动作未执行。…","retryable":false,"tool":"observe_screen"}
```

`DISPLAY_RELEASED` 这条同样关键：它证明新判断没有把「刚释放、窗口已不在」的屏误判成 FOREIGN。

### 29.3 新坑一：APK 换了，进程没换（最容易误判成「代码没生效」）

第一次验证失败得很像「代码写错了」：APK 里明明有 `DISPLAY_FOREIGN`（`unzip -p base.apk "classes*.dex"
| grep -c` = 2），但主应用 `observe_screen(21)` 仍报 `DISPLAY_RELEASED`。同一时刻 shell 侧的证据是：

```
dumpsys display          → eta-virtual-vdmvtbupq0（display 21，owner io.github.mangi.eta.canary）
dumpsys window displays  → Display: mDisplayId=21 段内 2 个 Window{，其中
                           mObscuringWindow=Window{… com.coloros.calculator/…}
```

真因：`lastUpdateTime=17:05:34`（APK 落盘），而进程 `/proc/<pid>` 启动时间是 **16:45:02** ——
安装没有换掉进程，跑的还是旧 dex。判断方法：

```
stat -c %y /data/app/…/base.apk          # 17:05:33
awk '{print $22}' /proc/<app pid>/stat   # 6228597 ticks ÷ 100 = 62286 s
cat /proc/uptime                          # 63947 s → 进程启动于 1661 s 前（16:45）
```

**教训**：`grep APK` 只能证明「包里有新代码」，不能证明「正在跑新代码」。要么重启进程，
要么换一个进程验证（本轮就是换成 canary，见 29.2）。

### 29.4 新坑二：canary 自建的屏，回合结束就被回收

上一轮 canary 建屏（display 20/21）后脚本用纯文本收尾，等主应用去观察时 `dumpsys display` 里
已经没有这块屏（`eta-virtual-` 只剩主应用的 19）。不是造屏失败，是**回合结束就被清掉**。

修法：mock 在 `launch` 成功后改回 `wait 60000`，屏在等待期间保持存活，对面才有窗口去观察。
验证时 display 21 段内确实有 2 个 `Window{`，其中就是 calculator。

### 29.5 新坑三：虚拟屏点输入框会唤醒主屏输入法

给 canary 发消息时，重启后的输入框没有焦点，`type_text` 返回 `input_verification=unknown`
（工具自己提示「没有聚焦的输入框，`input text` 会静默丢弃」）。点了一下输入框，
`dumpsys input_method` 立刻变成 `mInputShown=true`，而 IME 窗口（`Window{9232edd u0 InputMethod}`）
出现在 **display 0 段** —— 输入法显示在主屏，不在虚拟屏。

本轮因为主屏 `mCurrentFocus=null`（没人用），没有实际打扰；但这说明「虚拟屏上点输入框」这件事
在用户正在用手机时确实会抢键盘。收尾输入改用 `input -d 19 tap 1055 2500` 直接点发送按钮，
没有再点输入框。

### 29.6 证据与收尾

- mock 剧本：`/workspace/mock-foreign-observe.py`（`python3 mock-foreign-observe.py 18080 [display_id]`，
  默认 19；失败结果缺 `tool` 时回溯最近一条 assistant 的 `tool_calls` 还原工具名；`stream=true` 必须回 SSE，
  否则 App 判「模型接口未返回 SSE data chunk」并整轮重试 —— 这是本轮第一版 mock 只回 JSON 时踩的坑）
- canary 覆盖安装证据：`firstInstallTime=16:35:18` 未变、`appId=10617` 未变、`lastUpdateTime=17:17:01`
  ——没有卸载重装，KernelSU 授权不用重点
- 构建：`./gradlew :app:assembleDebug -Peta.applicationIdSuffix=.canary --offline`，产物
  `build-artifacts/Eta-v3.3.5-debug-canary.apk`（md5 `f236d322…`）；主包备份
  `build-artifacts/Eta-v3.3.5-debug-main.apk`（md5 `a7fd01f7…`，与设备上安装的一致）
- **未验证（如实标注）**：主应用自身跑新代码后的 FOREIGN —— 它仍在跑 16:45 启动的旧 dex（见 29.3）。
  本轮用 canary 验证的是**同一份代码**；主应用要等下一次进程重启才生效。

## 30. 2026-10-29 第二十三轮：`DISPLAY_FOREIGN` 判据被实测推翻后改对，主应用侧补验

上一轮（§29）用「`dumpsys window displays` 里目标 Display 段的 `Window{` 行数 > 0」来判定跨进程。
本轮把它拿到两个真实场景里跑，**判据直接失效**；换成「`dumpsys display` 的 Display 段 + owner 包名」后，
同一份代码上两个分支都验过了。

### 30.1 旧判据为什么不可靠（实测）

| 场景 | 该 Display 段的 `Window{` 行数 | 旧判据结论 |
| --- | --- | --- |
| 主应用建的 display 22/26（上面跑着 calculator） | **0** | 误判成 `DISPLAY_RELEASED` |
| 上一轮验证用的 display 19（上面跑着 canary 的 MainActivity） | 2 | 误判成 `DISPLAY_FOREIGN`（碰巧对） |

差异在于**这一段里有没有焦点/遮挡窗口的引用行**（`mCurrentFocus=` / `mObscuringWindow=`），
display 22 段实测是 `mCurrentFocus=null`、`mFocusedApp=null`，于是 `grep -c 'Window{'` 为 0 ——
窗口列表并不在这个段里。也就是说旧判据量的其实是「这块屏有没有焦点窗口」，与「屏属于谁」无关。

### 30.2 新判据（代码里已写明实测数据）

```kotlin
// 段在 + owner 不是本包 → 跨进程；段不在 → 已释放
private fun probeDisplay(displayId: Int): Pair<Boolean, String?> =
    // dumpsys display | sed -n '/^  Display N:/,/^  Display [0-9]*:/p'
    // 正则 owner (\S+) \(
```

- 段不存在 → `exists=false` → 仍抛 `DISPLAY_RELEASED`
- 段在且 `owner ≠ context.packageName` → 抛 `DISPLAY_FOREIGN`，文案带创建者包名
- 段在但 owner 解析不出来（格式变了）→ 退回 `DISPLAY_RELEASED`：宁可少报，
  也不要让用户按「跨进程」的指引去重建一块其实属于自己的屏

时序数据（Android 16 / SM8750，实测）：

| 时刻 | `dumpsys display` |
| --- | --- |
| 别人建的屏（display 26，跑 calculator） | `Display 26:` 在，`owner io.github.mangi.eta (uid 10629)` |
| 本进程 release 后 2 秒（display 24） | `Display 24:` 整段消失 |

所以 release 之后不存在「段还在、窗口已没」的空窗期，不需要用窗口数来兜底。

### 30.3 主应用侧补验（补上 §29.6 那条「未验证」）

§29 的 FOREIGN 是在 **canary** 进程里验的。本轮主应用被覆盖安装后进程重启，跑的就是新代码，
于是用「canary 建屏、主应用观察」再验一遍：

1. `am start -n io.github.mangi.eta.canary/io.github.mangi.eta.ui.MainActivity --es eta.debug.virtual_display create`
   → 建出 display 30（1080×1920，owner `io.github.mangi.eta.canary`，uid 10617）
2. 主应用 `observe_screen(display_id=30)` →
   `{"ok":false,"code":"DISPLAY_FOREIGN","message":"虚拟屏 30 存在，但它不属于当前应用（创建者 io.github.mangi.eta.canary）；…","tool":"observe_screen"}`
3. canary 释放该屏（`--es eta.debug.virtual_display release`，`Display 30:` 消失）
4. 主应用再 `observe_screen(display_id=30)` → `{"ok":false,"code":"DISPLAY_RELEASED",…,"tool":"observe_screen"}`

两个分支落在**同一份新代码**上，且都不需要安装第二个包。

### 30.4 新坑：不带 suffix 构建出来的就是主包

本轮为了把改动送进 canary，连续用 `./gradlew :app:assembleDebug --offline` 构建。
`app/build.gradle.kts` 里是

```kotlin
applicationId = "io.github.mangi.eta" + (providers.gradleProperty("eta.applicationIdSuffix").orNull ?: "")
```

不带 `-Peta.applicationIdSuffix=.canary` 时产物就是**主包**（`io.github.mangi.eta`），装上去会覆盖
正在用的主应用：进程重启、会话中断，而 canary 仍然是旧包 —— 现场表现就是「装了但代码没生效」，
再加上「我明明装好了」的两边认知错位。

正确命令与核对方法：

```
./gradlew :app:assembleDebug -Peta.applicationIdSuffix=.canary --offline
pm path io.github.mangi.eta.canary
dumpsys package io.github.mangi.eta.canary | grep -E "lastUpdateTime|versionName"
```

**教训**：构建产物先确认 `applicationId` 再安装；「安装成功」不等于「装到了目标包」。

### 30.5 `tagToolResult` 的复现（三类错误都带 `tool`）

mock 剧本 `/workspace/mock-ux29.py`（日志留档 `/workspace/evidence/section29-ux29-mock.log`）：

```
step=launch           got='virtual_display_launch' ok=False code='APP_ALREADY_RUNNING' | tool=virtual_display_launch ✓
step=observe-released got='observe_screen'         ok=False code='DISPLAY_RELEASED'     | tool=observe_screen ✓
step=observe-foreign  got='observe_screen'         ok=False code='DISPLAY_FOREIGN'      | tool=observe_screen ✓
```

这三条都是**失败**结果，`tool` 都在 —— 也就是说调用方不必再回溯上一条 assistant 消息去猜工具名。

### 30.6 收尾与现状

- 现场：只剩 `Display 0`（虚拟屏全部释放）、18080 无监听、无 mock 进程、临时 DB 副本已删
- 主包被覆盖为 17:34 的构建（同签名覆盖安装，`appId`/数据未变）；需要回滚时用
  `build-artifacts/Eta-v3.3.5-debug-main.apk`（32290084 字节）
- **仍未验证**：`MAIN_INPUT_ACTIVE` 的新文案只做了代码核对 —— 触发它需要主屏正处于输入状态，
  本轮没有为了验证文案去抢用户的输入法

## 31. 2026-10-29 第二十四轮：运行中发送改成「排队」，并修掉「停止后又自己跑起来」

### 31.1 用户看到的两个现象

1. 会话运行中发消息，消息仍然被当成「插话／补充」塞进当前回合，想发的新一轮永远不出现；
2. 点停止后界面已经显示「未运行」，过几秒会话又自己动起来（最后那轮总结就是这么跑出来的）。

### 31.2 根因

**现象 1**：`sendCurrentMessage()` 在 `isStreaming` 时直接调 `steerCurrentRun(prompt)` —— 走
`sendSupplement` 把文本送进当前 run，不产生新的用户消息。按钮写着「发送」，行为却是补充指令。

**现象 2**：停止路径把「用户意图」弄丢了：

- `watchStoppedRunUntilGone()` 一旦查到 run 从活跃列表消失，就 `stopRequestedAt.remove(runId)`；
  而 run 的最终 `RunResult` 是随后才到 `applyRunResult()` 的，那时
  `explicitlyStoppedByUser = stopRequestedAt.containsKey(runId)` 已经是 false。
- 仅剩的兜底是 `result.error == "已停止"`；但 `drainQueueAfterRun()` 对队首
  `immediateInterrupt=true` 的消息**无条件发出**（插话语义），所以「停止」之后队列照样会把
  下一条拉起来。

也就是说：`stopRequestedAt` 同时承担了「去抖计时」和「用户停止证据」两个职责，前者在收尾前被
清掉，后者就跟着丢了。

### 31.3 改法

| 改动 | 位置 | 说明 |
|---|---|---|
| 运行中发送 = 排队 | `AgentAppState.sendCurrentMessage` → 新增 `queueCurrentMessage` | 入队 `immediateInterrupt=false`，不调 `requestStop`，弹「已排队，本轮结束后自动发送」 |
| 停止意图与去抖分离 | 新增 `stopIntentByInterrupt: MutableMap<String, Boolean>` | `requestStop(..., byInterrupt)` 先写意图再去抖；插话传 true，停止按钮默认 false |
| 停止监视器不再清标记 | `watchStoppedRunUntilGone` | 只收本地运行态，`stopRequestedAt` / `stopIntentByInterrupt` 留到 `applyRunResult` |
| 收尾按最后意图决定 | `drainQueueAfterRun(..., stoppedForInterrupt)` | 插话触发的停止不算「用户想停」；用户点停止 → `stoppedByUser=true`，整个队列冻结 |
| 文案 | 三处 `strings.xml` | 「插话发送（打断当前运行）」→「打断本轮并立即发送」 |

顺带清理：`reconcileStaleRunState` / `healStaleRunsAfterStartup` / `forgetRunState` 三处同时清两张表，
避免记录泄漏。

### 31.4 真机验证（canary，虚拟屏）

构建：`./gradlew :app:assembleDebug -Peta.applicationIdSuffix=.canary --offline` →
`app/build/outputs/apk/debug/Eta-v3.3.5-debug.apk`（32036597 字节，md5 `08e69a3a…`），
`output-metadata.json` 的 `applicationId=io.github.mangi.eta.canary`。

覆盖安装（未卸载）：

```
appId=10617                    （未变）
firstInstallTime=16:35:18      （未变）
lastUpdateTime=18:14:28
classes8.dex 含 queueCurrentMessage
resources.arsc 含 chat_queued_hint
```

> 装之前先查了设备上的旧包：主包／分身都是 32034489 字节（= 09:34 的构建），dex 里没有
> `queueCurrentMessage` —— 上一轮改完根本没上机，用户看到的仍是旧逻辑。这一步
> （比对 APK 字节数 + dex 方法名）应作为「装没装上」的固定判据。

验证过程（canary 数据目录为空，复制了主包的 `eta_settings.preferences_pb`，SELinux 用
`restorecon` + `chcon` 修正到 `app_data_file:s0:c105,c258,c512,c768`）：

1. 发一条消息触发 run（模型是内置 `DebugHang`，请求重试 4 次后失败，给了约 20 秒窗口）；
2. 运行中在输入框写「第三条：排队C」，点主按钮（此刻 `contentDescription` 已是
   「排队发送（本轮结束后发出）」）：
   - 输入框清空；
   - 出现队列胶囊「排队中 · 第三条：排队C」+「立即插话发送」／「移除排队消息」；
   - **run 没有中断**：界面上重试消息（1/4…3/4）继续推进，最后一条用户消息仍是「第二条」。
3. 点停止 → 出现「已停止」，队列胶囊仍在；
4. 静置 8 秒后再观察：队列仍是「排队中 · 第三条：排队C」，主按钮回到「发送」，
   **没有新 run** —— 用户停止后队列不自动续发。

### 31.5 未验证／已知限制

- **没有验证「自然结束 → 队列自动发出」**：本轮 run 全部因 DebugHang 请求失败结束，没走到
  自然收尾；这一条只有 `AgentMessageQueueReducer` 的单测覆盖（`pollNextToSendAfterRun`）。
- canary 复制的配置来自主包 08:47 的快照，没有可用 provider key，所以只验证了 UI／状态机，
  没有验证真实模型对话。
- 主包已于 18:22:55 覆盖安装同一构建（32036553 字节；`appId=10629`、`firstInstallTime=10-03 10:38:00`
  均未变，`classes8.dex` 含 `queueCurrentMessage`），旧包备份在
  `/data/local/tmp/eta/backup/eta-main-32034489.apk`（md5 `b89c5d53…`）。

### 31.6 教训

1. **「我改好了」≠「用户用上了」**：代码改完没构建、没安装，用户看到的现象不会变。
   判断装没装上，看 APK 字节数、`applicationId`、dex 里的新符号，而不是看安装时间。
2. **一张表不要同时承担两个职责**：`stopRequestedAt` 既是去抖计时器又是「用户停止」证据，
   监视器一清，证据就没了。把「意图」拆出来单独存。
3. 停止监视器只该收「本地显示态」，不该替 run 收尾逻辑做决定。

## 32. 2026-10-29 第二十五轮：自查出「编译不过」和 4 处真缺陷，并把输入框回灌接通

### 32.1 起因与结论

用户问「没有编码短板吗」。逐处读代码后确认：上一轮的编辑守卫**根本编译不过**，
而且同一个 bug 还有第二个入口没堵。

### 32.2 已确认并修掉（5 条）

1. **编译失败**：`R.string.chat_edit_while_streaming` 在三处 `strings.xml` 里都没有定义。
   实测 `:app:compileDebugKotlin` → `Unresolved reference 'chat_edit_while_streaming'`、
   `BUILD FAILED in 7s`。已补 zh-Hans / zh-Hant / en，并新增
   `chat_interrupt_sent_after_finish`（假运行态插话真的发出去时用）。
2. **插话入口没有守卫**：`interruptAndSendCurrentMessage()` 完全不检查 `messageEdit`。
   运行中点「打断并立即发送」会把编辑内容当插话入队，`enqueue` 顺手清掉 `messageEdit`，
   用户刚改的那条一点没变。已与主发送共用 `rejectMessageEditWhileStreaming()`。
3. **队列续发会写坏编辑**：`dispatchQueuedMessage()` 把消息正文写进 `input` 再发送，而编辑态下
   `input` 存的就是编辑正文。轻则顶掉用户输入；若编辑是「保留后续消息」模式，
   `sendCurrentMessage` 还会把这条排队消息当成编辑正文写进历史。已改为编辑态放回队首。
4. **提示语说假话**：假运行态下插话实际已经发出，代码仍无条件弹「任务已经结束」。
   `dispatchPendingInterrupt()` 改为返回是否真的发出，分别提示。
5. **纯函数没单测**：`isQueueFrozen` 补 5 组用例（6 用例 0 失败），覆盖
   点停止 / 插话停止 / 服务端自停 / 自然结束 / 先停后插话。

### 32.3 产品口径（用户授权「你来决定」）

- 运行中**一律**禁止保存编辑（原来只挡 `preserveFollowingMessages` 模式）。
  依据：`beginMessageEdit()` 本来就在运行中拒绝，所以「运行中 + 编辑态」几乎只在竞态下出现；
  为不可达路径加隐藏状态不划算，规则简单一致更重要。
- 拒绝时**不吞文字**：把正文放回草稿位并抬 `inputSyncVersion`。

### 32.4 `inputSyncVersion` 原本是个死字段

`recallToDraft()` 每次都 `+1`，但 UI 层全仓库无人消费；输入框是
`rememberTextFieldState(initialText = input)`，只在「进入/退出编辑」时同步。
静态推断：「撤回排队消息到输入框」在界面上不会显示出来。

本轮把版本号从 `AgentHomeScreen` → `AgentChatBody` → `AgentChatScaffold` →
`AgentChatBottomBar` → `AgentChatInputBar` 一路传下去（各层默认 0），并加
`LaunchedEffect(inputSyncVersion)`：**只在版本变化时**把文本灌回输入框，
普通打字仍走本地状态，不会逐字重排聊天。

### 32.5 验证到哪一步

- `:app:compileDebugKotlin` 通过（改动前同一条命令失败）。
- `AgentMessageQueueReducerTest` 6 用例、0 失败（含新增用例）。
- canary 包：`build-artifacts/Eta-v3.3.5-debug-canary-fix32.apk`，
  32,039,893 字节，md5 `934ca0803800316cc0b7233286335b8f`；
  manifest 含 `io.github.mangi.eta.canary`（UTF-16 字节比对），
  dex 含 `queueCurrentMessage` / `chat_edit_while_streaming` / `inputSyncVersion`。
- **未安装**（装机需先征得同意），所以「撤回/拒绝后文字真的回到输入框」**未经真机验证**。

### 32.6 仍存在的已知问题（本轮没动）

- `drainQueueAfterRun` 顶部注释写「用户正在打字时不抢草稿位……下次收尾或切回会话时再发」，
  下面几行又写「没有『切回时自动补发』的钩子」，两句互相矛盾。实际行为是：
  输入框里有草稿时，队列会一直等到下一次 run 收尾。
  → **已在 §32.9 修掉**（注释按实际行为改写，并把「切回不会触发 drain」写明）。

### 32.7 真机验证（2026-10-29 晚，PKX110 / Android 16，canary 覆盖安装）

安装：`pm install -r -d` → `Success`；包名 `io.github.mangi.eta.canary`，
32,039,893 字节 / md5 `934ca0803800316cc0b7233286335b8f`（与 32.5 同一份产物），
`firstInstallTime` 保持 16:35:18（覆盖更新，未卸载），`lastUpdateTime` → 18:57:52。

**已验证（虚拟屏实机操作，DebugHang 提供长 run）**

1. 运行中发送 = 排队：输入第二条点主按钮 → 出现「排队中」胶囊（含「立即插话发送」
   「移除排队消息」），当前 run 未中断，输入框已清空。
2. 点停止 → 出现「已停止」；等 12 秒后队列仍在、**没有**自动续发（会话停在已停止）。
3. 点「立即插话发送」→ 队列胶囊消失，该条以独立用户消息进入对话，新 run 开始。
4. 编辑态与队列共存：编辑「第三条」时 run 自然结束，队列消息**没有**被写进输入框
   （编辑正文完好），消息留在队列；`dispatchQueuedMessage` 的 `messageEdit` 守卫
   在真机上确实生效。
5. 运行中「消息操作」不可用（`messageActionsEnabled = !isStreaming`），停止后才出现
   「复制 / 编辑 / 删除」菜单。

**未能真机验证（UI 上不可达，只有代码审查 + 单测）**

- `rejectMessageEditWhileStreaming()` 的 toast：需要「编辑态 + `isStreaming=true`」。
  三条路都堵死：
  ① 运行中点消息 → 菜单禁用（`beginMessageEdit` 也有 `isStreaming` 守卫）；
  ② 编辑态下点队列条「立即插话发送」→ `steerQueuedMessage` 顶部
     `if (isStreaming || messageEdit != null) return` **静默返回**，点了没反应，
     也没法借此启动 run；
  ③ 编辑态下主按钮是「保存」，走 `sendCurrentMessage`，此时 `isStreaming=false`，
     走的是正常保存分支。
  → 该守卫只在**状态失配**（事件延迟、跨进程恢复）时命中，属防御性代码。
- 假运行态插话（`dispatchPendingInterrupt` / `NoActiveRun`）：
  `AgentConversationStore` 读盘时把 `isStreaming` 强制置 false（638/785/808 行），
  `healStaleRunsAfterStartup` 又会在 5 秒后清掉不在活跃快照里的 checkpoint，
  所以「进程重启后留着假运行态」走不通；UI 上也构造不出「isStreaming=true 但
  本地无 runId、服务端也没有该 run」。同样只有代码审查。

**顺带发现的 UX 缺陷（未修）**

编辑态下点队列条「立即插话发送」**毫无反馈**（`steerQueuedMessage` 静默 return）。
按钮可点、点了没动静，比置灰更差。建议二选一：编辑态把该按钮置灰，
或弹一句「先取消编辑再插话」。

### 32.8 第二轮真机验证（同日 19:10，覆盖更新后复验 + 补上三项此前只有单测的行为）

安装：`pm install -r -d` 同一份产物 → `Success`；`firstInstallTime` 仍 16:35:18、
`lastUpdateTime` 18:57:52 → 19:10:55；启动后历史会话完整（证明覆盖更新保留数据）。

**本轮补上的真机证据（都此前只有单测或从未观察过）**

1. **运行态主按钮语义**：运行中且输入框有文字时，底部变四格 ——
   「切换模型」「暂停」「打断本轮并立即发送」、
   主按钮 desc = **「排队发送（本轮结束后发出）」**。
   即「运行中发送 = 排队」不只是行为，按钮文案也说明了后果。
2. **run 自然结束（失败）后队列自动续发**：第一条 run 以
   「Runtime 运行失败（已重试 4 次）」结束后，队列里的第二条**自动成为独立用户消息**
   并开启新一轮（回合 6/6 → 7/7）。此前这条只有 `pollNextToSendAfterRun` 的单测覆盖，
   现在有真机观察。注意失败结束 ≠ `errorLooksStopped`，所以不冻结 —— 与
   `isQueueFrozen = !stoppedForInterrupt && (explicitlyStoppedByUser || errorLooksStopped)` 一致。
3. **用户点「停止」后队列冻结**：运行中先排队一条（胶囊出现）→ 点「停止」→
   出现「已停止」，胶囊**原样保留**；等 20 秒，`display_event_age_ms` 29.3 秒
   （界面完全静止）、回合数仍 7/7、底部已回到「发送」空闲态 —— **没有**自动续发。
4. **撤回队列消息把文字回灌输入框**（本轮最关键的一条，直指 `inputSyncVersion`）：
   队列胶囊内点击消息预览文本即撤回（`QueuedMessagePreviewText.onClick = onRecall`，
   见 `AgentChatQueueStrip.kt`）→ 胶囊消失、`inputSyncVersion + 1`、
   输入框 EditText 的 `text` 立刻变为「第四条：排队后点停止」。
   这证明 `inputSyncVersion` → `AgentChatInputBar` 的 `LaunchedEffect(inputSyncVersion)`
   回灌链路在真机上确实通了（此前只是代码审查）。

**仍然未验证（结论不变）**

- `rejectMessageEditWhileStreaming()` 的 toast：三条 UI 路径仍全部堵死（见 §32.7），
  属状态失配时的防御性代码。
- 假运行态插话（`dispatchPendingInterrupt` / `NoActiveRun`）：读盘强制
  `isStreaming=false` + `healStaleRunsAfterStartup`，构造不出该状态。

**已知 UX 缺陷（仍未修，保持记录）**

编辑态下点队列条「立即插话发送」静默无反馈（§32.7 上一轮的观察；**本轮未复验**这条）。
建议之后单独处理（置灰或提示）。


### 32.9 第三轮：修掉「编辑态插话」这条 UX 缺陷（2026-10-29 晚，canary 覆盖安装 + 虚拟屏 A/B）

**先更正上一轮的一处记录不准**
§32.7 / §32.8 把这条缺陷写成「`steerQueuedMessage` 顶部 `if (isStreaming || messageEdit != null) return`
静默返回，点了没反应」。读现有代码，**那行守卫并不存在**。真实链路是：

1. `steerQueuedMessage` → `AgentMessageQueueReducer.promoteToInterrupt` 成功，该条被标成「立即插话」；
2. `requestInterruptCurrentRun()` **真的打断当前 run**（界面会出现「已停止」）；
3. run 收尾时 `dispatchQueuedMessage` 看到 `messageEdit != null`，把消息**原样放回队首**。

所以用户看到的不是「没反应」，而是「点一下，run 白停了一轮，消息还在队列里」——
执行了一半，比静默返回更糟。

**改法（6 个文件）**

- `ui/components/AgentChatQueueStrip.kt`：`AgentChatQueueStrip` 新增 `isEditingMessage: Boolean = false`；
  `QueuedMessageItemActions` 新增 `steerEnabled`，插话按钮 `IconButton(enabled = steerEnabled)`；
  图标 tint 从写死的 `primary` 改为禁用时 `onSurfaceVariantActions.copy(alpha = 0.4f)`。
  注释写的不是「禁用」，而是「为什么必须禁用」（插话会先打断 run，消息却发不出去）。
- `ui/components/AgentChatInputBar.kt`：把 `isEditingMessage` 透传给队列条。
- `ui/app/AgentAppState.kt`：`steerQueuedMessage` 顶部加 `messageEdit != null` 兜底 ——
  弹 `chat_queue_steer_blocked_by_edit` 后 return（键盘、无障碍、恢复态等其他入口走不到置灰按钮）；
  同时把 `drainQueueAfterRun` 头注释里互相矛盾的两句改成与实现一致，并写明「切回会话不会触发
  drain」（即 §32.6 那条，本节一并修掉）。
- 三语言 `strings.xml` 各加一条：
  `正在编辑消息，先取消编辑再插话` ／ `正在編輯訊息，先取消編輯再插話` ／
  `You are editing a message; cancel the edit before interrupting`。

**构建**

`:app:processDebugResources :app:compileDebugKotlin` BUILD SUCCESSFUL（仅两处既有的
`Condition is always 'false'` 警告）；`AgentMessageQueueReducer` 相关单测 **6 通过 / 0 失败**。
产物 `Eta-v3.3.5-debug.apk` **34,458,534 字节 / md5 `19020f6f999a863f87effa975b5e5dc2`**；
`output-metadata.json` 的 `applicationId` = `io.github.mangi.eta.canary`（versionCode 2026102909），
`resources.arsc` 里能查到新字符串名。归档 `build-artifacts/Eta-v3.3.5-debug-canary-fix32b.apk`。

**覆盖安装（未卸载）**

`pm install -r -d` → `Success`，本轮两次：`lastUpdateTime` 19:41:32 → 19:45:18；
`firstInstallTime` 始终 16:35:18，`files/`（agent、datastore、memory、skills）全部保留，
证明是覆盖更新而非重装。主包 `io.github.mangi.eta` 的 `lastUpdateTime` 仍是 18:22:55 —— **没碰主包**。

**真机 A/B（虚拟屏 display 35，PKX110 / Android 16）**

先要弄清「编辑态 + 队列」这个共存态怎么进得去：运行中（含暂停）「消息操作」不出现
（`messageActionsEnabled = !isStreaming`），所以**「编辑态 + 正在运行」在 UI 上不可达**。
能进得去的路径是「运行中点停止 → 队列冻结、胶囊保留 → 此时可以编辑」。

| 状态 | `立即插话发送` 节点形态 | 截图上的图标 |
|---|---|---|
| 未编辑 + 队列（基线） | 被 `clickable=true` 的 View 包裹 | 强调蓝 |
| 编辑态（消息操作 → 编辑） | 裸 `ImageView`，无 clickable 祖先 | `primary` × 0.4 alpha → 灰 |
| 取消编辑（同一会话内回切） | 又变回 clickable View | 又变回蓝 |

补充证据：

- 编辑态下点那块置灰区域 → **没有任何反应**：没有开新 run、队列条目仍在、输入框仍是
  「发送后将替换此消息」的编辑态（对比前后树，`screen_changed` 只是列表重排噪声）。
- 同一胶囊里的「移除排队消息」在编辑态仍可点 —— 只禁了插话，不是整条控件禁用。
- 暂停/继续不影响队列条与插话按钮的可用性：冻结只看 `messageEdit`，不看暂停。
- 本轮重新观察到「点停止后胶囊保留」（§32.8 第 3 条的冻结行为在这份构建上依旧成立）；
  本轮没再等满 20 秒专门测「不自动续发」，那条结论沿用 §32.8。

**仍未验证（不要当成已验证）**

- `chat_queue_steer_blocked_by_edit` 的 Toast：按钮置灰后 UI 路径不可达，键盘/无障碍/恢复态
  在这台机器上也没构造成 —— 仍是防御性代码。
- §32.7 列的两项（`rejectMessageEditWhileStreaming` toast、假运行态插话）维持「不可达」。

**收尾**

释放虚拟屏 `vdmvth3ne3`；`am force-stop io.github.mangi.eta.canary` 后进程当下退出，但
**canary 的无障碍服务处于启用状态**（`enabled_accessibility_services` 里主包和 canary 两个都在），
系统会把进程重新绑起来 —— 二三十秒后 `pidof` 又有新 pid，`ps` 里能看到它的
`AgentAccessibilityService` 连接记录。这是既有设备配置（本轮没改任何系统设置），
所以「停止」不持久，也不影响上面任何一条验证结论。测试用的排队消息已移除、草稿已清空；
canary 保持安装，主包未动。

**一条可复用的经验**

判定「进程被停掉了没有」，不能只看 `am force-stop` 的返回值和紧接着的 `pidof`——
有启用中的无障碍服务/前台服务时，系统会立刻把进程拉回来。要么多等一会儿再看，
要么直接看 `dumpsys activity processes` 里的服务连接记录。

### 32.10 第四轮：输入栏与队列条按 dsh 收口（2026-10-29 晚，canary 覆盖安装 + 虚拟屏实测）

**用户要求**：插话与消息队列参考 dsh，去掉一堆单独占地方的小图标。

**参考对象（真源码，不是印象）**

- `QueueDock.tsx`：单条时不显示计数头，行内才带 lead 图标；多条时收起成一行计数，
  展开才列条目；面板 `radius lg lg 0 0`、`margin-bottom: calc(-gap - 3px)` 贴住输入卡、`max-height:180px`。
- `InputBar.tsx`：只有「+ 命令」「停止（仅运行中且主按钮不是停止）」「主按钮」；
  `canSteerQueue = !locked && !machineBusy && !commandMenuOpen && empty && running && steeringAvailable && queue.length>0`
  —— **输入框必须是空的**才允许插话。

**改法（对照 dsh，不照抄视觉）**

- `AgentChatInputBar.kt`：删掉运行中并排常驻的「暂停」「插话」两个 `IconButton`。流式中只留一个主按钮：
  空输入=停止（点击=停止、长按=暂停）；有草稿=「排队发送（本轮结束后发出）」（点击=入队、长按=打断并立即发送）；
  暂停中=继续（点击=继续、长按=停止）。长按语义全部走 `combinedClickable` + `onLongClickLabel`
  与 `Role.Button`，无障碍可读。
- `AgentChatQueueStrip.kt`：收起条件由「用户是否手动折叠」改为「是否只有一条」——
  `hasMultiple` 决定形态，单条不再套计数头（dsh 同构）；多条收起时**行内图标全部收进展开列表**，
  只留「排队中 · N」+ chevron；展开后每行「序号 + 预览 + 插话/删除」。

**同轮挖出并修掉的真缺陷：有草稿时点插话，白停一轮**

`drainQueueAfterRun` 的守卫是「草稿非空则不抢草稿位」（正确，消息绝不丢），
但 `steerQueuedMessage` 会**先** `requestInterruptCurrentRun()` 再等接力 —— 于是这条链路是：
点插话 → 打断当前 run → 收尾接力看到草稿非空整段跳过 → 队列条停在「插话中」，用户以为在插话。

改法：`steerQueuedMessage` 顶部加 `homeState.input.isNotBlank()` 拦截，弹
`chat_queue_steer_blocked_by_draft` 后 return（三语各一条）；队列条新增 `hasDraft`，
`steerEnabled = !isEditingMessage && !hasDraft` —— 与 §32.9 的编辑态置灰同一处口径。

**门禁与产物**

`ETA_APP_ID_SUFFIX=.canary STAGES=test,assemble sh tools/gate.sh` → 单测 **719 用例 / 0 失败**，
打包 `Eta-v3.3.5-debug.apk` **34,463,566 字节**（sha256 `3fbd404aeddad696…`，
md5 `7be4048e957f2cac42784f33b0cfbdb7`）。`merged_manifest` 里 `package="io.github.mangi.eta.canary"`。

**lint 口径（如实记录：转绿靠基线，不是靠修完）**

`STAGES=lint` 在 HEAD 上本来就是红的（43 条 / 41 errors，与本轮改动无关）。本轮只修了触及文件里的
4 条（`LocalContextGetResourceValueCall` 等），其余 **37 条既有告警按既有口径登记进
`app/lint-baseline.xml`**（+37 新增、−2 已消失；`tools/lint_baseline_audit.py` 的账目与文件一致，412 条）。
所以「lint 通过」的准确含义是「没有**新增**告警」，不是「零告警」。

**覆盖安装（未卸载，用户授权）**

`pm install -r -d` → `Success`；`lastUpdateTime` 21:05:10 → **23:01:24**，
`firstInstallTime` 始终 **16:35:18**；主包 `io.github.mangi.eta` `lastUpdateTime` 仍是 18:22:55 —— 没碰主包。

**真机实测（虚拟屏 display 5 / PKX110 / Android 16，全部本构建）**

| 场景 | 观测到的节点事实 |
|---|---|
| 运行中 + 空输入 | 输入栏右侧只有一个按钮，`desc="停止"`，`long_clickable=true` |
| 运行中 + 有草稿 | 两个按钮：`desc="停止"` 与 `desc="排队发送（本轮结束后发出）"`（后者 `long_clickable=true`） |
| 点「排队发送」 | 输入框清空；队列条出现：`排队中` + 预览文本 + `立即插话发送` + `移除排队消息` |
| 再排一条 | 收起为单行 `排队中 · 2`，预览与两个图标全部消失 |
| 点收起行 | 展开为 `1 / 2` 两行，各自带预览与「立即插话发送」「移除排队消息」 |
| 本轮自然结束（草稿已空） | 队首自动发出成聊天消息，队列减一，并起新一轮 |
| 本轮结束但草稿非空 | 队列**原样保留**（连续多轮都不发），清空草稿后的下一轮才发出 |
| 长按「停止」 | 主按钮变成 `desc="继续"`（真暂停，非停止） |
| 暂停中点「继续」 | 重试序列继续推进（1/4 → 4/4） |
| 有草稿时的「立即插话发送」 | `clickable=true` 的 View 容器**消失**，只剩裸 `ImageView`（点不动） |
| 清空草稿后 | 同一个节点又变回 `clickable=true` 的 View |
| 草稿为空点插话 | 当前 run 被打断（出现「已停止」→ 新回合 24/24），队列条目作为消息发出 |

**容易误判的一点（我自己在验证中踩到）**

测试时如果一直往输入框里打字，「队列自动发出」看起来像完全失效 —— 其实是设计：
`drainQueueAfterRun` 见草稿非空就整段跳过，避免抢用户正在输入的草稿位。要验证 auto-drain，
必须**把输入框清空**再看，否则会把正确行为误记成 bug。

**仍未验证（不要当成已验证）**

- 暂停中长按 = 停止；长按「排队发送」= 打断并立即发送 —— 两条只按语义接好，未在真机点过。
- `chat_queue_steer_blocked_by_draft` 的 Toast 可见性：按钮置灰后 UI 路径不可达，
  键盘/无障碍/恢复态入口在这台机器上没构造成 —— 仍是防御性代码（同 §32.9 的 edit 版）。
- 展开态里点预览正文「撤回输入框」未单独实测（单条形态下同一控件沿用 §32.8 的结论）。

**收尾**

虚拟屏 `vdmvto8o8x` 已释放（释放时连带移除屏上任务，避免被迁回主屏）；测试产生的排队消息均已发出、
输入框已清空；canary 保持安装，主包未动。

### 32.11 更正 §32.10：长按不是 dsh 的做法，改成显式按钮（2026-10-29 晚）

**用户质疑**：「长按？你没有参考 dsh 么？」——质疑成立。

**核查 dsh 真源码（`/workspace/dsh-work/dsh/packages/client`）**

- 全仓 `onLongPress` / `onLongClick` / `longPress` / `pressAndHold` **零匹配**；输入栏没有任何长按。
- 也没有「暂停运行」：`pause` 只出现在 goal 的暂停/恢复，不是 run。
- `ui-conversation/src/client/skeleton/InputBar.tsx`：
  - `primaryStops = running && subagent === null && (empty || blocked !== undefined)` —— 运行中且输入为空，主按钮就是**停止**；
  - 有草稿时主按钮是**发送**，排队还是插话由 `resolveSubmitMode(busyEnter, running, 'enter', steeringAvailable)` 决定，
    即 `ComposerSubmissionPolicy`（BusyEnterBehavior，用户设置），**不是手势**；
  - 独立 Stop 只在 `interruptible`（continuable 子代理）时出现；桌面另有 `useStopShortcut` 的 Esc 快捷键。
- 结论：§32.10 的「暂停/打断即发折叠进长按」是自创交互，不是 dsh。

**改法（用户选：保留暂停为显式按钮）**

- `AgentChatInputBar.kt`：`ChatHoldActionButton`（`combinedClickable`）→ `ChatActionButton`（普通 `clickable`），
  长按参数与 `combinedClickable` import 一并删除。
- 运行中固定两个显式按钮，按状态切换：

| 状态 | 左键 | 右键（主按钮） |
|---|---|---|
| 运行中 + 空输入 | 暂停 | 停止 |
| 运行中 + 有草稿 | 停止 | 排队发送（本轮结束后发出） |
| 暂停中 + 空输入 | 继续 | 停止 |
| 暂停中 + 有草稿 | 继续 | 排队发送（本轮结束后发出） |

  最后一行是**设计例外**：用户要求「有草稿时只有停止 + 发送」，但暂停中若收起「继续」，
  用户打了字就再也无法恢复（只能删草稿）。暂停中把左键留给「继续」，停止需要先继续或清空草稿。
- 删除三语 `chat_interrupt_send`（长按专用文案，已无引用）；`onInterruptAndSubmit` 参数保留但输入栏不再调用
  —— 「打断即发」现在走两步：先排队，再点队列条的「立即插话发送」。

**门禁与产物**

`ETA_APP_ID_SUFFIX=.canary STAGES=test,assemble sh tools/gate.sh` → 单测 **719 用例 / 0 失败**，
`Eta-v3.3.5-debug.apk` **34,461,638 字节**（md5 `37df2dd53625e58f…`）。

**覆盖安装（未卸载）**

`pm install -r -d` → `Success`；`lastUpdateTime` 23:12:41 → **23:21:30**，`firstInstallTime` 始终 **16:35:18**；
主包 `io.github.mangi.eta` 的 `lastUpdateTime` 仍是 18:22:55。

**真机实测（虚拟屏 display 6 / PKX110 / Android 16，全部本构建）**

| 状态 | 观测到的节点事实 |
|---|---|
| 运行中 + 空输入 | `desc="暂停"`（Button）+ `desc="停止"`；两者都没有 `long_clickable` |
| 运行中 + 有草稿 | `desc="停止"` + `desc="排队发送（本轮结束后发出）"` |
| 点「暂停」 | 左键变 `desc="继续"`，右键仍是 `desc="停止"` |
| 暂停中 + 有草稿 | `desc="继续"` + `desc="排队发送（本轮结束后发出）"` |
| 点「停止」 | 消息流出现「已停止」，输入栏回到空闲态只剩 `desc="发送"`（空输入时 disabled） |

**仍未验证**

- 暂停中「继续」恢复后的重试序列推进，本轮只看到按钮切换（上一轮 §32.10 验过继续能推进）。
- 队列条「立即插话发送」在草稿非空时仍按 §32.10 置灰（`hasDraft`），本轮没有重跑那条路径。

**收尾**

虚拟屏 `vdmvtoyd7g` 已释放（连带移除屏上任务）；测试草稿已清空、测试 run 已停止；canary 保持安装，主包未动。

### 32.12 繁忙时发送行为可配置：排队 / 插话（2026-10-30）

**dsh 依据**（`/workspace/dsh-work/dsh/packages/client/ui-conversation/src/client`）

- `settings/EnterBehaviorRow.tsx` + `src/submission-settings.ts`：`BusyEnterBehavior` 二选一，默认 `queue`；`locales.ts` 的标题就是「繁忙时的发送行为」。
- `input/submission-policy.ts` 的 `resolveSubmitMode(preferred, running, gesture, steeringAvailable)`：发送按钮与 Enter 共用 `enter` 手势，Ctrl/Cmd+Enter 取反。
- Eta 没有键盘回车提交，所以只作用于「运行中 + 有草稿」时点击发送按钮。

**实现**

- `agent/model/AgentBusySendMode.kt`：`QUEUE` / `STEER`，外加 `AgentBusySendPolicy.steers(streaming, paused, editingMessage, mode)` 这个纯函数。
- `Prefs.Keys.AGENT_BUSY_SEND_MODE`：字符串键，默认 `queue`；未知值回退，与 `AgentMode.fromId` 同一规则（手改配置不能让发送按钮变哑）。
- 设置页「聊天」组新增一行单选 `AgentBusySendModePreference`（默认排队），写入走 `commit()` 并在失败时提示（与 AgentMode 一致）。
- 输入栏主按钮：`steersNow` 为真时调 `onInterruptAndSubmit`（既有链路：入队 `immediateInterrupt=true` + `requestInterruptCurrentRun`），否则照旧 `onSubmit`（运行中 → 排队）。这个参数此前是死参数，现在接上了。
- **暂停态与编辑态一律排队**：暂停是 Eta 独有状态，插话会打断用户想保住的现场；编辑态插话会先打断 run、而编辑内容本身发不出去（§32.9 已记录同类缺陷）。
- 三语 strings 7 条（`settings_busy_send_*`、`agent_busy_send_*`、`chat_send_steer`）。

**单测 5 例**：默认值、id 往返、未知回退 queue、steer 的三个例外（暂停 / 编辑 / 空闲）、queue 永不 steer。

**顺带修掉的 lint 债**（本轮 lint 从「0 新增」变成 6 个错误，全部由工作区未提交改动引入）

| 位置 | 问题 | 修法 |
|---|---|---|
| `AgentGoalCard.kt:119/132` | `LocalContextGetResourceValueCall` | 改用 `LocalResources.current`（Configuration 变化后不会拿到旧值） |
| 三语 `goal_card_error_invalid` | `TypographyDashes` | `1-500` → `1–500`（en dash） |
| `AgentBusySendModePreference` | `UseKtx` | 抽成私有 `writeBusySendMode` + 局部 `@SuppressLint("UseKtx")`：必须拿到 `commit()` 的布尔结果才能提示写失败，KTX 的 `edit {}` 不返回 |

**验证**

- `STAGES=test`：**734 例 / 0 失败**（新增 5 例 busy send + 5 例 goal store）。
- `STAGES=lint,assemble`：lint 无新增问题，baseline 412 条一致；APK **34,493,550 字节**，sha256 `ea9043f5cdb55318…`。
- **真机已验证**：queue / steer / 设置切换即时生效 / 暂停态仍排队，证据见 §32.14。

### 32.13 目标卡接上 dsh GoalBar 的动作（2026-10-30，真机未验）

**背景**：dsh 的 GoalBar 让目标在卡片上就能暂停 / 继续 / 编辑 / 清除，不必再让模型调一次 `goal` 工具。

**实现**

- `AgentGoalStore.mutate(scopeId, expectedRevision, transform)`：同一把 `writeLock` 里重读 → 校验 revision → 写回；revision 不一致返回 `STALE`，不覆盖模型刚做的修改。`withLock` 让 `goal` 工具的读-校验-写与界面动作共用同一把锁。
- `AgentGoalCard`：行内「暂停 / 继续 / 编辑 / 清除」，行内错误 + 单飞（`AtomicBoolean`，同 dsh 的 `pendingRef`）；`LaunchedEffect(goal.id)` 作废残留编辑态，避免草稿写到新目标上。
- 三语 `goal_card_*` 文案。
- `AgentGoalStoreTest` 5 例：作用域隔离、原子替换不留 `.tmp`、只清本作用域、空白作用域不共享、revision 冲突不覆盖。

**验证**：734 例（含这 5 例）全绿，lint/assemble 通过。**真机未验**：卡片动作、行内错误、并发竞态目前只有单测与 lint 证据。

### 32.14 繁忙发送行为真机验证（2026-10-30，canary / PKX110 A16）

**环境**：`io.github.mangi.eta.canary` 以 `pm install -r -d` 覆盖安装（未卸载，`firstInstallTime` 保持 2026-10-29 16:35:18，主包 `io.github.mangi.eta` 的 `lastUpdateTime` 未变）。模型为 `lingzhan / deepseek-v4.1-flash`；该服务商当轮持续返回 HTTP 502/503，所以每轮都是「重试 → 失败」，但不影响本轮要验的发送路径。

**queue（排队）**

1. 运行中且输入框有草稿时，主按钮 `contentDescription` = 「排队发送（本轮结束后发出）」。
2. 点击后出现队列条：`排队中` + 正文 + `立即插话发送` + `移除排队消息`，输入框清空。
3. 本轮结束后队列条消失，该消息作为新一轮出现在对话里（回合 27/27 → 28/28），输入框为空。
   - 这一轮是 502 重试耗尽后结束的；drain 与正常结束走同一路径。

**steer（插话）**

1. 设置页改为「插话发送」后**未重启 App**，同一界面主按钮立刻变成「插话发送（打断本轮）」——设置监听即时生效。
2. 运行中（29/29，正在重试）点击该按钮，日志时序为：
   `Agent run stop requested: id=run-6044 state=running primary=true` →
   `Agent runtime stopped: id=run-6044` →
   `run_started`；界面旧轮标「已停止」，新消息成为新一轮（30/30）。即真打断，而非等旧轮自然结束。

**暂停态**：暂停后左侧按钮变「继续」，在设置为**插话**时主按钮仍为「排队发送（本轮结束后发出）」——符合「暂停保现场、不打断」。

**未验**：编辑历史消息（`editingMessage`）时是否仍排队，目前只有 `AgentBusySendPolicy` 单测；目标卡行内动作与行内错误仍只有单测与 lint 证据（见 §32.13）。

**现场**：设置已改回「排队发送」；canary 会话里留了两条测试消息与一段测试草稿「暂停态草稿」，均在测试包内。

### 32.15 「提交被拒」不再吞字：清空交给真正消费草稿的一侧（2026-10-30）

**现象**：`AgentChatInputBar` 主按钮在 dispatch 之前就 `textFieldState.clearText()`。而
`sendCurrentMessage` 在 `isCompacting`、`rejectWhileMessagesLoading()`、只读归档会话、
运行中编辑态这几条路径上**直接 return**：输入框已经空了，随后 `snapshotFlow` 把空串写回
`draftsByConversation`，权威草稿也变成空 —— 文字两处都没了，还没有任何提示。

**为什么不做"拒绝时回填"**：回填要写 `input + inputSyncVersion + 1`，但"点击时清空"会
随后发一次空串；`updateDraft("")` 一定晚于回填（同一调用栈之后的协程调度），于是把刚回填的
草稿又清掉一次。能不能保住字取决于调度顺序 —— 这种修法只是把偶发变成另一种偶发。

**修法（职责归位）**：输入栏只报告"用户点了发送"，不再负责清空；清空只由真正消费掉草稿的
一侧发出信号（`input` 归零 + `inputSyncVersion + 1`，输入框据此同步一次）：

| 消费路径 | 位置 | 本次改动 |
|---|---|---|
| 排队 / 插话入队 | `AgentMessageQueueReducer.enqueue` | 补 `inputSyncVersion + 1` |
| 同上（清权威草稿表） | `queueCurrentMessage` / `interruptAndSendCurrentMessage` | 补 `draftsByConversation.remove`，否则 `updateConversation` 的草稿校正会把文字刷回输入框 |
| 发送成功 | `launchConversationRun` 的 `state.copy(input = "")` | 补 `inputSyncVersion + 1` |
| 「保留下文」的编辑保存 | `sendCurrentMessage` 的 preserve 分支 | 补 `inputSyncVersion + 1`，并把 `previousInput` 立回权威草稿表 |

**代价与对策**：点到发送与输入框真正清空之间多了一帧，这一帧里主按钮看起来仍可点，连点两下
会把同一段文字发两遍（排队时排两条）。新增 `AgentSubmitDedupPolicy`：相同文本 + 400ms 窗口
判为误触；不同文本、超过窗口、时钟回拨一律放行（拦多了会表现为"点发送没反应"，比多一条更糟）。

**顺带清掉的真死代码**：`AgentAppState.steerCurrentRun` 与 `clearDraftInput` 无人调用
（全仓 grep 仅自身引用），是旧 steering 路径的遗留；连同只被它使用的 4 个 `chat_steering_*`
字符串一起删除（三语同步删，键集仍一致）。

**测试**：746 例 0 失败。新增 `AgentSubmitDedupPolicyTest`（5 例：首次/窗口内/超窗口/不同文本/
时钟回拨）；`AgentMessageQueueReducerTest` 新增 1 例钉住"enqueue 消费草稿必须抬同步序号"。
两个类均已进 `tools/gate.sh` 的 `FAST_TEST_FILTERS`。

**真机**：发送后输入框清空已在 §32.19.5 复验（33/33 → 34/34 → 35/35）；连点去重的两次实测见 §32.14 那一轮的记录。

### 32.16 collapseTiming：回合结束后工作过程什么时候收（2026-10-30）

**dsh 的模型**：`collapseTiming`（`completion` / `next-input`）与"工作细节档位"
（`transcriptView`）是两个独立设置 —— "看多少细节"和"什么时候收"是两件事
（`packages/client/ui-chat/src/chat-settings.ts`、`client/presentation-policy.ts`）。
`next-input` 的含义是：已完成的回合先保持实时形态，等用户真的发出下一条消息时再退回历史形态。

**Eta 的落地**：

- 设置键 `agent_work_collapse_timing`（字符串，取值与 dsh 同名：`completion` / `next-input`），
  默认 `completion`，未知值回退默认；**不放进** `BOOLEAN_DEFAULTS`。
- 设置页「聊天」组新增一行 `AgentWorkCollapseTimingPreference`（在"繁忙时的发送行为"之后），
  写着即生效（`rememberAgentWorkCollapseTiming` 监听同一个键）。
- 判定不引入任何新状态：`AgentConversationMessages` 反向扫一遍条目，算出"哪些工作过程块
  后面已经出现用户消息"（`workKeysWithLaterUserMessage`），交给纯函数
  `AgentWorkCollapsePolicy.autoExpand(timing, active, hasUserMessageAfter)`。
  切会话、重启、编辑/删除历史都自动一致，不需要记"刚才哪一块刚结束"。
- `AgentWorkProcess` 新增 `autoExpanded`（默认 `= active`，即原行为）；用户手点过的
  `manualExpanded` 与搜索命中展开仍然优先，语音浮层（`assistantOverlay`）依旧不自动展开。

**测试**：`AgentWorkCollapseTimingTest` 6 例（默认值、同名同值、未知值回退、两种时机下的
展开判定、正在跑的那一块永远展开）。

### 32.17 transcriptView：工作细节四档（2026-10-30）

**dsh 的模型**：`transcriptView` 不是「折叠开/关」一个开关，而是四档常用组合，每档由四个
独立维度展开（`packages/client/ui-chat/src/client/presentation-policy.ts`）：

| 档位 | foldCompletedTurns | stepGrouping | liveProcessDetail | settledReasoningPreview |
|---|---|---|---|---|
| compact | 是 | collapsed | 否 | 否 |
| standard | 是 | collapsed | 是 | 是 |
| detailed（默认） | 是 | history | 是 | 是 |
| verbose | 否 | none | 否 | 是 |

dsh 的 `normal` / `expanded` 是上一代两档取值：**接受但不再提供、也不再写回**，一律读作 `detailed`。

**Eta 的落地**（`agent/model/AgentTranscriptView.kt`）：

- 设置键 `agent_transcript_view`（字符串，与 dsh 同名同值），默认 `detailed`；`normal` / `expanded`
  读作 `detailed`，其余未知值回退默认。**不放进** `BOOLEAN_DEFAULTS`（同 `AGENT_BUSY_SEND_MODE`、
  `AGENT_WORK_COLLAPSE_TIMING` 的先例）。
- 设置页「聊天」组「回合结束后工作过程」之后新增一行 `AgentTranscriptViewPreference`（四选一单选）。
- 判定仍是纯函数 `AgentTranscriptPresentation`：

```kotlin
autoExpand = active -> true
          | (!policy.foldCompletedTurns && !active) -> true
          | else -> AgentWorkCollapsePolicy.autoExpand(timing, active, hasUserMessageAfter)

grouped = COLLAPSED -> true
        | NONE -> false
        | HISTORY -> !active && !(timing == NEXT_INPUT && !hasUserMessageAfter)
```

`autoExpand` 对「折叠时机」是**委托**而不是重写：两处各写一份判断，早晚漂移成「档位说展开、
时机说收起」。

- `AgentWorkProcess` 新增两个参数：`groupHeaderVisible`（false 时不画折叠行、正文恒显示，
  对齐 verbose 的 `stepGrouping=none`）与 `liveProcessDetail`（false 时运行中的折叠行只给
  「处理中 N 步」，不给工具名/命令/路径细节，对齐 compact）。

**没有落地的维度（诚实记录）**：`settledReasoningPreview`（已结束的推理行在标题旁预览首行）
在 Eta 没有对应结构 —— 折叠态只有一条整轮汇总行，没有独立的推理行标题。不为对齐硬造一个，
需要时再单独设计。

**四档在 Eta 侧的可观察差异**：

| 档位 | 观察点 |
|---|---|
| 紧凑 | 每轮收成一行；运行时只有「处理中 N 步」 |
| 标准 | 每轮收成一行；运行时带当前工具细节（改动前的观感） |
| 详细（默认） | 当前轮平铺（无折叠行），历史轮收束；`next-input` 下刚结束的轮保持平铺 |
| 完整 | 永不折叠、不画折叠行，每一步单独一行 |

**测试**：`AgentTranscriptViewTest` 11 例（默认值、同名同值、旧值读作 detailed、四档组合表、
运行中永远展开、completion 下三档收束而 verbose 不收、next-input 两种时机、collapsed 恒有折叠行、
verbose 恒无折叠行、detailed 只收历史轮）。已进 `tools/gate.sh` 的 `FAST_TEST_FILTERS` ——
这类「没进过滤列表就等于没跑」的坑本项目踩过四次，见该文件的注释。

### 32.18 只读归档会话「搬草稿」后清掉旧会话那一份（2026-10-30）

**缺陷**：在只读外部归档会话里发送时，`sendCurrentMessage` 先调 `moveCurrentDraftToNewConversation()`
把输入搬到新会话（`homeState = emptyChatState().copy(input = draft.input)`），但**旧会话的
`draftsByConversation` 与 `conversationsById[旧].input` 都没清**。

**后果**：新消息发出后，`launchConversationRun` 清的是新会话的草稿；用户点回那个只读归档会话时，
`updateConversation` 拿草稿表校正 —— 表里有值就把文字刷回输入框；本次修完「清空交给消费方」
之后表是空的，于是保留快照里的 `input`（仍是刚发出去的那段）→ **刚发出去的文字又出现在输入框里**。

**修法**：`moveCurrentDraftToNewConversation()` 里先记下 `from = selectedConversationId`，清掉
`draftsByConversation[from]`，并把 `conversationsById[from]` 的 `input` 置空。两处都要清：
表决定覆盖，快照决定直接显示。

**验证**：只有代码审查，**真机未验证**（§32.19.6 第 4 条：本轮没造出只读归档会话那条路径）。`AgentAppState` 依赖 Android `Context`，
本仓库单测跑的是纯 JVM（Robolectric 在本机 Linux/aarch64 跑不了），所以这条没有单测 ——
不写成「已单测」。

### 32.19 真机验证：四档 / 折叠时机 / 免重启（2026-10-30，canary · PKX110 A16）

**环境**：PKX110 / Android 16；canary `io.github.mangi.eta.canary` 覆盖安装于 11:24
（`firstInstallTime` 仍是 2026-10-29 16:35:18，主包 `lastUpdateTime` 未变）。设备上跑的是
**文案修正前**的那一版 APK —— 三条摘要字符串是之后才改的（见 §32.19.1），本轮没有再次覆盖安装。

#### 32.19.1 先修掉一处「文案说假话」

「详细」档的摘要原文是「刚结束的这一轮保持展开，直到你发下一条消息；更早的轮次仍然收起」。
这句话描述的是 **`collapseTiming=next-input`** 的行为，而档位与折叠时机是两个正交设置：
默认 `completion` 下 detailed 的回合结束后**立刻收起**，摘要与行为互相矛盾（真机上就是这样：
设置页写「保持展开」，聊天页一结束就收起）。

改成「运行中的这一轮步骤直接平铺；已经结束的轮次收进可展开的分组」（三语同步：英 / 简 / 繁），
与 `AgentTranscriptViewPolicy(DETAILED)` 的 `stepGrouping=history` + `foldCompletedTurns=true`
对齐 —— 它只描述档位本身，把「什么时候收」留给旁边那一项。

#### 32.19.2 验证 1：切档免重启（详细 → 完整 → 紧凑 → 标准）

| 操作 | 观察到的现象 |
|---|---|
| 起始「详细」 | 两条已完成回合各是一条折叠行：「思考 1 次 ／ 详情 ／ 展开工作过程」 |
| 改「完整」返回聊天页 | 折叠行**消失**，改为平铺：「思考已完成 · 用时 1 秒」+ 回答正文 |
| 改「紧凑」 | 折叠行回来（「思考 1 次」） |
| 运行中改「标准」 | 下一次观察折叠行标题已带工具细节（见验证 2） |

**未重启、未重进会话**，四次切换都是在设置页点完即返回聊天页看到的。这证实
`rememberAgentTranscriptViewMode` 的 `OnSharedPreferenceChangeListener` 在跨路由时生效 ——
设置页与聊天页是两个路由，靠重组时机是读不到新值的。

#### 32.19.3 验证 2：紧凑 vs 标准的差别（运行中）

同一回合（第 34 回合）在跑：

- **紧凑**：折叠行标题只有「正在分析任务」—— 没有工具名、没有命令。
- 运行中切到**标准**：标题变成「正在处理 · 第 1 步 · 终端 · 执行命令 · Android」。
- 第 35 回合（`sleep 15; uptime`）在标准下复现：「正在处理 · 第 1 步 · 终端 · 执行命令 · Android」。

即 `liveProcessDetail` 这一维在真机上确实只影响折叠行标题，不影响正文与步数。

#### 32.19.4 验证 3：`collapseTiming=next-input`

- 第 34 回合结束后：折叠行按钮仍是「收起工作过程」，步骤表（1/2/3 命令与输出）**可见** ——
  与 `completion` 的「结束即收」相反。
- 发送下一条消息、第 35 回合开始后：第 34 回合的按钮变成「展开工作过程」，步骤收起 ——
  正是 next-input「等下一句才收」。
- 全程未重启，也没有出现「切档后折叠时机失效」。

#### 32.19.5 其它现场事实

- 发送后回合计数 `33/33 → 34/34 → 35/35`，输入框清空 —— §32.15 的「清空归消费方」仍然成立。
- 设置页的摘要行随选择即时更新（「紧凑：…」「标准：…」「完整：…」）。
- 第 34 回合的正文里，模型把三步写成了一张「步骤 / 命令 / 结果」表（横向可滚），说明工具
  多条结果的呈现没有因为档位改动而退化。

#### 32.19.6 未验证（不写成已验证）

1. **改文案后的新文案渲染**：新文案已进源码并重新打包
   （`Eta-v3.3.5-debug.apk` 34,516,850 字节，sha256 `4b1d7ccf193f238104c335684d5f40ed47fc8d066b5ffe367c05f727a59b4baf`），
   但**没有再覆盖安装**，设备上看到的仍是旧文案（截图上「详细」档摘要仍是旧句）。
2. **无障碍可读性**：设置对话框里四个选项行在无障碍树里只暴露 `checked=false`，**当前选中项
   的行节点整个缺失**（只剩标题和摘要两段文本）。屏幕阅读器能否读出「现在是哪一档」未验证；
   `EtaRadioButtonPreference` 用的是 `Modifier.selectable(role=RadioButton)`，理论上会写
   `selected`，但抓到的树与理论不一致，需要专门查。本轮补验又复现两次（工作细节、折叠时机
   两个对话框都一样）。
3. **只读归档会话搬家清草稿（§32.18）**：本轮试过用 `am start -a android.intent.action.SEND`
   造路径，结果走的是「分享文案填进新会话输入框」，**没有进只读归档会话**；
   `openArchivedConversation` 要的是 `AgentRunArchiveStore` 里带 `source + conversationKey`
   的归档（语音助手交接），本机没造出来。仍只有代码审查。
4. **编辑历史消息排队**：只有单测（`AgentMessageQueueReducerTest` 的编辑入队用例）。

`steerQueuedMessage` 的 `hasDraft` 已补验，见 §32.19.9。

#### 32.19.7 测试过程本身的两个坑

- **坐标点击会落到别的应用**：两次 `tap`（同一个位置附近）之后前台变成
  `com.tencent.mm/…WxaLiteAppLiteUI`（微信的一个小程序页）。canary 进程一直活着（`pidof`
  有值）、crash 缓冲区没有新记录 —— 所以**不是 canary 崩了**。改用 `tap_element`（节点点击）
  之后没再出现。判断「现在谁在前台」不要看 `dumpsys activity`（里面同时有多条
  历史 `topResumedActivity`），要看 `dumpsys window | grep mCurrentFocus`。
- **`am start --es eta.route settings` 之后界面滚动会失效一次**：拿到的是旧的
  `observation_id`；重新 `observe_screen` 再滚即可，不是应用问题。

#### 32.19.8 本轮门禁与产物

- `STAGES=test,assemble`：**757 例 0 失败**；APK `Eta-v3.3.5-debug.apk` 34,516,850 字节，
  sha256 `4b1d7ccf193f238104c335684d5f40ed47fc8d066b5ffe367c05f727a59b4baf`（包名 `io.github.mangi.eta.canary`）。
- **lint**（2026-10-30 补跑）：`STAGES=lint` **通过** —— `BUILD SUCCESSFUL in 21m 21s`，
  `Lint found no new issues (405 errors + 7 hints filtered by baseline lint-baseline.xml)`；
  门禁自检输出「✓ baseline 账目一致：412 条命中」，`app/lint-baseline.xml` 仍是 413 条、未改动。
- 三语键集：本轮新增的 10 条 `agent_transcript_view_*` 与 6 条 `agent_work_collapse_*`
  三份文件齐备；文件里那三处历史差异（简繁缺 `speech_api_key` / `route_skills`，
  多三个 `browser_*`）在 `HEAD` 上就有，本轮没动。

#### 32.19.9 补验：详细档运行中平铺 / completion 免重启 / 插话按钮随草稿禁用

设置走的是 UI（不是手改文件），事后从 canary 自己的 prefs 里读到硬证据：

```
<string name="agent_transcript_view">detailed</string>
<string name="agent_work_collapse_timing">completion</string>
<string name="agent_busy_send_mode">queue</string>
```

| 验证项 | 操作 | 观察到的现象 |
|---|---|---|
| **详细档运行中平铺** | detailed + completion，发一条「两步」任务（第 36 回合） | 运行中没有折叠行：`思考已完成` / `终端 · 2 次` / `终端 · 执行命令 · Android` 直接铺开 |
| **结束即收（completion）** | 同一回合跑完（约 28 秒） | 收起为一条折叠行：`思考 1 次 · 调用 2 个工具` + `展开工作过程`（不再有步骤行） |
| **completion 免重启** | 第 35 回合在 `next-input` 下按钮是「收起工作过程」（展开）；进设置改 `completion` 后返回聊天页 | 同一回合立即变「展开工作过程」（收起）—— **未重启、未重进会话** |
| **更长回合仍然运行中平铺** | 第 37 回合 `sleep 45; uptime` | 运行中同样平铺，计时从 `3 秒` 走到 `35 秒`；结束后收成 `思考 1 次 · 调用 1 个工具` |
| **排队 → 自动发出** | 第 37 回合运行中点「排队发送（本轮结束后发出）」 | 队列条出现「排队中」+ 消息预览；第 37 结束后自动发出，`38/38` 完成，回答原样是 `OK` |
| **插话按钮随草稿禁用** | 队列条存在时：无草稿 → 输入「草稿A」→ 清空 | 无草稿：按钮容器 `clickable=true`；有草稿：**容器节点整个消失**，只剩 `desc=立即插话发送` 的 ImageView（Compose 对 `enabled=false` 的 IconButton 就是这个表现）；清空后 `clickable=true` 恢复 |

`hasDraft` 这条以前只有单测（`steerEnabled = !isEditingMessage && !hasDraft`），这次在真机上
用无障碍节点的「有没有 clickable 容器」判定，两个方向都抓到了。

#### 32.19.10 门禁脚本自己的两个缺陷（本轮顺手修掉）

1. **`MaxMetaspaceSize=512m` 覆盖了项目设置**：`tools/gate.sh` 的 `gw()` 用
   `-Dorg.gradle.jvmargs="-Xmx2000m -XX:MaxMetaspaceSize=512m"`，而 `gradle.properties`
   写的是 1g。第一次跑 `STAGES=lint` 在 9 分 5 秒后失败：`Unexpected failure during lint
   analysis of EtaApp.kt` → `OutOfMemoryError: Metaspace`（栈里是 lint 的 symbol 解析）。
   改成 1g 后重跑，daemon 实测 `-XX:MaxMetaspaceSize=1073741824`，21 分 21 秒通过。
2. **外部 `JAVA_HOME` 把 lint 带去 JDK 25**：脚本原写 `JAVA_HOME=${JAVA_HOME:-…jdk17}`，
   本会话环境带着 `java-25-openjdk`，于是 lint daemon 跑在 JDK 25 上（实测
   `/usr/lib/jvm/java-25-openjdk-arm64/bin/java`，21 分 21 秒）。AGP 8.x 支持的是 17/21，
   已改为**优先使用项目自带 jdk17**（`ETA_GATE_JAVA_HOME` 可显式覆盖）。JDK 17 下的耗时
   **没有对比数据**，下一轮可以做一次 A/B 再定论谁更快。

> 这两处改动只做了 `sh -n tools/gate.sh` 语法检查，**没有重跑整条门禁**：`STAGES=assemble`
> 会用 JDK 17 重新打包，可能改变 §32.19.8 记录的 APK 字节与 sha256（当前 34,516,850 /
> `4b1d7ccf…`），而那份产物就是本轮验证过的。下一轮跑 lint A/B 时再一并确认脚本改动可用。

**顺带查清 canary 的数据目录**：`/data/user/0/io.github.mangi.eta.canary` **不存在**，
`dumpsys package` 报的 `dataDir` 也不对路；真实目录是 `/data/data/io.github.mangi.eta.canary`
（`databases/eta.db`、`shared_prefs/eta_agent_preferences.xml`）。当前 shell 里
`ls /data/data | grep eta` 只看得到主包，但通过 `/proc/<canary_pid>/root/data/data/…` 能直接读。
后续要在真机上核对设置值，走 `/proc/<pid>/fd` 找打开的文件最省事，别在 `/data/user/0` 里找。


### 32.20 `ask_user` 的回答绝不能再进排队（2026-10-30，canary · PKX110 A16）

**用户反馈**：回答过一次之后，切走会话再切回来又要重答；而那个回答「居然放到了排队消息上」。

#### 32.20.1 根因

旧实现 `onAnswerAsk` 直接走 `InterruptAndSubmitMessage`：回答被当成一条「插话」塞进
`queuedMessages`（`immediateInterrupt=true`），队列条显示「插话中」，而
`drainQueueAfterRun` 只处理**选中的会话** —— 切走就再没有人把它发出去。
而待答卡片 `pendingAskOf` 只按消息流判定：回答还在队列里、不在 `messages` 里，于是
切回来卡片又出现，看起来就是「让你再答一次」。

第二轮补修时又挖出一条：`PendingAskAnswers.put` 在「已有待发回答」时**静默返回 false**。
第一条 pending 因为旧 run 迟迟不收尾而没发出去，用户再点一次 —— 界面毫无变化，
与「点了没反应」无法区分。现已改成**后一次选择生效**：`put` 返回被顶掉的旧回答，
界面把旧消息换成新消息并提示「已更新回答」。

#### 32.20.2 现在的路径

| 时机 | 行为 |
|---|---|
| 会话空闲 | 直接 `sendCurrentMessage(回答)`：一条普通用户消息 + 新一轮 |
| run 还在跑 | 先把回答落成**真正的用户消息**（`messages` + `persistConversations()`），再 `requestInterruptCurrentRun()`；同时把 `runId`/`messageId` 记进 `PendingAskAnswers` |
| 旧 run 收尾 | `saved.await()` 之后 `maybeLaunchPendingAskAnswer()`，用同一条 `messageId` 起新 run（与普通发送同一条路径） |
| 其它补接点 | `selectConversation`、`loadDeferredMessages` 成功、`RunResolution.NoActiveRun` |

关键不变量：**回答永远不经过 `queuedMessages`**。队列是「等这轮做完再说」的语义，
而回答是模型明确停下来要的那一句，两者不能混。

#### 32.20.3 真机验证（canary，虚拟屏）

| 场景 | 结果 |
|---|---|
| 空闲时点「甲」 | 卡片消失、用户消息「甲」立刻出现、`9/9`；打开会话列表切到别的会话再切回：「甲」仍在、**没有再出现待答卡片** |
| 运行中点「甲」（模型在 `ask_user` 后继续跑 `sleep 180`，底部仍是「停止」） | 点下即出现「甲」+「已停止」，卡片消失，**没有队列条**，`11/11`；约 4 秒后新 run 用「甲」起跑（`异步命令 · 等待完成`） |
| 切走再切回（运行中） | 「甲」与「已停止」都在，卡片不重现 |

#### 32.20.4 未验证（不写成已验证）

1. **进程被杀后重启**：回答已 `persistConversations()`，且 DB 里能查到 `user` 行，但本轮没有
   实际 `am force-stop` 再拉起核对界面。
2. **「后一次选择生效」的替换交互**：改完只跑了单测，没有重新构建装机点两次 —— 装机那份 APK
   还是「第一次被拒」的旧逻辑（也因此上面两条验证都不涉及替换提示）。

## 32.21 后台回来不再误报「未运行」+ 虚拟屏归属隔离（2026-10-31，本机 PKX110/A16）

用户报的两个问题：① 应用退到后台久了再回来，界面显示「未运行」，但会话其实还在跑；
② 虚拟屏是全局资源，另一个对话建的屏会被当成「自己的」——列表里混在一起，释放也不分归属。

### 32.21.1 运行状态误报的根因

`ui/app/AgentAppState.kt` 的 `attachRunningRun()` 里，`AttachOutcome.Unavailable` 分支在
`asPrimary=true` 时**直接把 `isStreaming` / `currentRunId` 清空**；而重试只挂在「下一次
`ON_RESUME`」。于是回前台那一刻只要撞上一次 Unavailable（后台期间看护通道未就绪、查询超时都算），
界面就退回「未运行」，之后再无补救 —— 与「会话还在跑」直接矛盾。

修复分两层：

| 层 | 内容 |
|---|---|
| 纯逻辑 | 新增 `AgentAttachRecovery.decide(outcome)`：`APPLY_RESULT` / `CLEAR_AND_RECONCILE` / `KEEP_RUNNING_AND_RETRY`，把「查不到 ≠ 没在跑」写成可测的判定 |
| 状态机 | Unavailable 时**保留运行态**，只把 `currentRunJob` 置空，并 `scheduleAttachReconcile()`：首次 1s、其后每 5s，最多 6 次，用 `queryActiveRuns() is Known` 判定；成功即 `recoverRuntimeRuns()`，`finally` 复位标志 |

单测 `AgentAttachRecoveryTest`（5 例）覆盖三种判定与重试窗口，已进 `tools/gate.sh` 的
`FAST_TEST_FILTERS`。

### 32.21.2 虚拟屏归属：轻量版隔离

不引入按会话配额，只把「谁建的」记下来、并让「默认看不到别人的」：

- `VirtualDisplayController.Session` 增 `ownerConversationId` / `ownerRunId`，`VirtualDisplayHub`
  记 `sessions` 并提供 `list/find/liveSession/lastOwnedBy/release/captureOf`；
- `VirtualDisplayOwnership.match()` 三态判定（纯逻辑，`VirtualDisplayOwnershipTest` 5 例）：
  有会话 id → 同会话 SELF、否则 OTHER；只有运行 id → 同运行 SELF、否则 OTHER；
  都没有（更早版本建的屏）→ **UNKNOWN，不冒充任何一方**；
- `virtual_display_list` 默认只列本会话，`others_count` 报数量；`include_others=true` 才全列并带
  `owner` 标记；
- `virtual_display_release` 跨会话需 `force=true`，否则返回 `VIRTUAL_DISPLAY_OTHER_SESSION`
  （带「创建于 N 秒前、用途 X」的理由）；`id=all` 只释放本会话的，并报 `skipped_others`；
- 预览面板按 `conversationId` 过滤，截图走 `captureOf`。

### 32.21.3 真机验证（canary 覆盖安装，虚拟屏操作，未动主屏）

安装：`pm install -r` 覆盖，`firstInstallTime` 仍是 2026-10-29 16:35:18（未卸载），
`lastUpdateTime` 08:17:58；装完的 `base.apk` 与构建产物 **sha256 一致**
（`513290219b0c2ede4f093ee5719a84ef0b2b0e61f8c520c4ce344d6cd23021ed`，32,134,289 字节）。

| 场景 | 实测结果 |
|---|---|
| 长任务 `sleep 100` 跑到一半 → 用另一个应用把 canary 压到后台约 55 秒 → 再切回 | 底部仍是「暂停／停止」，工具卡显示「终端 · 执行命令 · Android · 94 秒」；跑完为「执行完毕 / 状态 exited / 退出码 0 / 超时 false」——**没有出现「未运行」** |
| 新对话 A 建屏（`vdmvvon39x`，display 37，480×800）后用 `sleep 240` 吊住该轮 | 系统侧确认 `uniqueId="virtual:io.github.mangi.eta.canary,10617,eta-virtual-vdmvvon39x"` 存在 |
| 切到新对话 B 调 `virtual_display_list`（默认） | `count:0, mine_count:0, total_count:1, others_count:1, include_others:false` |
| 同对话 B 传 `include_others=true` | `count:1, others_count:1, include_others:true`（列出对方的屏） |
| B 直接释放（不带 force） | `{"ok":false,"code":"VIRTUAL_DISPLAY_OTHER_SESSION","message":"虚拟屏 vdmvvon39x 属于另一个对话（创建于 83 秒前，用途：跨会话验证-D1），本次未释放…"}` |
| B 带 `force=true` 释放 | `{"ok":true,"released":1,"id":"vdmvvon39x","closed_tasks":0}`；随后 `dumpsys display` 里 `eta-virtual-vdmvvon39x` **消失**，canary 名下虚拟屏计数 0 —— 是真销毁，不是只清记录 |

### 32.21.4 未验证 / 已知缺口（不写成已验证）

1. **Unavailable 分支本身没有在真机复现**：本轮 run 始终是同进程 `Attached`，所以现场验的是
   「后台回来不丢状态」，而 `KEEP_RUNNING_AND_RETRY` 那条补接路径只有单测覆盖。
2. **一块主应用遗留屏 `vdmvo7v77`（display 34）**：08:36:31 由主应用进程创建，但当前 Hub 里没有它，
   `virtual_display_release` 够不到；它随应用进程重启回收。**根因未确认**，本轮只记录，
   `release()` 里 `displays.remove(id)?.release()` 为空时仍返回成功的写法值得下一轮专门审。
3. **工具建的屏会被本轮收尾回收**：`VirtualDisplayController.close()` → `releaseAll()`，
   所以「跨运行存活」只在 controller 未关闭时成立（实测 canary 建完屏、该轮结束后屏即消失）。
   这与 Hub 注释的表述不一致，本轮未改语义。
4. **新建对话的瞬间**曾显示「▸ 虚拟屏 · 跨会话验证-D1」（别的对话的屏），发出第一条消息后消失；
   是否只是新会话尚未落库时的回退，未深究。
5. 面板里拖动／滚动手势在虚拟屏上仍不稳定（多次 `ACTION_OUTCOME_UNKNOWN`），本轮全部改用节点点击绕开。

### 32.21.5 门禁与产物

- `tools/gate.sh` `STAGES=lint,test,assemble` 全绿：**803 用例 0 失败**；lint 无新增问题，
  baseline 仍 412 条；打包成功。
- 产物：`app/build/outputs/apk/debug/Eta-v3.3.5-debug.apk` **32,134,289 字节**，
  sha256 `513290219b0c2ede4f093ee5719a84ef0b2b0e61f8c520c4ce344d6cd23021ed`；
  canary 装机包同源（`io.github.mangi.eta.canary`）。

## 32.22 终端 env.conf：值不进 argv，四种执行面都吃得到（2026-10-31，本机 PKX110/A16）

### 32.22.1 缺陷：值就在 `ps` 里

旧实现把 env.conf 的每一项拼成 `export KEY='value';` 塞进命令串，于是它成为 `sh -c` / `su -c`
的 argv。真机上 `ps -A -o pid,args` 能直接读到 `export Github_Token='…'` 与
`export ETA_PROBE_ENV='…'` —— 任何进程都能看，等于把密钥写在公告板上。
（文中不写具体值；该 token 应从「已泄露」处理，见 §32.22.6。）

### 32.22.2 方案：一次性 0600 脚本 + 单行 source/rm

| 项目 | 做法 |
|---|---|
| 载体 | 每条命令写一个 `.eta-env-<随机>.sh`，**0600**；目录 `env.d` 收紧到 **0700** |
| 下发 | 命令行只出现**路径**：`if [ -r '<脚本>' ]; then . '<脚本>'; else echo '<提示>' >&2; fi; rm -f '<脚本>'`（单逻辑行，无换行） |
| 生命周期 | source 后立刻删除；命令被强杀留下的文件由 `sweepStale()` 回收（>10 分钟，按目录 60 秒限流） |
| Android 落点 | `files/terminal-user/env.d`（root 与 App 身份都能读） |
| Linux 落点 | rootfs 内 `/tmp`，对 guest 引用 `/tmp/…`；`ownsDirectory=false`，**不动 guest `/tmp` 的 1777** |
| 覆盖面 | `terminal` exec / 常驻会话 / `terminal_job` / `terminal_daemon` / Linux 侧，均由 `envPrefixFor(environment, surface)` 统一产出 |
| 失败可见 | 写入失败 → 命令前加一行 stderr 提示（仍不回退拼值）；source 失败 → 另一行提示（见 §32.22.4） |
| 共用 | Agent 终端与用户终端都走 `TerminalEnvFile.effectiveEntries()`，含 Download 覆盖文件 |

### 32.22.3 真机验证（canary，虚拟屏 display 41，未动主屏）

用下载覆盖文件写探针变量 `ETA_PROBE_ENV`，让 canary 自己的 Agent 执行两条命令并回贴 stdout：

| 检查 | 实测 |
|---|---|
| `terminal` exec 取值 | `M=[eta_probe_9f3c]` ✓ |
| `terminal_job` start 后 read | `J=[eta_probe_9f3c]` ✓ |
| 落点与权限（canary 自报） | `ENVD 700 /data/data/io.github.mangi.eta.canary/files/terminal-user/env.d` ✓ |
| 收尾残留 | `left=0`（sourcing 完成后脚本已删）✓ |
| argv 是否含值 | 500 张 `ps` 快照（约 80 秒，覆盖整个执行窗口）：值 **0 命中**；`eta-env-` 脚本路径 **438 次** ✓ |
| 正向对照（证明快照真抓到了进程） | `30149 sh -c . '…/env.d/.eta-env-xls90f59i-8iqca.sh' 2>/dev/null; rm -f '…'`、`28419 sleep 12` ✓ |

即：命令行里出现的是**脚本路径**，值只活在 0600 文件里、source 完就没了。

### 32.22.4 本轮挖出的两个坑（都会骗人）

**坑一：扫描器自己命中自己。** 第一版扫描写的是
`grep -al eta_probe_9f3c /proc/[0-9]*/cmdline` —— grep **自己的 argv 里就有模式串**，
于是每轮都命中自己，`$()` 取回结果时该进程已退出，日志里只剩 `HIT /proc/N/cmdline` 加一行空内容。
900 轮刷出 435 条「命中」，全是假的。正确姿势：`ps -A -o pid,args` 打快照（pid 与 args 同在一行，
没有竞态），再 `grep -Ff 模式文件`（模式从文件读，不进 argv）。
教训：**用带模式串的命令去扫 argv，先看看它会不会扫到自己**。

**坑二：POSIX `.` 找不到文件会直接终止非交互 shell。** 第一版补充是
`. '<脚本>' 2>/dev/null || echo '<提示>' >&2; rm -f '<脚本>'` —— 单测立刻红了：
`||` 后面那段**根本不会执行**，因为 `.` 失败会让整个 shell 退出，`rm -f` 也轮不到，
用户命令静默不跑。改成
`if [ -r '<脚本>' ]; then . '<脚本>'; else echo '<提示>' >&2; fi; rm -f '<脚本>'`，
并加单测 `unreadableScriptIsReportedAndCommandStillRuns` 卡住「提示出现 **且** `after=ok` 仍然打印」。
这条只有真跑 shell 才会暴露，读代码看不出来。

### 32.22.5 门禁与产物

- `STAGES=test`：**819 用例 0 失败**（比上轮 +1，新用例即上面那条）。
- `STAGES=lint,assemble`：lint 无新增（baseline 仍 412），打包成功。
- 产物 `app/build/outputs/apk/debug/Eta-v3.3.5-debug.apk`：**32,142,769 字节**，
  sha256 `63e457895ba365c7b279523a3b8b5620132529d7788c4a51971bfe6965745499`。
- canary 覆盖安装（`pm install -r -d`，`firstInstallTime` 仍是 2026-10-29 16:35:18）：
  **32,142,805 字节**，sha256 `f3042455b89f39d92a7ac3ee5b298545c5ff86ba23794bf275cb800f401410d1`
  （与 `pm path` 下 `base.apk` 逐字节一致）。
- 换上新构建后再验一次（虚拟屏 display 42）：`M=[eta_probe_9f3c]`、`left=0`；同窗口 500 张 `ps` 快照
  里值 **0 命中**，且实际命令行已是 `… && if [ -r '<脚本>' ]; then . '<脚本>'; else …`，
  旧写法（`. … 2>/dev/null; rm -f …`）**0 次**。

### 32.22.6 未验证 / 必须提醒（不写成已验证）

1. **主应用 `io.github.mangi.eta` 仍是旧版本**：本轮在 `ps` 快照里实测到它把
   `export Github_Token='…'` 与 `export ETA_PROBE_ENV='…'` 拼进 argv（`/data/user/0/io.github.mangi.eta/…`）。
   覆盖安装新版本才会生效；因为安装会重启这个正在承载对话的应用，本轮**没有**在会话里执行。
2. **已经泄露过一次**：该 GitHub token 以明文出现在 argv 中，应视为泄露并**轮换**。
3. **文件 0600 只有单测覆盖**：真机上脚本存在的时间窗只有 source 前的一瞬，外部抓不到；本轮只验到目录 0700 与「收尾无残留」。
4. **Linux / proot 落点未上真机**：rootfs `/tmp` 是否可写、guest 是否真能 `source` 到，只有单测（`/tmp/.eta-env-` 前缀、不动 `/tmp` 权限）。
5. **常驻会话里的旧变量不会回滚**：env.conf 改完后，已开着的会话/已在跑的后台服务保留旧环境（界面提示里已写明，需重开会话或重启服务）。
6. **同类泄露已修（见 §32.23）**：`AcpClient` 启动 ACP 代理时把凭据写进 `su -c` 的命令串
   （`AcpClient.kt` 里 `providerExport = "export ${AcpProvider.ENV_KEY}=…"`，旁边注释写的「不会出现在
   宿主 ps 的命令行里」与实际不符——它就在 `su` 的 argv 里）。当时的结论是「修法与终端一致（往 rootfs 写 0600 脚本再
   source），但要往 AcpClient 里多接一个 rootfs 脚本提供者，并需要能真跑一次 ACP 委派才能验收」；
   本轮已改成同一套 rootfs 0600 脚本（§32.23）；仍未做到的只有「真跑一次 ACP 委派」，原因见 §32.23.4。

## 32.23 ACP 凭据也不进 argv：改走 rootfs 内一次性 0600 脚本（2026-10-31，本机 PKX110/A16）

### 32.23.1 缺陷：同一处坑的第二条链路

§32.22 修完终端后记过一笔：`AcpClient` 启动 ACP 代理时把凭据拼成 `export KEY='value';` 交给
`su -c`，于是值成为 `su` 的 argv，`ps -A -o pid,args` 直接可读。旁边那句注释
「凭据在**环境内** export：值不经过宿主 shell，也不会出现在宿主 ps 的命令行里」是错的 ——
它确实在 `su` 的命令串里。

### 32.23.2 改法

| 项目 | 做法 |
|---|---|
| 拆分 | `AcpLaunchEnv(marker, secrets)`：`ETA_ACP_SESSION`（收尾靠它扫进程树）**非机密、仍内联**；其余全部进脚本 |
| 落点 | 新增 `envScript` 回调 → `AgentLocalTools.wrapEnvForLinux` → `TerminalEnvInjection(File(rootfs,"tmp"), "/tmp", ownsDirectory=false)`，与终端 env.conf **同一套** |
| 命令行 | `export ETA_ACP_SESSION='…'; <单行 source/rm 片段>`，只出现脚本路径 |
| 失败即中止 | `envScript` 返回 null（rootfs 未就绪 / 写不进去）→ 抛异常、不启动；**绝不回退拼值** |
| 值不合法 | 含换行 / NUL / 超长 → 报出具体键名并中止（不在上游静默过滤，否则表现成「代理说没凭证」） |
| 同名键 | `distinctBy { key }` 保留第一次出现：provider 里配的 key 必须压过环境里的同名旧值（脚本按行 export，后写会覆盖前写） |

代码位置：`AcpClient.kt`（`AcpLaunchEnv` / `acpLaunchEnv` / `acpEnvPrefix` / `launch()`）、
`AgentLocalTools.kt`（`wrapEnvForLinux`）。测试：`AcpLaunchEnvTest`（6 例）。

### 32.23.3 真机验证（机制层，本机）

用与 `AcpClient` **同形**的启动链跑一条 15 秒命令：
`su -c '<busybox> chroot <rootfs> /usr/bin/env -i HOME=/root … PATH=… /bin/sh -lc '<payload>''`，
payload 里 source 脚本后打印 `KEYLEN`（只打印长度，不打印值）：

| 检查 | 实测 |
|---|---|
| 脚本权限 | `SCRIPT 600 root:root`（写在 rootfs `/tmp`） |
| guest 内 source | `KEYLEN=16`，与探针值长度 16 一致（值确实进了子进程环境） |
| source 后删除 | `LEFTOVER=0` |
| `/tmp` 权限没被改 | 仍 `drwxrwxrwt root:root`（1777） |
| argv 扫描 | 110 张 `ps -A -o pid,args` 快照：值 **0 命中**；脚本路径 114 次、`ETA_ACP_SESSION` 114 次 |
| 正向对照 | 快照里能读到 `… chroot <rootfs> /usr/bin/env -i … /bin/sh -lc 'export ETA_ACP_SESSION…'` |

这一轮同时补齐了 §32.22.6 第 4 条（Linux 落点未上真机）：rootfs `/tmp` 可写、guest 能 `source`、
宿主 `File(rootfs,"tmp")` 与 guest `/tmp` 是同一目录（inode 一致）。

**探针自己踩的坑（记录一下，别误判成产品缺陷）**：第一版探针的 chroot 命令没带 `PATH`，
于是 guest 里 `rm` / `sleep` 都 `not found`，脚本删不掉、`LEFTOVER=1`。真实 `ShellProcessSupervisor`
会通过 `/usr/bin/env -i … PATH=…` 显式给 PATH，所以这条只说明：**命令缺失时脚本会残留，靠
`sweepStale()`（>10 分钟）兜底**。

### 32.23.4 未验证（不写成已验证）

1. **没有真跑过一次 ACP 委派**：本机 rootfs 里没有 codex 类代理（`/opt/eta/node/bin` 为空、
   `acp_agent_list` 会报未安装），`delegate()` 在「代理可执行文件不存在」这一步就返回，
   走不到凭据下发。端到端（代理真的用 `ETA_CODEX_API_KEY` 拿到 key）仍缺，
   需要一台装了 codex-acp 的环境。
2. **ACP 凭据不读 env.conf**：`acpLaunchEnv` 的来源只有 `provider.apiKey` 与 **App 进程环境**
   （`System.getenv`）；`agents()` 的 `credentialPresent` 也只看这两者加代理自己的凭证文件。
   所以「在终端 env.conf 里配 `OPENAI_API_KEY`」不会让 ACP 用上它 —— 这是既有语义，本轮没改，
   但用户容易误会，写在手册里更合适。
3. **canary 没有 GUI 验证**：canary 数据目录当前不存在（装过但从未启动），本轮只核对安装与哈希；
   ACP 这条链路本来也点不出来（见第 1 条）。

### 32.23.5 门禁与产物

- `STAGES=test`：**825 用例 0 失败**（上一轮 819 里其实**不含** `AcpLaunchEnvTest` —— 它没进
  `FAST_TEST_FILTERS`，所以「绿」来自没跑到。本轮已加进列表，+6 例即 4+2）。
- `STAGES=lint,assemble`：lint 无新增，baseline 账目一致（**412 条命中**：405 errors + 7 hints）；
  打包 32,144,445 字节，sha256 `0917bcee0faa95c73d671cb6ba421e39e469a10ae9952ba47c015df427e6fd5b`。
- canary（`ETA_APP_ID_SUFFIX=.canary`）：32,144,481 字节，
  sha256 `4da510f101e3bd4093c5e4358ef48e31b080c7dfd2258b1b516856ddfe0d2e9e`；
  `pm install -r -d` 后 `firstInstallTime` 仍 **2026-10-29 16:35:18**，`base.apk` 与构建产物逐字节一致。

## 32.24 「越聊越卡」的真因：旧客户端投影是 O(n²) 序列化（2026-10-31，本机 PKX110/A16）

### 32.24.1 现象：新包装完，用户仍说卡

进程盘点（`ps -A` / `top -b`）：

- 只有一个 Eta 主进程（12:21 随覆盖安装重启），**没有旧实例残留**；
- 真正的“残留”是 3 个 Java 守护进程：Gradle 9.7.1（另一个项目留下的，RSS **1.6 GB**）、
  Gradle 8.9、Kotlin 编译 daemon。全部停掉后：空闲内存 **600 MB → 2.77 GB**，
  系统 idle **18% → 76%**。

但 Eta 自己仍有一个线程持续 **≈99% 单核**（785 ticks / 8s），系统整体 idle 76% 时它照样满。

### 32.24.2 定位：栈直接指到 JSON 序列化

`/proc/<pid>/task/*/comm` 找出线程名 `agent-result-io`；`kill -3` 抓到的
`/data/anr/trace_07` 里栈是：

```
AgentRuntimeService.sendResultNow
  → AgentRuntimeWire.toBundle
    → AgentLegacyConversationProjection.encode
      → Json.encodeToString / JsonToStringWriter.ensureTotalCapacity
```

读实现就明白了 —— 超预算时它做的是：

```kotlin
while (bounded.size > 1) {
    bounded.removeAt(0)
    encoded = json.encodeToString(listOf(notice) + bounded)   // 每删一条，整段重编码
}
```

**O(条数 × 总字符量)**：对话越长越慢，几 MB 的 transcript 就能把一个核跑满几分钟。
用户感觉到的“手机很卡”，在这个会话里主要就是它（RenderThread / GC 只是跟着忙）。

### 32.24.3 改法

| 项目 | 做法 |
|---|---|
| 长度预算 | 每条只 `encodeToString` 一次算长度；从尾部累加「条目 + 1 个逗号」定起点 |
| 编码次数 | 列表编码 1 次（正常路径）；预算误差最多再校验 8 次，**绝不回到每条重编码** |
| 语义 | 尾部优先、加压缩提示、跳过开头的 tool、单条超限压缩（与旧一致） |
| 空 / 全是 tool | 与旧一致返回 `[]` |

### 32.24.4 验证

- 新增 `AgentLegacyConversationProjectionTest` 4 例：不截断、超预算保尾部、
  尾部 tool 空投影、**2000 条 × 1 KB（2 MB）在 5 秒内**（旧实现约 2000 次全量重编码）。
  已加进 `FAST_TEST_FILTERS`。
- `STAGES=test`：**829 用例 0 失败**（上轮 825 + 4）。
- 首次误用 `--tests 'io.github.mangi.eta.agent.runtime.*'` 跑了整包：73 例失败，
  全是 `DefaultNativeRuntimeLoader`（Robolectric native runtime 不支持 Linux aarch64），
  与本改动无关 —— 又一次印证 gate 只收纯 JVM 用例的理由。
- 真机：安装后单独实测了该线程的 CPU（§32.24.5 第 3 条）。

### 32.24.5 还没治的 / 未验证

1. 只优化了**投影编码**。`toBundle` 仍每次重建 Bundle（O(结果大小)），只是不再是 O(n²)。
2. `AgentConversationCodec.encodeTranscriptForStorage` 每轮仍全量一遍（O(n)），长对话下是固定成本。
3. **真机已实测（2026-10-31 12:44 安装，sha `556661db…`）**：同一长对话下
   `agent-result-io` 从 **785 ticks/8s（≈99% 单核）** 降到 **0 ticks/8s**；
   该线程自 12:44 起累计 CPU 仅 **5.6 秒**（旧实现单次回传就要 200+ 秒）。
   同时停掉 3 个 Java 守护进程（Gradle 9.7.1 / 8.9 / Kotlin）：
   空闲内存 600 MB → 2.77 GB，系统 idle 18% → 76%。
4. 新包体积 **32,656,141 字节**，sha256
   `556661dbdf207af17254200b11820f9491177e1da846beaaebe46c06eb569c7c`；
   `pm path` 下 `base.apk` 与构建产物逐字节一致，`lastUpdateTime=12:44:05`。

### 32.24.6 复装核对（12:44 再装一次，确认装进去的真是新实现）

- `pm install -r -d` 返回 `Success`；User 0 的 `firstInstallTime` 仍是 **2026-10-03 10:38:00**
  （是覆盖安装，不是重装，数据没动）；`lastUpdateTime=12:44:05`。
- `pm path` 的 `base.apk` sha256 与构建产物一致（`556661db…`）。
- 直接查包里的 dex：`classes12.dex` 能搜到新方法名 `tailStartWithinBudget`，
  且 **搜不到** `AgentLegacyConversationProjectionTest` —— 装进去的是新实现，测试类没进包。
- 新旧包体积差 511,696 字节，但两边都是 **364 个条目**，未压缩总量只差 **1,144 字节**、
  压缩总量差 **451 字节**：差异来自 zip 打包顺序与对齐，不是功能代码（也解释了为什么
  `classes12.dex` 的 header 偏移能差 19 MB —— 条目被重排了）。
- 安装后 8 秒线程采样：`agent-result-io` **不存在**（0 ticks）；主要消耗是
  `RenderThread` 167 ticks/8s、主线程 116 ticks/8s —— 与旧实现里那个常驻满核的线程不是一回事。
- 仍未计时：**一次「发 3 MB 结果」的端到端耗时**。算法已不是 O(n²)，但具体几毫秒没单独测。

## 32.25 投影不再「序列化一遍再解析回来」（2026-10-31，本机 PKX110/A16）

### 32.25.1 剩下的固定成本

§32.24 把裁剪从 O(n²) 改成 O(n)，但 `encode` 开头还留着这段：

```kotlin
val bounded = AgentConversationCodec.decodeTranscript(
    AgentConversationCodec.encodeTranscriptForStorage(messages),
)
```

目的只是拿到 **sanitize 之后的对象**（图片正文、敏感工具参数要被替换掉），
却为此付了一次全量 JSON 编码 + 一次全量解析：长对话 30 MB 就是几十 MB 的中间字符串、
再展开成一整份对象图。每次结果回传都会走一遍。

### 32.25.2 改法

- 新增 `AgentConversationCodec.sanitizeTranscript(messages)`：直接 `map(::sanitizeMessage)`。
  与旧往返等价 —— 两个方向都只搬运 DTO 已有字段，内存里的对象本来就没有未知字段。
- `AgentLegacyConversationProjection.encode` 改用它；`encodeTranscriptForStorage` 原样保留
  （写存储/传历史仍然需要 JSON 文本）。

### 32.25.3 验证

- 新增 `sanitizePathMatchesJsonRoundTrip`：对带 `toolCallId` / `reasoningContent` /
  `toolCallsJson` / `contextSummary` / `compactedUserTurns` / `messageId` 的消息，
  断言 `decodeTranscript(encode(x)) == sanitizeTranscript(x)`。清洗逻辑一旦分叉会立刻红。
- `AgentConversationCodecTest`（5 例，含「图片观察不落 base64」「IPC 投影有硬预算且不以孤儿 tool 开头」）
  此前**不在门禁白名单**，本轮纳入；试跑 5/0。
- 门禁 `STAGES=test`：**835 用例 0 失败**（§32.24 的 829 + codec 5 例 + 等价性 1 例）。

### 32.25.4 未验证

- 真机端到端耗时仍未计时。这一轮省掉的是「每次回传一次全量编解码」，
  属于可以从代码结构直接确认的收益，但**没有秒表数字**，不冒充实测。

### 32.25.5 安装核对（13:11:34 覆盖安装）

- `pm install -r -d` → `Success`；`base.apk` sha256 `67f32333…` 与构建产物逐字节一致；
  User 0 的 `firstInstallTime` 仍 **2026-10-03 10:38:00**（覆盖，数据未动）。
- events 日志：13:11:34 安装杀掉旧进程 13595 → 13:12:16 起新进程 23198，
  说明当前跑的是新包（`/proc` 的 starttime 换算与 wall clock 有偏移，以事件顺序为准）。
- 直接查 dex：`classes4.dex` / `classes12.dex` 里都能搜到 `sanitizeTranscript`，
  测试类不在包里 —— 装进去的确实是本轮实现。
- 体积 33,733,212 字节 vs 上一版 32,656,141：条目数同为 364，未压缩总量只差 **472 字节**
  （`classes4.dex` +456），差异在打包布局与签名块，不是代码。
- 安装后 8 秒线程采样：`agent-result-io` **0 ticks**（线程在，但不耗 CPU）；
  `RenderThread` 253 ticks/8s、主线程 242 ticks/8s —— 界面在流式渲染时的正常开销，
  与 §32.24 那个「常驻满核」不是一回事。
- 仍然**没有**测「一次回传的端到端耗时」；要测它得在发送路径上埋点，属于下一轮的事。

## 32.26 把「回传到底多慢」变成一行日志（2026-10-31，本机 PKX110/A16）

### 32.26.1 为什么做这个

§32.24 和 §32.25 都是「从代码结构上确定更快」，但**没有秒表**。真出事时还是得
`kill -3` 抓栈、翻 `/data/anr/trace_*` 才知道卡在哪个方法 —— 这不适合日常判断。
所以补两个计时点，把长对话的成本变成可以直接 grep 的一行。

### 32.26.2 埋点

| 位置 | 日志行 | 说明 |
|---|---|---|
| `AgentRuntimeClient` 构造请求 | `request bundle: build=Xms messages=N` | 用户按下发送后，整个历史序列化成 JSON 的耗时 |
| `AgentRuntimeService` 回传结果 | `result delivery: wait=Xms build=Yms messages=N chars=C` | `wait` = resultIo 单线程队列排队；`build` = 两次编码（完整 JSON + 投影）总耗时 |

`wait` 单独记的理由：`resultIo` 是单线程，若一次回传要 800ms，后面排队的会被拖住，
只看 build 会漏掉「排队」。

### 32.26.3 不改变行为

只加了两处 `SystemClock.elapsedRealtime()` 和一行 `Log.i`：不开线程、不改协议、
不改 Bundle 内容。字符数是 `transcript` 四个文本字段求和，O(条数)，相对编码可忽略。

### 32.26.4 怎么用

```
logcat -d -s Eta:* | grep -E "request bundle|result delivery"
```

### 32.26.5 已验证 / 未验证

- 编译与单测：`STAGES=test` **835 用例 0 失败**。
- 真机数字：**要等装包后跑一次真实对话**才有；本轮不预先写假数据。

### 32.26.6 真机第一组数字（13:32 装包，14:00 的对话）

安装核对：`Success`，`base.apk` sha256 `00f4aae1…` 与产物一致，User 0 的
`firstInstallTime` 仍是 **2026-10-03 10:38:00**，`lastUpdateTime=13:32:49`。

装完第一次真实对话直接抓到：

```
14:00:46.853  request bundle: build=119ms messages=267
14:00:41.950  result delivery: wait=0ms build=7ms messages=2 chars=1063
14:00:42.111  result delivery: wait=0ms build=0ms messages=0 chars=0
```

读法：

- **267 条历史 → 119 ms**：这是按下发送后、请求真正发出去之前的固定等待，
  每轮都要付。约占「用户感知延迟」的大头之一（另一个是模型本身）。
- **回传 2 条 → 7 ms**：这一轮 run 的 transcript 只有 2 条，不是长对话样本，
  所以只能说明「短结果几乎不花时间」，**不能**用它推断上千条的耗时。
- `wait=0ms` 说明 resultIo 没有排队堆积 —— 目前没看到「前面的回传把后面的堵住」。

要评估长对话的真实代价，需要一次 transcript 上千条的 run；这组样本只能作为**基线**。

## 32.27 历史传输改成流式写读：不再整块 `toByteArray`（2026-10-31，本机 PKX110/A16）

### 32.27.1 起因（有数字了才动手）

§32.26.6 的实测：**267 条历史 → `request bundle: build=119ms`**。翻实现，
`AgentWireText.put` 的路径是：

```
历史 JSON 字符串（本机实测最大 445,117 字符）--toByteArray--> 再一份同尺寸 byte[] --write--> 临时文件
```

读端是反过来的：`ByteArray(size)` 一次性申请同样的字节数，再 `toString(UTF_8)` 生成字符串。
一次请求/回传，堆上各多出一份完整的整块副本，越大越容易触发 GC。

> **更正（§32.28.5）**：本节初稿写的「约 30 MB」没有任何日志支撑，实测最大 445,117 字符。

### 32.27.2 改法

| 方向 | 旧 | 新 |
|---|---|---|
| 写 | `text.toByteArray()` 后 `write(bytes)` | `bufferedWriter(UTF_8).use { it.write(text) }` |
| 长度 | 用 `bytes.size` | 用 `file.length()`（中文 3 字节，比按字符数估更准） |
| 读 | `ByteArray(size)` + 循环读满 | `CountingInputStream` + `reader(UTF_8).readText()` |
| 校验 | 读满 + 末尾 EOF | 不变：`count == size` 且末尾 `read() == -1` |

上限保护也保留：写完后仍 `require(size <= 64MB)`，超限直接失败、原记录不动。

### 32.27.3 顺带改的日志

`request bundle` 加 `chars=`（下面这行是 14:39 的真实日志）：

```
request bundle: build=130ms messages=333 chars=445117
```

理由：对话每轮都在变，只比 `messages` 或只比毫秒数都不严谨；有字符总量才能归一
（例如「ms / 百万字符」），否则「改快了」只是感觉。

### 32.27.4 验证

- `STAGES=test`：**835 用例 0 失败**。
- `AgentHistoryRetentionTest`（6 例，含 descriptor 长度不匹配必须抛错）在本机 **跑不了**：
  `DefaultNativeRuntimeLoader` 不支持 Linux aarch64（Robolectric 限制），与本次改动无关。
  所以流式路径**没有**本机单测覆盖，只能靠真机端到端。
- 真机：装包后看 `request bundle` 的 ms/chars，以及对话是否正常（能正常往返即说明读写正确）。

### 32.27.5 取舍

`readText()` 在 0.4 MB 级历史时是 StringBuilder 扩容，CPU 有可能会略高于一次性 decode；
换来的是少一次同尺寸 `byte[]` 峰值。**净收益要看真机数字**，不预设结论（§32.28.3 是第一组）。

## 32.28 130 ms 花在哪：给请求序列化分段（2026-10-31，本机 PKX110/A16）

### 32.28.1 先承认一组**不能直接对比**的数字

| 时间 | 包 | 日志 |
|---|---|---|
| 14:00 | 改前（整块 `toByteArray`） | `build=119ms messages=267`（当时还没有 `chars`） |
| 14:39 | 改后（流式写读） | `build=130ms messages=333 chars=445117` |

条数、字符数都不同，旧样本又没有 `chars`，所以**既不能宣称变快，也不能宣称变慢**。
唯一能算的是量级：445,117 字符 / 130 ms ≈ **3.4 MB/s**，而 kotlinx.serialization
正常能到几十 MB/s —— 说明这 130 ms 里**大头很可能不在 JSON 编码本身**。

猜测没有意义，所以先分段。

### 32.28.2 新增的分段日志

`requestBundle` 里把「编码」和「落盘」拆开：

```
request history: encode=Xms write=Yms chars=N
```

- `encode` = `encodeTranscriptForStorage`（sanitize 的 `map` + JSON 序列化）
- `write` = `AgentWireText.put`（>16 384 字符时写临时文件并打开 FD）

与 client 侧 `request bundle: build=` 的差值，就是其余字段、图片准备和 Bundle 组装。

### 32.28.3 真机第一组分段数字（15:00，安装后首次请求）

装包核对：`base.apk` sha256 `1afb1ab5…` 与产物逐字节一致，User 0 的
`firstInstallTime` 仍是 **2026-10-03 10:38:00**（未重置），`lastUpdateTime=14:51:30`。

```
15:00:13.860  request history: encode=5ms write=2ms chars=59756
15:00:13.875  request bundle:  build=22ms messages=51 chars=48942
```

三个数字**不是同一口径**，不能相互加减：

| 数字 | 含义 |
|---|---|
| `chars=59756` | 历史 **JSON 字符串**长度（编码后） |
| `encode=5ms` | `sanitizeTranscript` + JSON 序列化 |
| `write=2ms` | 写临时文件（>16 384 字符必走文件，尺寸用 `file.length()`） |
| `chars=48942` | 历史四个字段**原始长度之和**（`content`+`contentJson`+`reasoningContent`+`toolCallsJson`），不含 role 与 JSON 元字符 |

- 51 条 / 59,756 字符：**encode 5 ms + write 2 ms = 7 ms**，约占 `build=22ms` 的三分之一。
- 其余 15 ms 不是历史：`prompt`、屏幕上下文、模型会话 id、自定义头/体与 Bundle 组装都在这里
  （图片 `prepare` 在 `build` 计时之外）。**单样本不能判定它是稳态还是首次调用的冷启动** ——
  类加载与 JSON 序列化器的首次初始化都落在这一次里。
- 归一化：59,756 字符 / 5 ms ≈ **12 M 字符/秒**的编码速度，与 kotlinx.serialization 的正常量级一致，
  说明这条路径本身没有异常。

### 32.28.4 与 130 ms 那次对不上，差在哪

14:39 的样本是 **333 条 / 445,117 字符**。按上面 12 M 字符/秒线性外推，`encode` 约 37 ms；
`write` 按 2 ms/59 KB 外推约 15 ms —— 合计约 52 ms，**仍解释不了 130 ms**。

两种可能，都**还没有证据**：

1. 摊还假设不成立（大字符串触发一次完整 GC，或临时文件首次落到慢区）；
2. 大头不在历史，而在其余字段的 Bundle 组装 —— 59 KB 样本里那 15 ms 就是它的下限。

要区分，只能再抓一次「历史 ≥ 300 KB 且同一进程内非首次」的样本。拿到之前本节不给结论，
也不据此改代码。

### 32.28.5 对 §32.27.1「约 30 MB」的更正

§32.27.1 与 §32.27.5 原写「历史 JSON 字符串（约 30 MB）」。这是**错的**：当时没有任何字符量
日志，30 MB 是把 `messages` 条数当量级拍出来的，与实测（最大 445,117 字符，UTF-8 字节约
0.4~1.3 MB）差了两个数量级。已按实测改写并保留这处更正 —— 性能文档里一个凭空的数量级，
会让后面所有取舍都建在错的尺度上。

### 32.28.6 还没做的

- 缺一次「历史 ≥ 300 KB 且非首次调用」的样本（§32.28.4）。
- 若届时 `encode` 占大头，下一步是减少编码量（增量化 / 只编码变化部分）；
  若 `write` 占大头，才轮到文件 I/O；若都不在，去查 `prepare` 与 Bundle 组装。
