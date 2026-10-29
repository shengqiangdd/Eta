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
