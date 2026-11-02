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

## 32.29 每次请求都在算一份没人读的历史（2026-10-31，本机 PKX110/A16）

### 32.29.1 怎么发现的

顺着 §32.28 的「其余 15 ms」读 `requestBundle`，发现它在写完 `history_json`（走文件描述符）
之后，**又**构造了一份旧客户端兼容载荷：

```kotlin
putParcelableArrayList(
    KEY_HISTORY,
    ArrayList(AgentConversationCodec.decodeTranscript(
        AgentLegacyConversationProjection.encode(request.history, DIRECT_CHARS)
    ).map { ... Bundle() ... }),
)
```

读端是 `AgentWireText.read(bundle, "history_json") ?: bundle.getParcelableArrayList(KEY_HISTORY)`。
而 `AgentWireText.put` **无论内联还是走文件都会写上 `history_json` 这个键**（内联 `putString`、
超限则 `putXxx_fd`），所以只要请求是本版本客户端发的，`KEY_HISTORY` 那一支**永远取不到**。

代价是每次发消息都要额外做：

1. 对同一份历史**再做一遍** `sanitize` + 全量 JSON 编码（§32.25 刚删掉的同类往返，这里还留着一份）；
2. 把刚编码出来的字符串**再解析回来**（`decodeTranscript`）；
3. 拆成 N 个 `Bundle`，塞进 `ParcelableArrayList` 内联走 Binder —— 投影上限 `DIRECT_CHARS = 96_000`，
   Parcel 用 UTF-16，最坏约 **190 KB 进 Binder 事务**。

### 32.29.2 改法

- `requestBundle(..., includeLegacyHistory: Boolean = false)`；正常请求不再构造 `KEY_HISTORY`。
- `toLegacyBundle()` 传 `includeLegacyHistory = true` —— 它本来就只服务「兼容旧客户端 / 协议测试」，
  实际调用点只有 `MSG_UPDATE_MODEL`（历史为空）与测试。
- **读端兜底原样保留**：旧客户端（只写 `KEY_HISTORY`）发来的请求仍能解析，回退路径不会被砍掉。

为什么可以只改写端：`AgentRuntimeService` 与入口在同一 APK、同一版本一起升级；旧 service 进程
在 `pm install` 时已被杀。协议测试改走 `toLegacyBundle`，仍覆盖旧格式。

### 32.29.3 预计收益（**推算，未实测**）

§32.28.3 的 59 KB 样本：`build=22ms`，其中 `encode=5ms`、`write=2ms`，其余 15 ms 归「其它字段 +
Bundle 组装」。本次删掉的正是那 15 ms 里的一部分：

| 项 | 59 KB 时的粗算 |
|---|---|
| 投影再 sanitize + 编码 | ≈ 5 ms（与主编码同量级） |
| 再解析回对象 | ≈ 2~3 ms |
| N 个 Bundle + Binder 内联 | ≈ 2~3 ms |

合计约 10 ms，但**这是逐项相加的估算，没有单独计时**。真实收益要看装包后的
`request bundle: build=` —— 若它掉到 10 ms 出头，说明这 15 ms 主要就是这块；若几乎不动，
说明剩下的时间在别处（`prepare`、Messenger 编组、类加载），那就要换方向。

### 32.29.4 验证要求（缺一不可）

1. `STAGES=test` 用例数不降、失败 0（本轮：见 §32.29.5）。
2. 装包后发一条真实消息：`request history:` 仍在 → 写端没坏。
3. **回答仍引用上文** → 读端确实读到了 `history_json`。这一条不能省：读端读空列表不报错，
   表现是「静默失忆」，只测「有没有报错」是测不出来的。
4. `request bundle: build=` 与 §32.28.3 的 22 ms 对照。

新增单测 `modernRequestKeepsLegacyHistoryOffTheWireButStillRestoresHistory`（`AgentRuntimeWireTest`）
同时钉住两半不变量：新请求不带 `history` 键但能还原历史；`toLegacyBundle` 仍内联且能往返。
该类是 Robolectric 测试，本机 aarch64 跑不了（同 §32.27.4），所以它进不了门禁，只能作为
将来在有 JVM 图形环境的机器上的护栏 —— **本轮的证据仍以真机日志为准**。

### 32.29.5 门禁

- `STAGES=test`：**835 用例 0 失败**（白名单 0 个）。新增用例本身是 Robolectric，本机不跑，见 §32.29.4。
- `STAGES=lint,assemble`：见 §32.29.6（装包前的构建记录）。

### 32.29.6 真机结果：改完第一次请求就拿到 302 KB 的样本

装包核对：`base.apk` sha256 `2541f05a…` 与产物一致，`firstInstallTime` 仍是 **2026-10-03 10:38:00**，
`lastUpdateTime=15:17:25`。

```
15:18:27.897  request history: encode=22ms write=3ms chars=302031
15:18:27.898  request bundle:  build=26ms messages=184 chars=261180
```

这次的历史比 §32.28.3 那次**大 5 倍**（302,031 vs 59,756 字符），`build` 却只多了 4 ms。
把「历史以外」的部分单独拿出来看：

| 包 | 历史 JSON | encode | write | build | **build − encode − write** |
|---|---|---|---|---|---|
| 改前（§32.28.3） | 59,756 字符 | 5 ms | 2 ms | 22 ms | **15 ms** |
| 改后（本节） | 302,031 字符 | 22 ms | 3 ms | 26 ms | **1 ms** |

历史大 5 倍、这一项反而从 15 ms 掉到 1 ms —— 被删掉的 legacy 载荷（再造一份投影 + 解析回来 +
N 个 Bundle 内联进 Parcel）就是那 15 ms 的主要内容，量级对得上。归一化也是同一方向：

- 改前 445,117 字符 / 130 ms ≈ **3.4 M 字符/秒**
- 改后 302,031 字符 / 26 ms ≈ **11.6 M 字符/秒**

### 32.29.7 保留意见（别把话说满）

1. 两次样本**不是同规模、也不是同一次启动**：改前那次是安装后首次请求（含类加载与 JSON
   序列化器首次初始化），改后这次是进程内请求。所以「15 ms → 1 ms」里可能混有冷启动成分。
   方向与量级一致，但严格归因还差一个「改前代码 + 非首次」的对照 —— 包已被替换，拿不到了。
2. 445,117 字符 / 130 ms 那次仍有**未解释的部分**：按改后 13.7 M 字符/秒，445 KB 的主编码
   应约 32 ms，加 `write` 约 5 ms，仍不到 130 ms 的一半。那只样本出在 §32.27 之前的版本，
   当时的整块 `toByteArray` 会额外产生一份同尺寸字节数组，可能触发了 GC —— **推测，未验证**。
3. 样本量仍是 1。要断言稳态，至少再抓两条。

### 32.29.8 下一步（按现在的数字排序）

改后 `build` 里 **25/26 ms 是 `encode`+`write`**，也就是历史编码本身成了唯一大头：

- `encode=22ms / 302KB` ≈ 13.7 M 字符/秒，是 kotlinx.serialization 的正常量级，不是异常；
  要降只能**减量**（增量编码 / 缓存每条的 JSON 片段），而不是「优化写法」。
- `write=3ms` 里还包含一次 302 KB 的 UTF-8 编码与落盘；可以改成让序列化器**直接写流**
  （`encodeToStream`），省掉中间那个 302 KB 的 `String` 与一次全量编码拷贝 —— 但按现在的
  比例最多省几毫秒，先不动。

### 32.29.9 一个「空结果被当成通过」的隐患（安装脚本）

安装记录本身是完整的：`START 15:17:24` → `Success` → `base.apk` sha256 `2541f05a…` 与产物一致
→ `DONE 15:17:30`。但脚本里那段「dex 字符串（新代码标记）」**一行输出都没有**：

```
--- dex 字符串（新代码标记）---
--- 测试类是否误入包 ---
0
```

初版把原因写成「管道在 busybox 下没命中」—— **这个归因是错的**（见 §32.32）：真正原因是循环的列举命令用了
`unzip -Z1`，而设备上的 unzip 是 BusyBox 版，**根本不支持 `-Z`**（`invalid option -- Z`），
于是 `DEXES` 为空、循环一次都不跑、`HIT` 恒为 0。也就是说：**这次「dex 里有新代码」
实际上没被脚本验证过**，是我另外单独验的。

如果没人复查，这种「没有输出」很容易被读成「没问题」。核对脚本的正确写法是
**命中数为 0 就报错退出**，而不是只在命中时打印。这条先记下来，下次写安装脚本按这个改。

## 32.30 复跑一条：稳态确认，账只剩「编码」（2026-10-31，本机 PKX110/A16）

### 32.30.1 第二组样本（同一进程，非首次）

```
15:38:48.726  request history: encode=20ms write=3ms chars=335304
15:38:48.728  request bundle:  build=24ms messages=206 chars=290000
```

与 §32.29.6 那次并排：

| 时间 | 历史 JSON | 条数 | encode | write | build | build − encode − write |
|---|---|---|---|---|---|---|
| 15:18:27 | 302,031 字符 | 184 | 22 ms | 3 ms | 26 ms | **1 ms** |
| 15:38:48 | 335,304 字符 | 206 | 20 ms | 3 ms | 24 ms | **1 ms** |

两条都是「历史以外 1 ms」。**§32.28.3 那 15 ms 的口径到此可以关掉**：改完后这一项不随历史增长，
也不再有 10 ms 量级的固定开销。顺带把 §32.29.7 的第 3 条（样本量只有 1）了结一半 ——
两次不同规模、同一进程、结果一致；但**仍然没有「改前代码 + 非首次」的对照**（包已换），
所以「15 ms 全是 legacy 载荷」仍是强推断而非严格证明。

归一化：335,304 字符 / 24 ms ≈ **14.0 M 字符/秒**（与 §32.29.6 的 11.6 M 同量级）。

### 32.30.2 客户端这半边已经查完，还有服务端那半边

上面的 `encode`/`write`/`build` 全在**入口进程**。历史还要跨 Binder 交到 Runtime 进程，
由 `runRequestFromBundle` 读回并 `decodeTranscript` 解析 335 KB —— 这段此前没有任何计时，
而它在用户「按下发送 → 模型开始回」的路径上。

所以加了一行（本轮）：

```
run request: ingest=Xms history=N chars=M
```

`ingest` 覆盖 `AgentRuntimeImageTransfer.materialize(incoming)`（含历史文件读回 + JSON 解析）
以及按需补运行时配置。只对**准备成功**的请求打；失败路径本来就有 `warnThrottled`。

这样一次请求的固定成本就被拆成三段可对账：客户端 `encode` → `write` → 服务端 `ingest`。
三段加起来才是「发出去之前等了多久」。

## 32.31 请求耗时日志必须落盘：logcat 只留几十秒（2026-10-31，本机 PKX110/A16）

### 32.31.1 抓不到的原因不是没打，是滚掉了

§32.30 加了 `run request: ingest=` 之后，装包（sha `51017c8e…`，15:51:36）并复跑，
再查 `logcat -s Eta` 却**一行都找不到**。不是日志没打，而是：

```
main: ring buffer is 256 KiB (250 KiB consumed, 728 KiB readable)
```

设备 `main` 环形缓冲只有 **256 KiB**，而本次 run 的事件流每轮都要刷十几行 DEBUG。
实测 `logcat -s Eta` 只剩 **43 行**，最早一条是 `17:32:18`，而这次请求的 `request history:`
发生在 `17:31:5x` —— **几十秒就被顶掉了**。

结论很直接：**任何需要「事后对账」的性能日志，只打 logcat 等于没打。** 这也是为什么
`boot.log` 早就存在（`AgentLogger.infoPersisted`，128 KiB、超额保留后半、按 pid 区分进程）。

### 32.31.2 改法

三条计时行从 `info(...)` 改成 `infoPersisted(...)`（仍同时进 logcat，行为不变）：

| 行 | 位置 |
|---|---|
| `request history: encode/write/chars` | `AgentRuntimeWire.requestBundle` |
| `request bundle: build/messages/chars` | `AgentRuntimeClient.run` |
| `run request: ingest/history/chars` | `AgentRuntimeService.ingestRunRequest` |

落点是 `files/diagnostics/boot.log`，客户端与服务端在同一份文件里按 pid 标记，
正好能拼成一次请求的完整账。

### 32.31.3 代价与边界

- 每次请求多 3 行（约 300 字节）。128 KiB 大约能留 **400 次请求**，之后按「保留后半」轮转，
  不会无限增长。
- 写盘在单线程 daemon 池里异步执行，热路径只多一次队列提交。
- **不要**把普通 info 也改成落盘：`boot.log` 一旦变成第二个日志洪水源，启动分段那些
  真正抢救不回来的行就会被冲掉（`AgentLogger` 里已有注释警告）。

### 32.31.4 这一轮还没验证的

装包后要确认：`files/diagnostics/boot.log` 里确实出现三行，且 `ingest` 与客户端两行时间相邻。
在那之前，`ingest` 的具体毫秒数**仍然未知**——本节只解决「能不能留下证据」。

## 32.32 设备上的 `unzip` 是 BusyBox 版：没有 `-Z`（2026-10-31，本机 PKX110/A16）

### 32.32.1 现象与真因

§32.29.9 的核对脚本在设备上枚举 dex 时用了 `unzip -Z1`，结果 `HIT=0`、直接判 FAIL，
而 Linux 侧扫同一个 APK 明明命中。当时我把它归因成「管道在 busybox 下不灵」—— 错的。

实测：

```
$ unzip -Z1 base.apk | head -3
unzip: invalid option -- Z
BusyBox v1.36.1.1 topjohnwu multi-call binary.
```

设备（APatch/KernelSU 环境）里的 `unzip` 是 **BusyBox 多合一**，只实现了 `-l/-p/-x` 等少数选项，
**没有 zipinfo 模式**。所以：

- `unzip -Z1 … | grep -E '^classes.*\.dex$'` → 命令报错、stdout 为空 → 循环 0 次 → `HIT=0`；
- 与「管道」「strings」都无关：`unzip -p base.apk classes12.dex | strings -a | grep -c '…'`
  在设备上**是能命中的**（返回 1）。

### 32.32.2 正确的写法

```sh
DEXES=$(unzip -l "$APK" 2>/dev/null | awk '/classes[0-9]*\.dex$/ {print $NF}')
for f in $DEXES; do
  n=$(unzip -p "$APK" "$f" 2>/dev/null | strings -a | grep -c "$MARK")
  …
done
```

`unzip -l` 在 BusyBox 下可用，`awk '{print $NF}'` 取最后一列就是条目名（实测 21 个 dex 全部取到）。

### 32.32.3 教训

1. **在设备上写核对脚本，先确认命令是哪个实现**：`unzip`/`awk`/`sed`/`find` 都可能是 BusyBox
   精简版，GNU 选项不一定存在。
2. 「命令不存在某个选项」在 `$(…)` 里会**静默变成空**（stderr 被 `2>/dev/null` 吞掉），
   表现为「循环没跑」，而不是「报错」—— 这正是 §32.29.9 那种「空结果被当成结论」的同类。
3. 判失败的方向这次是对的（`HIT=0` → FAIL），所以它至少**没有假绿**；假红浪费了一次安装，
   但假绿会让人把坏包当成好包。

## 32.33 三段账终于对齐：服务端比客户端还贵（2026-10-31，本机 PKX110/A16）

### 32.33.1 落盘生效，一次拿到三条

装包 `1d94cfef…`（`lastUpdateTime=17:43:50`，`firstInstallTime` 未重置，安装脚本 `FAIL=0`）后，
`files/diagnostics/boot.log` 里出现：

```
18:19:01.837 [12478] request history: encode=38ms write=6ms chars=478549
18:19:01.839 [12478] request bundle: build=46ms messages=309 chars=410886
18:19:01.957 [12478] run request: ingest=65ms history=309 chars=410886
```

三条同一次请求，首尾相隔 120 ms。历史这次到 **478,549 字符**（比 §32.30 又大 43%）。

### 32.33.2 账

| 段 | 耗时 | 占「发出前」总成本 |
|---|---|---|
| 客户端 `encode`（sanitize + JSON 序列化） | 38 ms | 34% |
| 客户端 `write`（UTF-8 + 落临时文件 + 开 FD） | 6 ms | 5% |
| 客户端其它（Bundle 组装等，`build − encode − write`） | 2 ms | 2% |
| **服务端 `ingest`（读回 + 解析 + 按需补配置）** | **65 ms** | **59%** |
| 合计 | **111 ms** | 100% |

**服务端一段就比客户端整个 build 还贵。** 而在这轮之前，服务端那 65 ms 完全没有计时 ——
之前所有关于「130 ms 花在哪」的讨论都只看到了客户端那一半。

### 32.33.3 为什么必须先拆 read / decode

65 ms 里面可能有两件完全不同的事：

1. **读文件**：`AgentWireText.read` 流式读 478 KB 并做 UTF-8 解码 → 估计 5~10 ms；
2. **JSON 解析**：`decodeTranscript` 把 478 KB 解析成 309 个对象 → kotlinx 解析通常比序列化慢，
   这段很可能是大头。

这个区别决定下一步：

- 如果大头是**解析** → 「增量编码」有意义（服务端缓存已解析前缀，只解析新增几条）；
- 如果大头是**读文件** → 增量没什么用，该改的是传输形态（例如不再落临时文件）。

所以加了 `run history: read=Xms decode=Yms messages=N chars=M`（同样落盘）。
在拿到这条之前，**不对 ingest 做任何归因**。

### 32.33.4 拆开了：读文件很小，解析也不是大头

装 `c0ff474d…`（`lastUpdateTime=18:32:40`，`firstInstallTime` 未重置，`FAIL=0`）后拿到第一条分解。
安装重启后上下文被重建，只有 16 条：

```
18:33:22.884 [23222] request history: encode=2ms write=1ms chars=31165
18:33:22.885 [23222] request bundle: build=5ms messages=16 chars=27483
18:33:22.935 [23222] run history: read=1ms decode=3ms messages=16 chars=31165
18:33:22.936 [23222] run request: ingest=6ms history=16 chars=27483
```

31 KB 时 read 1 ms、decode 3 ms。**绝对值太小**（毫秒精度、且是冷启动第一条），不能外推到 41 万字符。

所以补了一份**同代码的 JVM 定标**：用的就是 `AgentConversationCodec` 本身，
438,925 字符 / UTF-8 743,085 字节 / 279 条消息，先预热一次再测多轮（临时探针跑完即删）：

| 段 | 冷态（前几次） | 热态 |
|---|---|---|
| `encode` | 4.8 / **33.9** / 5.4 ms | ~5 ms |
| `decode` | **20.5 / 22.3 / 21.0** ms | **5.3 ms** |
| `read`（写临时文件 → 流式读回成 String） | 37.7 / 21.3 / 15.8 / 9.9 / **6.6** ms | **6.6 ms** |

两点必须一起读：

1. **热态下 read + decode 只有约 12 ms**，远小于真机 41 万字符时的 65 ms；
2. **冷态 decode 是 20~22 ms**，是热态的 4 倍 —— 而每一次真实请求都是「另一条线程、刚起来的代码」。

所以 41 万字符的 65 ms 里，history 这半边大概是**二十几到三十几毫秒**，
不是 5 ms 也不是 50 ms。**这个量级既不支持「增量编码」，也不支持「改传输形态」。**

### 32.33.5 再追一层：这条链路从来没有跨进程收益

`AndroidManifest.xml` 里 `AgentRuntimeService` **没有 `android:process`** —— 与界面同进程。
日志的 pid 也印证这一点，客户端行与服务端行永远同一个 pid（`12478`、`23222`、`10591`）。

于是每轮对话都在做这四件事：

1. 客户端把手里**已经有**的 `List<ConversationMessage>` → sanitize → JSON 序列化（41 万字符时 38 ms）；
2. UTF-8 编码写进临时文件、只把 FD 与长度放进 Bundle（6 ms）；
3. 服务端 `AutoCloseInputStream` 读回、UTF-8 解码成一个完整 String（约 7 ms）；
4. kotlinx 把同一份 JSON 解析回**同一批对象**（热 5 ms / 冷 21 ms）。

四步之后拿到的对象，与第 1 步开始时手里那批**逐字段相同**。

值得注意的是 §32.25 早就写了 `sanitizeTranscript`「不做全量 JSON 往返」——
但那条通路只服务跨进程；而同进程才是常态，反倒一直在走最贵的那条。

## 32.34 同进程直传：把那一圈彻底去掉（2026-10-31，本机 PKX110/A16）

### 32.34.1 改动

新增 `AgentWireHistoryHandoff`：一个进程内的交接表。

- 写侧（`requestBundle`，默认 `processLocalHistory = true`）：
  `putString(history_transport, "process")` + `handoff = AgentWireHistoryHandoff.offer(sanitizeTranscript(history))`，
  **不再** encode、**不再**写临时文件；
- 读侧（`historyFromBundle`）：看到 `transport=process` 就直接取引用。
  取不到即 `error(...)` —— 静默回退成空历史会让对话「莫名失忆」，比崩溃难查；
- 跨进程路径（`toLegacyBundle`）显式传 `processLocalHistory = false`，原样保留 JSON + FD 通路。

三条硬约束写进了代码注释，也各有守护用例：

| 约束 | 不这么做的后果 |
|---|---|
| **单次消费**（`take` 取出即删） | 同一次请求解析两遍；或读到上一轮的旧数据 |
| **TTL 60 s + 上限 8 条** | 客户端 offer 了但服务端没来取（发送失败/被杀）→ 每请求留一份几十 MB 的历史 |
| **取不到就失败** | 真跨进程时静默丢历史，表现为「对话失忆」 |

### 32.34.2 真机数据（决定性一条）

装 `2f5c7ae2…`（`lastUpdateTime=18:49:18`，`firstInstallTime=2026-10-03 10:38:00` 未重置，
`FAIL=0`，dex 命中 `run history: handoff=`）后：

```
18:51:57.703 [10591] request history: handoff=1ms messages=132
18:51:57.708 [10591] run history: handoff=0ms messages=132 chars=222328
18:51:57.709 [10591] run request: ingest=4ms history=132 chars=222328
```

**22.2 万字符、132 条，整条链路 5 ms。** 三条日志的 pid 相同（`10591`），也再次印证同进程。

| 版本 | 字符 | 客户端 `build`（含 history） | 服务端 `ingest` | 发出前合计 |
|---|---|---|---|---|
| `1d94cfef`（拆分前） | 410,886 | 46 ms（其中 encode 38 + write 6） | 65 ms（含 read+decode） | **111 ms** |
| `c0ff474d`（拆分后） | 31,165 | 5 ms（encode 2 + write 1） | 6 ms（read 1 + decode 3） | 11 ms |
| `2f5c7ae2`（直传） | 222,328 | 3 ms（handoff 1） | 4 ms（handoff 0） | **7 ms** |
| `2f5c7ae2`（直传） | 253,805 | 1 ms（handoff 0） | 2 ms（handoff 0） | **3 ms** |

最后两行是**同一条连续对话里的两次请求**（pid 都是 `10591`）：字符数从 22.2 万涨到 25.4 万，
总耗时反而从 7 ms 降到 3 ms —— **冷热差异（JIT）比字符数差异更大**，这与 §32.33.4 里
JVM 定标看到的「冷态 decode 是热态 4 倍」是同一个现象。

口径统一为 **`build`（客户端组装，含 history）+ `ingest`（服务端准备，含 history）**；
`handoff` / `encode` / `read` / `decode` 都是这两段内部的子项，不能另加。

**必须说清的口径**：没有做「同规模、同状态」的新旧对照 —— 四行是不同字符数、
不同上下文规模下的测量，且唯一那次 41 万的只有旧包数据。从 41 万折算到 22 万（0.54 倍）
旧路径约 60 ms，新路径实测 7 ms，但这只是**外推**。可以确定的不是倍数，而是机理：
那 4 步（编码/落盘/读回/解析）在新路径上**确实一次都没发生**，`ingest` 里已经不含
history 了（4 ms 里只剩 materialize 与按需补配置）。

### 32.34.3 验证与门禁

- 新增 `AgentWireHistoryHandoffTest` **8 个用例**，含「直传结果 == 原 JSON 往返结果」等价性断言
  （用带 `image_url` 的 `contentJson` 触发 sanitize 改写，证明 `sanitizeTranscript` 与
  `decodeTranscript(encodeTranscriptForStorage(...))` 逐字段一致）；
- `tools/gate.sh` 全量：**835 用例 / 失败 0**（白名单 0），lint 与 baseline 账目一致（412 条），打包成功；
- APK `2f5c7ae2abba53c590939979491b64b5ca6ccfdcf1ae87003bc8cfe5054d5555`，32,733,212 字节；
- 四项安装核对全过：sha 一致、User 0 `firstInstallTime` 未重置、dex 含新标记、测试类 0 命中。

### 32.34.4 未验证

- **同规模新旧对照**（唯一能把「5 ms vs 59 ms」变成确定倍数的方法）：需要旧包在 22 万字符下再测一次；
- **release / R8 构建**的绝对耗时：目前全部数据来自 debug 包，冷态 decode 慢 4 倍很可能与未优化 + 冷 JIT 有关；
- **`toLegacyBundle` 跨进程通路**：代码保留但从未真机走过（`android:process` 一旦被加上就会用到，
  写侧标记会让它立刻失败而不是静默降级）。

### 32.34.5 顺带记一条工具事实

`tools/gate.sh` 在 Linux guest 上整跑约 **30 分钟**，其中 `:app:lintAnalyzeDebugUnitTest`
一段就占 5 分钟以上（`lint.log` 5 分钟不刷新，但 java 进程 CPU 60%，不是死锁）。
分阶段 `STAGES=assemble` / `STAGES=lint` 可以拆开跑，比整跑省时间。

## 32.35 下一笔账查过了：落库不是瓶颈（附一次差点写错的推断）

### 32.35.1 为什么去查

每轮发出前已经从 111 ms 降到 3 ms（§32.34），于是想知道「剩下的每轮成本在哪」。
落库（`AgentConversationStore.saveChanges`）此前**完全没有计时**，是唯一的黑盒。

### 32.35.2 数据（只读副本，未改设备）

`conversation_context_checkpoints` 的正文按 chunk 外置到 `agent_text_chunks`
（分块字段名是 `history` / `journal`，不是列名里的 `history_json` / `journal_json`）。
当前会话 `conv-1a25f9f1`：

| 字段 | chunks | 字符 | UTF-8 字节 |
|---|---|---|---|
| `history` | 18 | 289,180 | 366,027 |
| `journal` | 252 | 4,118,981 | 4,462,933 |

`conversation_messages` 里该会话 21,601 行。`history` 开头正是
`[{"role":"assistant","content":"[Eta 上下文摘要：…`，与请求里那 25.4 万字符对得上。

### 32.35.3 差点写错的推断

看到 4.4 MB 时第一反应是「每轮要重写 4.4 MB」——**这是错的**，差一点写进文档。

`journalWritableFor` = `!state.journalDeferred && old?.journalDeferred != true`，
而启动日志写明 `journal boot sessions=20 inline=0 planInline=0 tooLarge=1` ——
**journal 因过大被判为不内联，永远不写**，库里保留旧值。每轮真正写的只有 history 那 0.3 MB。

### 32.35.4 结论

每轮落库 = `history` 28.9 万字符的编码 + 366 KB 写入，跑在 `Dispatchers.IO`。
按 §32.33.4 的 JVM 定标（41 万字符 encode 约 5 ms）外推，量级约 **10 ms，且在 IO 线程**，
不阻塞界面。**不是瓶颈，本轮不做优化。**

这条线到此收敛：

| 环节 | 量级 | 位置 |
|---|---|---|
| 发出前（客户端 build + 服务端 ingest） | **3 ms**（25.4 万字符） | 主线程路径 |
| 每轮落库 | 约 10 ms（估算） | IO 线程 |
| 模型 API 往返 | 秒级 | 网络 + 推理，**不在应用侧** |

再往下就不是「代码里的固定成本」了。

### 32.35.5 方法学备注

这次全程只读：把 `eta.db` 与 `-wal` / `-shm` 一起复制到 `/data/local/tmp/eta/tmpdb2`，
在 Linux guest 用 `sqlite3` / `python3` 查询，**没有对设备数据库做任何写操作**；
副本用完即删。

## 32.36 release 包真机验收 + 「app-ready 到 activity-created 的 3 秒」查清（2026-10-31，本机 PKX110/A16）

### 32.36.1 release 包安装核对（四项全过）

`bin/` 与 APK 的对应关系必须先验，否则后面所有启动数据都不知道测的是谁：

| 核对项 | 期望 | 实测 |
|---|---|---|
| base.apk sha256 | 与构建产物逐字节一致 | `cc1e88a1…a365fa0f` ✓ |
| versionName | `3.3.5`（不是 `-debug`） | `3.3.5` ✓ |
| User 0 `firstInstallTime` | 不被重置（证明是覆盖安装） | `2026-10-03 10:38:00` 未变 ✓ |
| dex 里的新标记 | `run history: handoff=` 命中 ≥1 | `classes.dex hits=1` ✓ |
| 测试类误入包 | 0 | `hits=0` ✓ |
| 启动阶段新增崩溃 | 与安装前增量一致 | `before=15 after=15` ✓ |

APK 7,199,450 字节、构建 5 分 45 秒。回退包留在设备上（debug `2f5c7ae2`）。

### 32.36.2 启动分段：release 并不比 debug 快

`StartupTrace` 打点，同一台机器、同一份数据：

| 指标 | debug `[25080]` | release 首次 `[32211]` | release 二次 `[7641]` |
|---|---|---|---|
| app-onCreate | +1427ms | +42ms | +45ms |
| app-ready | +1560ms | +154ms | +97ms |
| activity-created | +1605ms | +3638ms | +3274ms |
| first-frame | +2905ms（ui=+1300） | +4095ms（ui=+457） | +3491ms（ui=+217） |
| msgload boot | 204ms | 85ms | 50ms |
| journal boot | 549ms（historyDecode 172） | 188ms | 159ms |

两件事同时成立，而且都是真的：

- **release 的 Application 阶段快了 10 倍以上**（1427ms → 42ms）：R8 去掉了一批类初始化，几个仓库的 `init` 都便宜了。
- **release 的 `app-ready → activity-created` 却慢了 20 倍**（45ms → 3200ms 量级）。

也就是说「release 慢」这个体感不是错觉，问题全部集中在那一段。

### 32.36.3 先确认打点没骗人

`markActivityStart()` 紧跟在 `super.onCreate(savedInstanceState)` 之后（`MainActivity.kt`），
所以这段空白**不含任何 App 自己的 onCreate 代码**，只能是：

1. `Instrumentation.newActivity`（反射创建 `MainActivity`，触发类加载与父类链初始化）；
2. `activity.attach()`（`PhoneWindow`、`WindowManager`、主题资源解析）；
3. `super.onCreate()`（ComponentActivity 的 savedState/lifecycle 装配）。

### 32.36.4 高频采样：这段里进程根本没在算

每 50 ms 采一次 `/proc/<pid>/stat`（state / utime / stime）与 `VmRSS`、线程数、`wchan`。
`/proc/<pid>/stat` 的 utime 是**整个进程所有线程的合计**，不是主线程。

`status=speed` 那一轮（`[4449]`，`app-ready +102ms` → `activity-created +1013ms`）：

| 相对时刻 | 进程态 | utime | RSS | 线程 | wchan |
|---|---|---|---|---|---|
| 136ms | R | 16 | 180MB | 32 | — |
| 298ms | R | 39 | 236MB | 55 | futex |
| 470ms | **S** | 60 | 264MB | 56 | `do_epoll_wait` |
| 736ms | S | 65 | 268MB | 59 | `do_epoll_wait` |
| 1013ms（= activity-created） | S | 66 | 268MB | 57 | `do_epoll_wait` |

**从 470ms 到 1013ms 这 543 ms 里，全进程 CPU 只涨了 6 个 tick（60 ms）**，
主线程停在 `do_epoll_wait` —— 它在 looper 里等消息，不在算东西。

这条证据把「App 代码里有阻塞/死循环」这条路直接排除了：**等的是系统侧**
（`ActivityTaskManager` 的启动流程、splash window、进程 attach 的收尾）。

### 32.36.5 把「冷启动」测成 WARM，是个方法学坑

`am force-stop` 之后 logcat 里出现的是：

```
Start proc 12734:io.github.mangi.eta.canary for service {…AgentAccessibilityService}
The Process … Already Exists in BG. So sending its PID: 12734
```

**无障碍服务会立刻把进程拉回来**，随后 `am start` 走的是复用路径：
`LaunchState: WARM`、`TotalTime: 272ms`、`WaitTime: 279ms`。

两个后果：

1. 用 `am force-stop` 在设备上测不到真正的冷启动，必须先停掉无障碍服务（那本身是系统改动）；
2. **用户日常打开就是这条 WARM 路径**（服务常驻），所以拿 `TotalTime: 272ms` 去说「启动很快」
   是在说另一件事 —— 用户看到的首帧是 1.2~3.5 秒，不是 272ms。

### 32.36.6 dexopt 状态：verify（82 KB）与 speed（29 MB）

| 状态 | base.odex | base.vdex | 来源 |
|---|---|---|---|
| `status=verify` `reason=install` | 82,848 B | 8,612,944 B | 刚安装完 |
| `status=speed` `reason=cmdline` | 29,370,016 B | 8,612,416 B | 手动 `cmd package compile -m speed` |

verify 的 odex 只有 82 KB，意味着**没有任何方法被编译成机器码**，只有校验数据。

同一台机器上 `status=speed` 之后的两次启动（`[32722]` / `[4449]`）：

| 轮次 | app-ready | activity-created | 空白 | first-frame |
|---|---|---|---|---|
| a | +157ms | +1655ms | **1498ms** | +1829ms |
| b | +102ms | +1013ms | **911ms** | +1170ms |

### 32.36.7 归因边界（必须写清楚）

「verify 3.2s → speed 1.0s」这个对照**不是单变量实验**：verify 那两次是**刚装完包的头两次启动**
（还要建 data、首次初始化各仓库），speed 那两次是**跑了一段时间之后**。
同时变了两个变量，所以只能说「AOT 之后观察到 1.0~1.5 秒」，**不能**说「AOT 省了 2 秒」。

要单变量，得在 speed 状态下 `cmd package compile -m verify -f` 再测一轮，或者反过来。
本轮没做，如实记为未验证。

另一条旁证来自 canary 包（同代码、debug、空数据目录、`status=run-from-apk`）：
`app-ready +1411ms → activity-created +1635ms`，空白只有 **224 ms**。
它也是 WARM、且没有任何历史数据 —— 同样不是可比样本，只说明「这段空白不是代码结构决定的」。

### 32.36.8 结论

1. 这段空白**不是 App 主线程在算**：speed 状态下实测 911 ms 里进程 CPU 只涨 60 ms，主线程在 `do_epoll_wait`。
2. 它主要是**系统侧把 Activity 从「进程已 attach」推到「onCreate 被调用」的耗时**，加上首次类加载。
   在 App 代码层能做的优化空间很小（打点已细化到 `super.onCreate` 前后，没有可切的位置）。
3. **可落地的改善只有 AOT 编译**：装完包跑一次
   `cmd package compile -m speed -f io.github.mangi.eta`（需 root / adb），
   或等系统在充电闲置时自己做完 background dexopt。
4. 本次实验期间设备上的主应用已被编译成 `status=speed`（`base.odex` 29 MB）。
   这是**对用户有利**的状态（启动更快、占 29 MB 空间），不是测试残留；
   要回到安装后的原状用 `cmd package compile -m verify -f io.github.mangi.eta`。

## 32.37 时间线投影把堆打爆：一条真正被找到的 OOM（2026-10-31，本机 PKX110/A16）

### 32.37.1 不是空指针，是 OOM

早前只记下「`toTimelineEntries` 崩溃」这个结论，没有堆栈。这次从 dropbox 里挖出原文
（`/data/system/dropbox/data_app_crash@1793402235989.txt`）：

```
java.lang.OutOfMemoryError: Failed to allocate a 262160 byte allocation
  with 1794336 free bytes and 1752KB until OOM, target footprint 268435456
  at java.util.HashMap.resize(HashMap.java:709)
  at kotlin.collections.MapsKt.toMap(Maps.kt:762)
  at io.github.mangi.eta.ui.components.AgentChatBodyKt.toTimelineEntries(AgentChatBody.kt:1557)
  at …AgentConversationMessages-UBWjYJY(AgentChatBody.kt:983)
  at androidx.compose.runtime.Recomposer.performRecompose(Recomposer.kt:1318)
```

上下文：`v2026102909 (3.3.5-debug)`、`Process-Runtime: 69971591`（跑了 19.4 小时）、
`target footprint 268435456`（堆上限 256 MB）。

### 32.37.2 根因：每一帧都在为「每个消息 id」建一次 HashMap

旧实现：

```kotlin
val previousWorkByMessageId = previous.asSequence()
    .filterIsInstance<AgentTimelineEntry.WorkProcess>()
    .flatMap { entry -> entry.messages.asSequence().map { it.id to entry } }
    .toMap()
```

`previous` 是上一版的完整时间线。大会话里大多数消息是过程消息（思考/工具/工具汇总），
全部被折进同一个 `WorkProcess` 块 —— 于是这张 map 的条目数等于**消息总数**（实测会话 8k+ 条，
数据库侧 21,737 行）。而它在 `remember(visibleMessages)` 的块里，
流式刷新时 `visibleMessages` 每变一次就重建一次。

量级估算（每条 = 1 个 Pair + 1 个 HashMap.Node）：

| 项 | 旧实现（n = 12,000） | 新实现（k = 工作过程块数，几十） |
|---|---|---|
| Pair | 12,000 × 16 B ≈ 192 KB | 0 |
| HashMap.Node | 12,000 × 32 B ≈ 384 KB | k × 32 B ≈ 3 KB |
| 桶数组（resize 时一次性） | 32,768 × 4 B ≈ 131 KB | 可忽略 |
| **单帧额外分配** | **≈ 700 KB** | **≈ 3 KB** |

崩溃点正在 `HashMap.resize` 分配那 262,160 字节的桶数组 —— 堆已只剩 1.7 MB。

### 32.37.3 修法：索引建在「块」上，不建在「消息」上

```kotlin
val previousWorkEntries = previous.filterIsInstance<AgentTimelineEntry.WorkProcess>()
val previousWorkByFirstId = previousWorkEntries
    .mapNotNull { entry -> entry.messages.firstOrNull()?.let { m -> m.id to entry } }
    .toMap()
```

只索引每个块的首条 id，规模从「消息数」降到「块数」。语义不能变 —— 前插时当前块的块首
可能是新消息，所以先按首 id 查，未命中再按块扫描回退：

```kotlin
previousWorkByFirstId[workMessages.first().id]
    ?: previousWorkEntries.firstOrNull { entry -> entry.messages.any { it.id in messageIds } }
```

回退只扫块列表（几十项），不会退化成 O(消息数)。

### 32.37.4 测试与门禁盲区

新增 3 个用例：前插且上一版有多个工作过程块（覆盖回退分支）、上一版存在空块（防御）、
2000 条过程消息加一条（长会话追加仍复用 key）。原有 4 个用例不改。

**更值得记的是门禁盲区**：`AgentChatTimelineEntriesTest` **原本不在 `tools/gate.sh` 的
`FAST_TEST_FILTERS` 里**，也就是说改了时间线投影，`STAGES=test` 根本不会跑它（835 个用例里
不包含这 4 个）。已加进白名单，现在 **842 个用例 / 0 失败**。
同类问题在 ADR 0028「门禁不进门禁流程 = 没有门禁」里已经写过一次。

### 32.37.5 未验证

**没有在真机上复现验证**。原崩溃是「跑 19 小时后、堆已接近上限」的偶发场景，
要在设备上构造同等内存压力才能重现；本轮只做到：单测覆盖语义、分配量有估算。
装包验证留待下一次真机回归。



## §32.38 全量单测暴露的「断言漂移」：不是回归，但门禁确实漏了这段代码

### 32.38.1 现象

`5eccf0e`（§32.37 OOM 修复）之后，按惯例跑了一次**不限白名单**的全量单测，用真实结果替代
「门禁绿了就算数」：

- **3219 用例 / 448 失败 / 9 跳过**；
- 其中 **447 个是 `DefaultNativeRuntimeLoader` 的已知环境噪声**
  （`The Robolectric native runtime is not supported on Linux (aarch64)`），
  6 个是「平台不支持就 assume 跳过」；
- **1 个是真实失败**：`AgentRuntimeWireTest.modernRequestKeepsLegacyHistoryOffTheWireButStillRestoresHistory`
  （`AgentRuntimeWireTest.kt:54`）。

### 32.38.2 判定：测试约定过期，不是跨进程回归

第一步先排除最坏可能 —— 「历史真的不跨进程了，会静默失忆」。三条证据都指向否：

1. `app/src/main/AndroidManifest.xml` 里 `AgentRuntimeService` **没有 `android:process`**
   → 与界面同进程，`Messenger` 本地 binder 不经过 Parcel；
2. 发端 `requestBundle(processLocalHistory = true)`（§32.33）**有意**只写
   `history_transport=process` + `history_handoff`，`history_json` 只在
   `toLegacyBundle`（旧客户端通路）里写；
3. 读端对 `transport=process` 的处理是 `AgentWireHistoryHandoff.take(id) ?: error(...)`，
   **取不到就立刻失败**，不存在「静默变空历史」。

失败在第 54 行 `assertTrue("history_json 必须仍在", ...)`，而第 55 行的
`assertEquals(history, runRequestFromBundle(modern).history)` 根本没执行到。
也就是说：这条用例写在 §32.29（去掉内联 `history`、保留 `history_json`），
§32.33 换掉载体后**没有同步**。断言守的是载体键名，不是不变量。

> 结论：**实现不改**。改的是断言 —— 让它盯「历史能不能原样还原」。

### 32.38.3 改法

正向用例改为：不带 `history`、不带 `history_json`、`history_transport=process`、
`history_handoff` 非空、且 `runRequestFromBundle` 还原出同一批消息；`toLegacyBundle` 的一半不动
（旧客户端内联历史仍必须可解析）。

另补一条**反面用例** `lostProcessLocalHistoryFailsInsteadOfSilentlyForgetting`：
把同一个 bundle 解析两次（第二次句柄已被单次消费删掉），断言抛 `IllegalStateException`
且消息里含「进程内交接丢失」。这条钉住的是读端的 `error` 分支 —— 将来若有人为了「容错」
改成 `orEmpty()`，对话会莫名失忆而不崩溃，正是最难查的一类退化。

### 32.38.4 真正的问题在门禁：这段代码一次都没被跑过

`AgentRuntimeWireTest` **不在 `tools/gate.sh` 的 `FAST_TEST_FILTERS`** 里，
所以 `STAGES=test` 的 842 个用例从来不包含它 —— 断言漂移能躺两个版本就是这个原因。

但它**不能**整类加进白名单：类上标着 `@Config(sdk = [33, 36])`，本机 66 个实例里
sdk 33 的 34 个能真跑（全 PASS），sdk 36 的 34 个必然撞 native runtime
（与改动前**同为 34 个**，是环境噪声不是新红）。加进去等于给门禁塞固定红。

取舍：把同属 §32.33、**纯 JVM** 的 `AgentWireHistoryHandoffTest`（8 用例，只碰
`ConcurrentHashMap`）加进白名单。它守的正是这段代码的三条硬约束：单次消费、
TTL / 容量上限自清、`discard` 立即失效。

门禁现在 **850 用例 / 0 失败**（842 + 8）。白名单旁边已写明「为什么加这个、为什么不加那个」，
避免下次有人按「都是 runtime 的测试」一刀切。

### 32.38.5 教训

1. **断言实现细节的测试，会在实现合法演进时变红** —— 红得像回归，实际是测试没跟上。
   断言要写不变量（能力），不要写载体（键名 / 编码方式）。
2. **门禁白名单是双刃剑**：不进去 = 新代码永不执行（本轮实证）；盲目进去 = 固定环境红。
   唯一可靠标准是「能不能在本机真跑」，并且必须把理由写在白名单旁。
3. 全量跑（哪怕一次）能发现白名单看不见的东西，代价是几百条已知噪声要人工分拣 ——
   分拣时按 `failure.message` 分组，只有分组计数**与预期不符**的那一组才值得深挖。

### 32.38.6 真机重启后的短窗数据（**不是**长时复现）

§32.37 的修复只做到「单测覆盖语义 + 分配量有估算」，缺真机数据。补一次重启观测：

- 手段：`am force-stop` + `am start -n …/.ui.MainActivity`。`AgentRuntimeService` 与界面同进程，
  所以这会把当前 Agent 会话一起结束 —— 顺序必须是「先落基线 → 起独立监控 → 延迟重启」。
- 监控：`setsid` 起的独立 shell（`ppid=1`、自身为 session leader），每 5 分钟采一次，
  pid 变化单独写事件行，便于把指标按进程分段。

| 时点 | pid | uptime | RSS | PSS 总量 | Java Heap PSS | dropbox 中 Eta 崩溃文件 |
|---|---|---|---|---|---|---|
| 重启前 | 16017 | 675s | 361,464 KB | 253,828 KB | 39,552 KB | 2 |
| 重启后 | 16692 | 110s | 340,924 KB | 182,383 KB | 26,696 KB | **2（未增）** |

读法（只说数据支持的）：

- Java Heap PSS 39.5 MB → 26.7 MB、PSS 总量 253 MB → 182 MB，说明**堆确实归零重来**；
- `dropbox` 里属于 Eta 的 `data_app_crash` 仍是 2 个（含 10-31 07:17 那次 OOM），
  `logcat -b crash` 的 `FATAL EXCEPTION` 计数为 0 —— **重启后没有立刻崩**；
- RSS 在 110s→192s 间由 340 MB 升到 380 MB，属冷启动预热，**样本太少，不能据此谈泄漏**。

必须写清的边界：这**不是**复现。原崩溃的前提是「连续运行 19.4 小时 + 堆接近 256 MB 上限」，
110 秒窗口证明不了长时间流式刷新下不再膨胀。用户确认「逻辑没问题就行」，即长时复现不做，
§32.37 的定级保持「语义有单测、分配量有估算、长时未验证」。

监控已停止（不留常驻进程）。要重新观测时直接跑（只读，不改任何系统状态）：

```sh
setsid sh /data/local/tmp/eta/oomwatch/watch.sh < /dev/null > /dev/null 2>&1 &
# 数据：/data/local/tmp/eta/oomwatch/metrics.csv（每 5 分钟一行）
```

## §32.39 `eta_self_audit`：让 Agent 能查 Eta 自己哪里坏了

### 32.39.1 需求与边界

用户要的是「Agent 能自动发现当前 Eta 的缺陷，并知道怎么改」。这句话可以做成两种东西：

- 一个**日志罗列器**（把 crash 文件念一遍）—— 没有判断，价值低；
- 一个**带判定规则的自审**：把设备上的事实收敛成「有证据的缺陷 + 测不了的域 + 线索」。

做第二种。边界写死：**只读**，不改配置、不停进程、不动系统设置；
**只说有证据的**，推测进 `clues` 并标 `confidence`。

### 32.39.2 输出三段（这是与「日志搬运」的分界线）

```json
{"ok":true,"package":"…","version":"3.3.5-debug","window_hours":48,
 "findings":[{"severity":"P0","confidence":"observed","title":"崩溃：java.lang.OutOfMemoryError",
              "evidence":["文件：data_app_crash@1793402235989.txt","异常：…","at …(AgentChatBody.kt:1557)"],
              "impact":"…","fix":"…","verify":"…"}],
 "clues":[{"title":"读不到 git 状态（这台设备通常没有 git），未提交改动数未知"}],
 "unavailable":["崩溃与 ANR：读取系统 dropbox 需要 root；当前无法判断有没有崩过"]}
```

- `findings`：P0/P1/P2 排序，每条必须有 `evidence`，并且 `fix` / `verify` 不能空；
- `clues`：线索不是缺陷（TODO 数、未提交改动）；
- `unavailable`：**这次测不了的域**。这一段是诚实性的核心 —— 没有 root 时宁可不说话，
  也不能返回「暂无崩溃」让模型据此宣称「很健康」。

### 32.39.3 判定规则（都有单测）

| 规则 | 结论 |
|---|---|
| 窗口内本包 crash 文件，含异常行 + 代码栈帧 | P0 / observed，证据带首帧 |
| 窗口内 ANR 文件 | P0，建议看主线程当时在做什么 |
| 窗口内 ≥2 次崩溃且当前进程刚起（<30 分钟） | P0 / **inferred**「疑似崩溃循环」 |
| Java Heap PSS / 堆上限 ≥ 90% | P0；≥ 75% P1（`notes` 里写明 PSS≠allocated） |
| 无障碍未连接 | P1 |
| 通知使用权未授予 | P1；**有 root 时不报**（root 可兜底读通知） |
| 使用情况访问未授予 | P1 |
| 崩溃文件早于窗口 / 属于别的包 | 不计入，只在 `notes` 里说明 |

### 32.39.4 真机挖出的一个假话（本轮）

第一版把 `git -C <src> status --porcelain` 的输出行数直接当「未提交改动数」。
真机上 **`git` 根本不存在**：命令失败、stdout 为空 → 计数 0 → 报告「工作区是干净的」。

这正是自审工具最不该犯的错：**把「读不到」当成「没问题」**。已改：

- 退出码 0 才算已知；`grep` 允许 0（有匹配）与 1（无匹配）；其余（含超时 -2）记为 `-1` = 未知；
- `-1` 时 `clues` 明确写「读不到 git 状态（这台设备通常没有 git），未提交改动数未知」；
- 补一条用例 `unknownGitStateIsNotReportedAsClean` 钉住它。

### 32.39.5 接入点与安全

| 位置 | 改动 |
|---|---|
| `AgentSelfAudit.kt` | 纯逻辑判定（无 Android 依赖，可纯 JVM 单测） |
| `AgentSelfAuditTool.kt` | 取数层：dropbox / `dumpsys meminfo` / 权限 / 可选源码扫描 |
| `AgentSelfAuditToolCatalog.kt` | schema（scope / hours / source_root） |
| `AgentToolCatalog` | 无条件注册进目录 |
| `AgentToolRequirements` | 登记 `RootRequirement.PARTIAL`（无 root 仍可用，crash 域降级） |
| `AgentLocalTools` | `executeLocal` 分发 |
| `AgentPlanModePolicy` | 加入只读白名单（计划模式下可用） |

安全：所有 root 命令都是**固定串**；拼进去的只有系统给的 dropbox 文件名与包名，
两者先过 `^data_(app_crash|anr)@\d+\.txt$` / `^[A-Za-z0-9._]+$` 白名单。
`source_root` 参数也只接受 `^/[A-Za-z0-9._/\-]+$`，并且只用于 `test` / `grep` / `git` 的目录参数。

### 32.39.6 验证

- **单测**：`AgentSelfAuditTest` 10 个用例（含「窗口外不算」「别人的崩溃不算」「有 root 时通知不算缺陷」
  「没 root 必须 unavailable」「未知 git 不许报干净」），已进 `FAST_TEST_FILTERS`；
- **门禁**：`STAGES=test` **860 用例 / 0 失败**；
- **真机取数核对**（逐条用等价 shell 命令确认真实环境符合假设）：
  dropbox 文件名 `data_app_crash@<毫秒>.txt` ✓；`dumpsys meminfo` 存在 `Java Heap:` 行 ✓；
  `grep -rIl -e TODO -e FIXME --include=*.kt` 在源码目录返回 5 个文件 ✓；`git` 不存在 ✓（正是因此改了 32.39.4）。

### 32.39.7 未验证

**没有在真机上真正调用过一次 `eta_self_audit`**（需要模型回合；本轮只验证到「取数环境与判定规则」）。
装包后第一次真实调用时，需要核对：返回的 `findings` 里 OOM 那条是否指向
`data_app_crash@1793402235989.txt`、`notes` 里的 PSS 比例是否与 `dumpsys` 一致。
在此之前，这个工具的定级是「单测覆盖判定 + 取数环境核对」，不是「真机跑通」。

## §32.39.8 真机第一次真实调用：四处核对

`eta_self_audit(scope=all, hours=48)` 装好包后第一次真跑（21:19:51，pid 2692），返回 3 findings / 2 clues /
0 unavailable。逐条用等价 shell 命令核对：

| 返回内容 | 用 shell 查到的事实 | 结论 |
|---|---|---|
| `crash-1793402235989` 的异常行 + 前 4 个栈帧 | dropbox 原文同段**逐字一致**；第 5 帧才是 `AgentChatBody.kt:1557`（与 §32.37 同一次崩溃） | 一致 |
| `crash-1793289948247`（Conscrypt `unwrap` 里的 OOM）确实在窗口内 | 距今 45.2 小时（< 48），没被窗口过滤掉 | 正确 |
| `notes`：Java Heap PSS ≈ 15.1% / 256 MB | `Java Heap: 35440` → 13.5% | 量级一致（两次取样之间过了一次 GC） |
| 没有 ANR 条目、`unavailable` 也是空的 | dropbox 里 `data_anr` 文件数 = 0 | 不是「没检查」，是真没有 |

取数层读到的是设备上真实存在的那些字节，判定层没有把「读不到」洗成「没问题」。

**顺带修掉一个可复核性问题**：`notes` 原来只写百分比。同一个进程前后两次取样实测到
22.0%（57,784 KB）与 18.1%（47,420 KB）—— 差 3.9 个百分点，只写百分比根本无法核对。现在把原始 KB 一起写进
`notes`，可以直接对 `dumpsys meminfo` 的 `Java Heap:` 那一列。

## §32.39.9 真机挖出的第二个假话：装包把进程换掉，被当成「崩溃循环」

第一次真实调用除了两条 OOM，还多报了一条 P0：

> 疑似崩溃循环：48 小时内 2 次崩溃，当前进程只跑了 217 秒

核对后发现是假的：

- `lastUpdateTime=2026-10-31 21:16:10`，进程 21:16:12 起 —— 那 217 秒就是**我自己 `pm install -r` 造成的重启**；
- 两次崩溃分别在 14.0 / 45.2 小时前，**都早于这次装包**。

「装包换进程」被读成「崩了又重启」。这与 §32.39.4 的 git 假话同源：**把外部原因当成自己的症状**。
自审工具犯这种错比不报更糟 —— 它会让人去追一个不存在的循环。

修法（纯逻辑，都可单测）：

1. `Input` 增加 `installUpdatedMillis`，取数层填 `PackageInfo.lastUpdateTime`（读不到就是 null，不额外报缺陷）；
2. 只有**本次更新之后**的崩溃才算循环证据 —— 装完包真的还在反复崩，照样报得出来；
3. 窗口内崩溃全早于本次更新时，改出一条 `clue`，写明进程活了多久、`lastUpdateTime` 是几点、
   窗口内几次崩溃各在哪个文件，让人一眼能复核「这是装包造成的」。

用例两条：`freshInstallExplainsRestartInsteadOfReportingACrashLoop`（不再报 P0、改出 clue）与
`crashesAfterTheInstallStillCountAsARestartLoop`（更新之后的崩溃仍判 P0，防止修过头）。

**先坏后好的直接证据**（同一台设备、同一批崩溃文件，只换了装上去的包）：

| | 修前 21:19:51 | 修后 21:35:41 |
|---|---|---|
| `finding_count` | 3（含 P0 `restart-loop`） | **2**（只剩两条真 OOM） |
| 关于重启 | 一条 P0 缺陷 | clue：「进程刚起（646 秒）与最近一次装包/更新（10-31 21:24:52）重合：窗口内 2 次崩溃都早于本次更新，不能据此判定崩溃循环」 |

装上去的包 `base.apk` sha256 = `e50cdcab…f23fdb`，与 `tools/gate.sh STAGES=assemble` 的产物一致；
`firstInstallTime` 仍是 2026-10-03（没被重装重置）。修复后单测 **862 用例 / 0 失败**（原 860 + 2）。

## §32.39.10 顺带补的两处（其中一处没有任何门禁验证过）

- **图标**：`ToolIcons.iconForTool` 给 `eta_self_audit` 加了专属图标
  （`Icons.AutoMirrored.Rounded.ManageSearch`），否则会落到兜底的 `Icons.Rounded.Build`。
  守全目录的是 `ToolCatalogUiTest` —— 它是 **Robolectric 测试，在本机（Linux aarch64）跑不了**
  （同一限制在 gate.sh 的注释里已记过多次），所以那一条对本机是**盲区**。
  补了 `ToolIconMappingTest`（纯 JVM，已进 `FAST_TEST_FILTERS`）钉住这条映射与显示名，
  并用**故意写错映射**（图标改成 `Build`、显示名改成 `tool_tap`）验过它真的会红：
  两条用例各自 `AssertionError`；改回后 864 用例 / 0 失败。仍未在真机界面上看过渲染。
- **显示名**：`AgentOverlayText.toolDisplayNameResource` 加 `tool_eta_self_audit`，三语
  （`values` / `values-b+zh+Hans` / `values-b+zh+Hant`）同步。三语键集由 `LocaleResourceParityTest`
  （纯 JVM、在门禁里）守着，已随本轮跑过。

## §32.39.11 仍未验证 / 未完成

- **Android Lint：上一轮的「没跑完」是误判，实际是过的**。初稿写的是「`lintAnalyzeDebug` 11 分钟
  无输出（既不报错也不前进），已停掉」——那次我盯的是 job 的输出，而 `STAGES=lint` 的输出被
  `tail` 汇总，任务结束前**一直是空的**；真正的进度只写在 `/tmp/eta-gate/lint.log`。
  本轮按日志监控重跑：**4 分 41 秒跑完、门禁通过**（报告时间 13:44:10，baseline 账目一致，
  412 条命中，无新增问题）。教训与 §32.39.4 同源：**把「看不到」当成「没发生」**。
- 自审工具只在本机（PKX110 / Android 16）跑过；测试机（A13 / MIUI）上没跑，
  那边的 dropbox 路径与 `dumpsys meminfo` 格式未复核。

## §32.39.12 证据里必须有「你能改的那一帧」

第一次真机调用返回的崩溃证据是：文件 → 异常 → `HashMap.resize` → `putVal` → `put` → `MapsKt.putAll`（共 4 帧）。
真正属于本项目的 `AgentChatBody.kt:1557` 在第 5 帧，**被截掉了** —— 这条路走到底只能得出「去改 HashMap」。
工具把唯一能动手的那一行丢了。

修法：

- 证据里除前 4 帧外，再补一行 `首个可改的帧：…`；
- 「可改」的判据：优先取包名匹配的帧；canary 的类名不带 `.canary`，按包名找不到时退回「首个非平台帧」
  （`java` / `javax` / `kotlin` / `android` / `androidx` / `dalvik` / `libcore` / `org.chromium` /
  `com.google.android` 这些都不算）；
- 这一帧已经在前面 4 帧里时不重复添加。

用例 3 条（深层帧 / 不重复 / canary 包名不匹配）。写 canary 用例时顺带确认了一件事：
「按包名认领崩溃」靠的是 `Process:` 行（applicationId），而类名不带 `.canary` —— 两个判断不能混用。

**真机对比**（同一批崩溃文件，换包前后）：

| | 换包前 | 换包后 |
|---|---|---|
| `crash-1793402235989` 证据 | 4 帧全是 `HashMap`/`MapsKt` | 多一行 `首个可改的帧：at …AgentChatBodyKt.toTimelineEntries(AgentChatBody.kt:1557)` |
| `crash-1793289948247` 证据 | 4 帧全是 Conscrypt `unwrap` | 多一行 `首个可改的帧：at okio.internal.DefaultSocket$SocketSource.read(DefaultSocket.kt:124)` |

第二条顺带把原因说清楚了：那次 OOM 是在**读 socket 响应体**时爆的（OkHttp/Okio 链路），不是莫名其妙的内存泄漏。

门禁：**867 用例 / 0 失败**（864 + 3），`STAGES=assemble` 出包，装上去 `base.apk` sha256 = `f396ce42…` 与产物一致。

## §32.39.13 崩溃早于当前安装包时，必须自己说清楚

做 §32.39.12 的时候我差点把一条**旧版本的崩溃**当成现存缺陷去修：
那份 `data_app_crash@1793402235989.txt` 的时间是 **07:17**，而 §32.37 的修复提交 `5eccf0e` 是 **20:12** ——
崩溃早于修复，拿它评估当前版本等于拿旧账算新账。工具当时没提醒这一点，因为崩溃文件里
只有崩溃自己的时间，没有被拿来跟「当前装的包是什么时候装的」比。

修法：

- `classifyFailure` 接收 `installUpdatedMillis`；崩溃时间早于安装包时，证据里多一行
  `早于当前安装包（MM-dd HH:mm:ss）：证明的是旧版本的问题`；
- `notes` 汇总一句：「其中 N 份崩溃早于当前安装包…当前版本是否已修，要看这之后有没有新的崩溃文件」
  —— 既不假装已修，也不让人白忙。

用例 2 条（早于安装包 → 必须标记；装包之后的崩溃 → 不许标成旧的，防误伤现存问题）。

**真机验证**（装 `70166f66…` 后调用，`scope=crash`）：两条 findings 各自多出一行
`早于当前安装包（10-31 21:55:30）：证明的是旧版本的问题`，`notes` 里也有那句汇总。
门禁 **869 用例 / 0 失败**。

**这证明不了什么**：它只保证「旧证据不会被当成现症状」，不证明当前版本已经修好 OOM ——
要证明那个，得在装包之后真的再崩一次。

## §32.39.14 长会话每次投影重建上万包装对象：时间线条目复用（含实测分配量）

§32.37 删掉「按消息 id 建 HashMap」之后不再爆内存，但每次重组仍然**为每条消息新建一个
`AgentTimelineEntry.Message`**，工作过程块还要**为整块消息建一个按 id 的 HashSet**。
真机那次崩溃的会话有 8,435 条消息 —— 也就是每次投影新建几千个包装对象、再加一个几千项的集合。

**口径先说清**：投影本身包在 `remember(visibleMessages)` 里，触发点是「消息列表实例变了」
（流式刷新的每个增量），**不是屏幕每一帧**。所以下面这些数字的单位是「每次投影」，
换算成每秒要乘上流式增量的频率（实测一次增量约 120KB → 20KB，少的 100KB 是每个增量省的）。

这次改三处：

1. **普通消息条目复用**（`AgentChatBody.kt` 的 `messageEntry`）：单调游标扫上一版条目，**不建 Map**
   （建 Map 正是 §32.37 的坑）。匹配不上时**不推进游标**，只在 32 个条目 / 8 条消息的预算内前瞻：
   前插历史、或中间某条被新实例顶替 → 游标留在原处，下一条照常复用；上一版头部被裁掉几条（窗口滑动）
   → 在窗口内重新对齐。找不到就新建：**最坏只是少复用，输出的内容永远取自当前 message**，语义不变。
2. **工作过程块快路径**（`flushWorkProcess`）：块首 id 命中、且上一版块是本块的**首段或尾段**
   （`isPrefixOrSuffixRun`，只比实例、零分配）→ 直接复用，不再每次投影建 id 集合。
   前插让块首变成新消息、或两段合并时，仍回退到旧的 id 集合路径，行为与之前一致。
3. **`buildList(capacity = 消息条数)`**：条目数不会超过消息数，省掉 ArrayList 扩容的多次复制。

**实测分配量**（JVM 探针，`ThreadMXBean.getThreadAllocatedBytes`，200 次取平均，测完即删）：

| 场景 | 改前 | 改后 | 说明 |
|---|---|---|---|
| 5,000 条普通消息，只有末条换实例 | 140,170 B/次（全新建） | **20,188 B/次** | −86%；只加游标复用、还没加容量提示时是 75,412 B/次 |
| 2,000 条过程消息，追加一步 | 147,038 B/次 | **50,015 B/次** | −66%，其中「建 id 集合」那一项归零 |
| 2,000 条过程消息，完全没变 | 138,864 B/次 | **41,960 B/次** | −70%；剩下的 42KB 主要是 `workMessages` 列表自身的增长 |

两条工作过程数据里的 13 万字节几乎全是那个按 id 的 HashSet —— 也就是说，二次 OOM 的**分配形态**
（每次投影一个几千项集合）到这一版才真正消失。

**用例**（`AgentChatTimelineEntriesTest` 共 14 条，新增 5 条）：
`prependingOlderMessagesKeepsTheFollowingEntriesIdentical`（前插 300 条后，后面每一条都 `assertSame`）、
`replacingOneMiddleMessageOnlyRebuildsThatEntry`（中间替换只重建那一条，游标不能永久卡住）、
`droppingLeadingMessagesStillReusesTheRemainder`（裁头 3 条后仍复用其余 97 条）、
`alignmentBeyondTheLookaheadOnlyCostsReuseNotCorrectness`（错位超出窗口时只少复用，绝不把 A 的内容
配到 B 的位置上）、
`randomMutationSequencesNeverMisPairEntries`（追加/前插/中间替换/裁头随机交替 200 步，每步都断言
「条目里的 message 就是当前列表那个位置的同一个实例」，并额外断言单步至少复用 100 条 ——
否则「压根儿不复用」的实现也能让前面那些断言全绿，等于空跑）。

**用例不是空跑（注入实验）**：把 `candidate.message === message` 故意改成 `candidate.message.id == message.id`
（按 id 配对，最容易写出的那种错），立刻 **3 条用例红**：随机压测、`unchangedPlainMessagesReusePreviousEntries`、
`replacingOneMiddleMessageOnlyRebuildsThatEntry`，报错位置就是“配错了实例”那一行。
还原后 14/14 全绿。

**门禁与装机**：`STAGES=test` → 用例 **876 / 0 失败**；`STAGES=assemble` → APK `sha256 = 61b16a26…`，
`pm install -r -d` 后已装 `base.apk` 的 sha256 与产物逐字节一致，`firstInstallTime` 仍是 10-03 10:38
（没有变成覆盖安装），dex 里能搜到 `isPrefixOrSuffixRun`、搜不到测试类。

**顺带验证的一件事（以后别再靠“应该一样”判断）**：只改两处**注释**、行数不变，重新打包
sha256 就变了（`53eb59f9…`，体积 +11 字节，相比机上的 `61b16a26…`）。把源码 `git checkout` 回去重建，
又逐字节回到 `61b16a26…`。结论两条：① 相同源码的构建是确定性的（能复现）；
② 「机上的包到底对应哪份源码」只能靠**源码与构建产物同时核对**，不能因为改的是注释就当成没改。
也因为这个，本轮那句措辞修正留在文档里，代码注释保持与已装包一致，不为一句话重装一次。

**真机观察（只说明「装上能跑」，不是与旧版的对比）**：新进程 16,987 正常起来，dropbox 里的崩溃文件数
仍是 2（都是装包前的旧崩溃）。`dumpsys gfxinfo` 两个窗口：装包到 22:33 共 527 帧、janky 8.73%
（含启动与 dex 解压，噪声大）；为了看稳态，在 22:33:43 清零后又采了一段 —— 8,458 帧、janky 0.38%
（50 分位 5ms、90 分位 8ms）。两个窗口负载不同，也没有旧版本在同一条件下的对照，所以这些数字
只能算「当前状态」，**不能用来声称优化了多少**。

**没验证的**：真机上的帧率/卡顿改善没有量化 —— 这次只有 JVM 侧的分配量与「条目实例是否同一」两个证据。
另外 8 条比较预算是对齐窗口的设计选择，超出后退化成全新建（有用例守着「只少复用、不错配」）。

**顺带记一笔（采样器的坑）**：装包会在 22:09:21 杀掉旧进程，而当时那个采样进程从 22:09:52 起
`pidof`、`dumpsys meminfo`、`/proc/<pid>/status` 全部取不到数，直到 22:26:54 才恢复 ——
18 分钟的空档。已停掉旧采样进程，改用**装包后重新起算**的 `samples-after-install.txt`。
教训：装包窗口的观测数据不能直接连起来看，换进程后要重新起算并标明断点。

## §32.39.15 装包后一小时观测：没崩、没被杀，但「堆地板还在抬」不能当已稳

装完 `61b16a26…`（§32.39.14 那版）后跑了一小时观测：每 60 秒记 `pid / heap_kb / peak_kb / crashfiles`，
三种退出条件（出现新崩溃、进程消失、跑满 60 次）任一满足就停并通知。日志 `/data/local/tmp/eta-oomwatch2/watch.log`。

**事实**：

| 项 | 结果 |
|---|---|
| 有效样本 | 60 / 60（没有 NA，说明取数通路整段都正常） |
| 进程 pid | 整段恒定 `16987` —— 一次都没被杀、没重启 |
| dropbox `data_app_crash/anr` | 恒为 **2 份**，都是装包前（10-31 07:17 / 更早）的旧账，没有新增 |
| Java Heap 峰值 | **150 MB**（均值 105 MB，最低 41 MB） |

按 10 个样本一段看趋势（每段 10 分钟）：

| 段 | 最小 | 最大 | 均值 |
|---|---|---|---|
| 1 | 41 MB | 150 MB | 67 MB |
| 2 | 67 MB | 148 MB | 117 MB |
| 3 | 74 MB | 140 MB | 99 MB |
| 4 | 80 MB | 149 MB | 112 MB |
| 5 | 81 MB | 139 MB | 120 MB |
| 6 | 94 MB | 144 MB | 115 MB |

**怎么读这组数**：天花板（140~150 MB）一小时都稳定，但**地板**从 41 MB 一路抬到 94 MB，均值从 67 MB
升到 ~115 MB 后走平。前半段符合「冷启动预热」（类加载、单例、JIT、会话数据）；**后半段地板仍在慢慢上抬**
（81 → 94 MB），所以现在**不能**说「堆已经稳定」或「泄漏已排除」——只能说这一小时没崩、没被杀。

**这不能证明什么**：09:17 那次 OOM 发生在 8,435 条消息的会话上，而本机这一小时里没有那种量级的会话。
「一小时没崩」与「OOM 已修」之间差着**触发条件**（长会话 + 流式刷新 + 投影频率），不是时间长短的问题。

**下一步能提高判断力的做法**（本轮没做）：在长会话上强制 GC 后取基线，隔 10 分钟再取，比较「GC 后基线」
而不是 PSS 峰值；或者把观测窗口拉到 3 小时以上。`dumpsys meminfo` 里的 Java Heap 是 PSS，含尚未回收的垃圾，
用它看趋势可以，拿单个峰值当结论不行。

## §32.40 一次清掉 9 个「没人发」的动作，并把这件事变成门禁（2026-11-01，本机 PKX110/A16）

**起因**：`AgentUiActions.kt` 里 74 个动作，逐个核对「界面上到底谁发它」，发现 8 个从来没有发出点
—— 它们照样编译、照样在 `when` 里有分支、单测与 lint 全绿，因为**「定义了一个动作」这件事本身
不需要任何使用者**。

**怎么查的**（先写脚本再下结论）：
- 从 `sealed interface XxxAction` 里抽成员；
- 在 `app/src/main` 的 Kotlin 里找 `接口.成员`，**排除 when 分支形态**（`is X.Y`、`X.Y ->`、
  多分支 `X.Y, … ->`）与注释行；
- 剩下没人引用的，就是「定义了却没人发」。

初版脚本没排除 `when` 分支，一扫全是 0 —— 把「有分支」错当成「有发出点」。这正说明判据比结论重要：
**「没扫出问题」也可能只是扫描写错了**。

### 清掉的 9 个

| 动作 | 判定依据 | 处置 |
|---|---|---|
| `AgentHomeAction.OpenTools` / `OpenSkills` / `OpenPermissions` / `OpenSettings` | 会话侧栏已有一整套真实入口（`AgentAppShell` 的 `onOpenTools…`，由 `AgentAppRoot` 接到 `pushRoute`） | 删 —— 同一目的地两套通路，改一处漏一处 |
| `AgentHomeAction.OpenSystemEnhance` | 设置页、能力总览、工具页、Linux 环境页共 5 处真实入口 | 删 |
| `AgentHomeAction.RetryConversationLoad` | 没有对应的加载失败状态，`when` 分支写的是 `-> Unit` | 删 —— 死动作 + 空承诺 |
| `AgentSkillsAction.ImportZip(uri)` | ZIP 导入早已改走应用内选择器（注释写明：系统 OpenDocument 在虚拟屏会把任务搬到主屏） | 删，连 `ZipSource.Content` 一起 |
| `AgentPluginsAction.ImportZip(uri)` | 同上 | 删，连 `importZip(uriString)` 一起 |
| `AgentHomeAction.ExpandRunTrace` | 有发出点，但 `when` 是 `-> Unit`；再查 `RunTraceMessageUi` **全文只有定义、没有任何构造点** | 删整条链 |

**`ExpandRunTrace` 值得单独说**：它对应的是一张「Agent 当前可调用能力」卡片（右侧箭头、可点）。
类型从未被投影产出 → 这张卡片**从来没出现过**；而点击回调最终落在 `-> Unit`，就算真出现也是点了没反应。
这类半成品比死代码更坏：它让下一个读代码的人以为这里有功能。

**顺带发现一条会骗人的注释**：`AgentPluginsStore.importZip` 上面写着「保留：仍有调用方走这条」，
而全仓唯一调用者就是那个 `when` 分支本身 —— 删掉分支后它立刻变成死方法。注释和代码一样会过期，
`rg` 不会。

### 门禁：让「定义了没人发」变红

`tools/ui_action_wiring.py` 接进 `tools/gate.sh` 的**始终执行**段（与 `STAGES` 无关，秒级），
同时加进 `tools/preinstall-gate.sh` 的第 2 段（静态对照）—— 那是与 `gate.sh` **并列的另一条**装机路径，
只接一条等于没接（ADR 0028 的教训）：

```
==== UI 动作接线 ====
UI 动作接线检查：7 个动作接口 / 65 个动作
  ✓ 每个动作都有发出点
```

**注入实验（证明它会红，不是摆设）**：临时把 `data object OpenTools` 加回去 →

```
  ✗ AgentHomeAction.OpenTools 没有任何发出点（定义了却没人用）
未接线 1 个。
```

还原后 65 个动作、绿。不放宽判据，也不留白名单。

**不做的事**：不检查「发出了但分支是 `-> Unit`」—— 有意的上报类动作就该是空的，那属于 review、
不属于门禁；否则第一次误报就会让人把整道门当背景。

### 我自己犯的错（记录在案）

批量编辑 `AgentChatBody.kt` 时，第一处的 `old_text` 是 5 行参数、`new_text` 却写成了空串，把
`onSuggestionClick` / `onOpenBrowser` / `onOpenRunDetail` / `isDrawerOpen` 一起删了。`edit_files`
每个文件都成功返回（它只保证「匹配上了」），**是编译才会告诉你删多了**。已按原顺序补回，
随后整跑门禁 876/0。

教训：`edit` 的 `new_text` 只有在「确实要删掉整段」时才留空；删一行就只留那一行的删除。

### 结果与边界

- 动作总数 **74 → 65**；15 个文件 **+19 / −156** 行（含文档与测试），另新增脚本 120 行；
- 门禁 `STAGES=test,assemble`：**876 用例 / 0 失败**，打包 `34,631,246` 字节
  sha256 `6ec21d11233535c0…`；
- `ChatViewFilterTest` 的「过程消息四类」改成三类、DIALOG 隐藏计数 4 → 3 —— 是这次删除的直接
  后果，不是断言漂移；
- 包体比上一版（`36,078,755`）小 1.45 MB。删掉的代码量解释不了这个差异，**不做因果声称**，
  只记数字；
- **未验证**：本轮没有新增 UI 行为（只删），所以没有做界面回归；也**没有装包** —— 删掉的动作与
  消息类型在运行时不可达（`RunTraceMessageUi` 从未被构造），编译 + 876 个单测已覆盖签名与分支；
  要装随时可装，不必等下一轮。

## §32.41 「-> Unit」查完没有真缺陷，但删代码留下了死文案（2026-11-01，同轮续）

### 1. 空处理审计：三处都不是缺陷

上一轮删掉两个 `-> Unit` 分支后，`AgentAppRoot` 里已经没有动作落到空分支。往下一层扫
`onClick = {}`（能点但没反应），全仓 3 处：

| 位置 | 实际语义 | 判定 |
|---|---|---|
| `UserTerminalScreen.CommandBlock` | `combinedClickable` 的 `onClick = {}` + `onLongClick = 弹菜单` | 长按模式，不改 |
| `AgentWorkProcess` 的 `ToolSummaryMessageUi` 分支 | `onSuggestionClick = {}`：该消息类型不含推荐提问，参数是签名必填 | 类型上不可达，不改 |
| `EtaAssistantComposer` 输入框容器 | `clickable(onClick = {})` 让整块容器接点击，焦点交给内部 `BasicTextField` | 刻意，不改 |

结论：这一层没有「假按钮」。**不是每个空 lambda 都是缺陷** —— 判据是「用户能不能点到、
点了有没有别的通道」，而不是文本匹配。

### 2. 真正的尾巴：三语文案变成死键

删掉 `ImportZip(uri)` 与能力卡片后，三个字符串再没人引用：

- `state_please_select_the_zip_file_provided_by_the_system_fi_fea145`
- `state_ui_unable_to_open_selection_9f0004`
- `ui_available_capacity_743337`

**不删会怎样**：资源还在、引用没了 → lint 的 `UnusedResources` 会新增 3 条未抑制问题，
而 `lint_baseline_audit.py` 要求「报告里的未抑制问题必须为 0」→ 下次 `STAGES=lint` 直接红。
删资源不是顺手，是必须。

三个语言目录各一处（`values`、`values-b+zh+Hans`、`values-b+zh+Hant`），按整行精确匹配删除，
行数同步变化（2350→2347 / 2294→2291 / 2294→2291）。`LocaleResourceParityTest`（三语键集一致）
随单测通过。

### 3. 顺带把「死文案」的盘子量了一遍

全仓 2122 条基准文案里，**201 条**没有任何 `R.string.` / `@string/` 引用（≈9.5%）。
`lint-baseline.xml` 里已记 **215 条** `UnusedResources` —— 两者数量接近，说明这基本是**已知
技术债**，不是本轮造成的。抽查 4 条（`conversation_stop_and_delete`、`ui_reload_5982c4`、
`tool_personal_context`、`settings_plan_mode_title`）确认除 `strings.xml` 外全仓无引用。

**没有一次删掉 201 条**：扫描没覆盖测试目录与运行时拼接，单凭「没有 `R.string.x`」不足以判定
无用；一次删 600 行（三语）收益小、误删风险大。记为待办，不假装已清。

### 4. 我自己犯的错（第二笔）

上一轮为了「同步文档」，把 `docs/TECHNICAL.md` 里「只看对话视图」那张表里的
`RunTraceMessageUi` 删掉了 —— 而那一节开头就写着「**本节是实现历史，不是当前代码**」。
历史章节记录的是当时的事实，删掉它不是同步，是篡改。已恢复。

教训：改文档前先读它自己的适用范围声明；「代码里没有了」不等于「文档里不该出现」。

### 5. 结果与边界

- 门禁 `STAGES=test,assemble`：**876 用例 / 0 失败**，包 `33,588,715` 字节
  sha256 `f62b1161c2971efc…`；
- 包体三轮递减：`36,078,755` → `34,631,246` → `33,588,715`。删的代码量解释不了这个量级；
  核对 dex：**21 个分片**，`isPrefixOrSuffixRun`、`AgentUiActions`、`AgentAppRootKt`、
  `AgentChatBodyKt`、`AgentSelfAudit` 都在包内。**可能与 multidex 分片边界变化影响压缩率有关，
  未验证**，不做因果声称；
- 仍未做：201 条死文案的清理（需先补「测试目录 + 动态拼接」的扫描口径）。

### 6. 还有一层：删代码会让 lint baseline 变陈旧（本轮真踩到）

删完文案、`STAGES=test,assemble` 全绿之后，我又跑了一次 `STAGES=lint`（约 12 分钟）—— 它红了，
但**不是**文案的问题：

```
✗ lint 报告有 1 条问题（报告时间 16:03:39）：
  - [LintBaselineFixed] lint-baseline.xml:?
```

报告正文写明：「2 errors/warnings were listed in the baseline file … but not found in the project …
Unmatched issue types: **UseKtx (2)**」。即 baseline 里有两条记录，现在已经找不到对应的代码。

顺着查，正好是这一轮删掉的两个方法里的 `Uri.parse(...)`：

| baseline 条目 | 原代码 | 本轮 |
|---|---|---|
| `AgentPluginsStore.kt:229` | `importZip(uriString)` 里的 `Uri.parse` | 方法已删 |
| `AgentSkillsStore.kt:139` | `importSkillZip(uriValue)` 里的 `Uri.parse` | 方法已删 |

两条 `UseKtx`（建议改用 `String.toUri`）随之失效。按整块精确匹配删掉后，baseline 从 **412 → 410** 条
（`UseKtx` 剩 67 条）。

**这说明什么**：删代码不是「编译器过、单测过」就完事 —— `lint-baseline.xml` 按「文件 + 消息」记账，
被删掉的代码会留下**永远不会再命中的条目**，下一次 lint 就以 `LintBaselineFixed` 报红。
`lint_baseline_audit.py` 把它当问题处理是对的，否则 baseline 只增不减，很快就没人敢碰。

**教训**：`STAGES=test,assemble` 绿了不等于门禁绿。这一轮原本打算只跑这两段，是「删了资源会不会动账目」
这个念头才去跑 lint —— 抓到的还不是我预想的那个问题。**跑一遍全部阶段**比「推断哪一段会红」可靠。

## §32.42 把「死文案」变成可比的数字，并接进两道门禁（2026-11-01 凌晨，承接上节）

### 1. 为什么需要一个脚本

§32.41 量出 201 条死文案后没有删——判断是「单凭没有 `R.string.x` 不足以判定无用，一次删 600 行
收益小、误删风险大」。但这也意味着这个数字**只能靠人重算**：下次谁删了代码、引用跟着消失，
文案留在原地，编译不报、单测不红，要等 `STAGES=lint` 的 `UnusedResources` 多几条才可能察觉——
而那时已经很难想起是哪次改动造成的。

所以这一轮做的不是「删文案」，是**把「现在有多少条死文案」变成可比的数字**，并让它在变动时变红。

### 2. 脚本口径（边界写进注释，不留在脑子里）

`tools/dead_strings.py`：

| 项 | 取值 |
|---|---|
| 基准 | 仅 `app/src/main/res/values/strings.xml`（默认语言），2122 条 |
| 扫描 | `app/src/main`、`app/src/test`、`app/src/androidTest`；kt/java/xml/json/js/html/txt/md/pro，共 1320 个文件 |
| 引用写法 | `R.string.x` 与 `@string/x` |
| 动态取名 | 出现非 `android` 命名空间的 `getIdentifier(...)` 即**报红**，那时「没人引用」的结论不成立 |
| 基线 | `tools/dead_strings_allow.txt`（本轮 201 条；§32.43 清理后归零），只许减少 |

两个口径限制写在脚本注释里：

- **基准只取 `values/strings.xml`**：`values/root_capabilities.xml` 里的名字不在基准内，脚本对它们
  不发表意见（实测差集里正好只有 `capability_workspace_private_summary` 一条，归 lint 管）；
- **动态取名的判据要收窄**：全仓唯一一处 `getIdentifier("config_nightDisplayAvailable", "bool", "android")`
  读的是系统开关，与本项目资源无关，所以判据限定「取 string 且包名不是 android」。

### 3. 门禁必须被证明会红

绿的门禁不叫门禁（§71 的教训）。三次注入实验：

| 实验 | 做法 | 结果 |
|---|---|---|
| A 新增死文案 | 往 `strings.xml` 加一条没人引用的 | exit=1，报出名字 |
| B 基线过期 | 从基线删一条（模拟删了文案没同步基线） | exit=1，提示同步 |
| C 还原 | 撤销两处 | exit=0，201 条与基线一致 |

第一次写实验 B 时用了 `python3 … | head -6` 再取退出码，取到的是 `head` 的 0——**假绿**。
改成先重定向到文件再取 `$?`，才是被测脚本的退出码。同类坑第二次（§71 那次是 `go fmt | git diff`）。

### 4. 接入位置

- `tools/gate.sh`：新增 `run_dead_strings`，**不挂在 STAGES 下、始终执行**（与动作接线同段）。
  为什么紧挨着动作接线：删代码 → 引用消失 → 文案变死，这三件事总是一起发生；
- `tools/preinstall-gate.sh`：第 2 段之后同跑一次。装完不会有任何症状，等 lint 发现要十几分钟。

### 5. 与 lint 交叉验证（对账，不是互相相信）

| | 条数 |
|---|---|
| 本脚本死文案 | 201 |
| `lint-baseline.xml` 中 `R.string` 的 `UnusedResources` | 202 |
| 交集 | 201 |
| 仅本脚本有 | **0** |
| 仅 lint 有 | 1（`capability_workspace_private_summary`，在 `values/root_capabilities.xml`） |

`UnusedResources` 合计 215 条，另含 color 7 / xml 2 / plurals 2 / style 1 / drawable 1——记下来，
下次看到「215 ≠ 201」不必重查。

### 6. 结果与边界

- `STAGES=test,lint,assemble sh tools/gate.sh` 全绿：契约 minSdk=33 ｜ 动作 7 接口/65 动作 ｜
  死文案 201 ｜ 单测 **876 / 0** ｜ lint 无新增、baseline **410** 命中 ｜ 包 `33,588,715` 字节
  sha256 `f62b1161c2971efc…`（与上一轮同值：本轮只加工具与注释，没有应用代码变化）；
- 本轮一条都没删。基线机制的作用是「止血 + 数字可比」，不是「清理完成」——
  下一轮（§32.43）在补齐口径后把 201 条全部删掉了；
- 未做：把脚本接进门禁之外的开发者自查入口（现在只有两道门禁会跑）。

## §32.43 把 201 条死文案真的删了，并纠正「用官方任务更新 baseline」的坑（2026-11-01 凌晨，承接上节）

### 1. 为什么这次可以删

§32.41 判「收益小、误删风险大」时，缺的是证据；§32.42 把证据补上了：

- 动态取名全仓只有一处，且读的是系统 bool（`config_nightDisplayAvailable`），与本项目资源无关；
- 扫描覆盖 `main` / `test` / `androidTest`（含 assets 里的 js/html），1320 个文件；
- 201 条与 lint 的 202 条 `R.string` 完全对齐：交集 201，本脚本多出 0；
- 三语同名同删，`LocaleResourceParityTest` 守着键集一致。

### 2. 删除方式与一次「脚本自己的误报」

一次性脚本（不进仓库）：只处理 allowlist 里的 name，非死文案哪怕多行也原样保留；
死文案若是多行块直接报错退出。第一次跑就报错：`values/strings.xml:392` 的
`ui_core_memory_...` 是 3 行块 —— 查下去发现它**不是死文案**，是我的脚本把「任何多行块」都当成了
错误。修法是先把「是否在 allowlist」判断前置。

这正是先 dry-run 的价值：第一次 dry-run 用的统计脚本只算了命中块的单行/多行，没暴露这个分支；
真正执行时才撞上。**「检查脚本」和「执行脚本」的判定口径必须一致**，否则前者给的安心是假的。

结果：三语各删 201 条、各 201 行，共 603 行。文件行数 2350 → 2146、2294 → 2090、2294 → 2090
（比 201 多减的 3 行来自 §32.41 那次删的 3 条死键）。删除后 XML 解析通过、键集合 = 原集合 − 201、
三语同步。

### 3. 坑：`updateLintBaseline` 会顺手抹掉别的技术债账目

删完文案，`dead_strings` 归零，但 baseline 里 201 条 `UnusedResources` 已失效。第一反应是用官方任务：

```bash
./gradlew :app:updateLintBaseline
```

结果 baseline 从 410 → **206**，而不是预期的 209。逐条对账（id + message + location）：

| | 条数 |
|---|---|
| 消失 | 266：`UnusedResources` 203、`PluralsCandidate` 23、`TypographyEllipsis` 21、`UseKtx` 12、`ModifierParameter` 4、`ApplySharedPref` 1、`HandlerLeak` 1、`AutoboxingStateCreation` 1 |
| 新增 | 62（多为行号漂移后的同一条问题） |

它的语义是「**以本次报告为准整份重写**」：本次没复现的条目一律丢掉。而「没复现」未必是修好了——
行号漂移、增量分析、依赖图变化都可能让它这次不出现。于是 60 多条与本次改动无关的技术债记录被
一次抹掉，等它们下次再报出来，就是未抑制问题、门禁直接红。

已 `git checkout` 回滚，改成按块做文本删除：只删「`id="UnusedResources"` 且 message 里的
`R.string.NAME` 在 201 集合里」的 issue 块，其余字节原样保留。

### 4. 删除后的对账（两处「连带失效」）

第一次删完 209 条，门禁红在 `LintBaselineFixed`：

```
3 errors/warnings were listed in the baseline file but not found in the project …
Unmatched issue types: TypographyEllipsis (3)
```

原因：`TypographyEllipsis`（把 `...` 换成 `…`）有 23 条记录都指向 `strings.xml`，按「同文件同消息
的实例数」匹配。被删的 201 条里有 1 条含 `...`（`page_processing_dde1db`，三语各一）→ 三语各少
一个实例 → 3 条记录失配。核对行号也对得上（删前 764/745/730，lint 记的是 762/743/728，差 2 行
XML 头）。删掉这 3 条后，baseline 206 = 报告命中 206。

**教训**：删资源不只影响 `UnusedResources` —— 其它「按出现次数记账」的类型（`TypographyEllipsis`
这类纯文本检查）也会跟着失效。删完必须重跑 lint 看 `LintBaselineFixed`，不能只看首尾两个数字。

### 5. 结果

- `dead_strings`：201 → **0 条**，`tools/dead_strings_allow.txt` 只剩表头（以后新增一条就红）；
- lint baseline：410 → **206**（`UnusedResources` 14 条：2 xml / 7 color / 1 drawable / 1 string /
  2 plurals / 1 style；其余为 UseKtx 67、PluralsCandidate 29、TypographyEllipsis 20 等）；
- 门禁 `STAGES=test,lint,assemble` 全绿：契约 minSdk=33 ｜ 动作 65 ｜ 死文案 0 ｜ 单测 **876 / 0** ｜
  lint 无新增、baseline **206** 命中 ｜ 包 `33,588,715` 字节 sha256 `60066630749e5f19…`
  （**包体大小与删前完全相同**，sha 变了；不做因果解释）。

### 6. 边界

- 脚本只管 `<string>`：`plurals` 不在扫描内，而 baseline 里正好还有 `work_completed_steps`、
  `linux_missing_core_commands` 两条 `R.plurals` 死资源 —— 下一轮若要继续清，口径要先扩到 plurals；
- `capability_workspace_private_summary` 定义在 `values/root_capabilities.xml`，不在基准内，仍未处理；
- 删除的文案只能从 git 历史找回（`60cef2f` 之前），不是「挪到别处」；
- 未在真机验证：本轮改动是纯资源删除，运行时引用的字符串全部保留，但**没有装机跑一遍界面**
  （风险低不等于验证过，如实记下）。

## §32.44 口径扩到 plurals 与 `values/*.xml`，又清掉 3 条（2026-11-01 凌晨，承接上节）

### 1. 上一节留的两条「边界」，其实是同一个洞

§32.42 的基准只取 `values/strings.xml`，当时把 `R.plurals` 的 2 条和
`values/root_capabilities.xml` 的 `capability_workspace_private_summary` 都记成「不在口径内」。
这次把基准换成 `values/*.xml` 目录、并识别 `R.plurals` / `@plurals/` 之后，两件事一起解决：

| 文件 | string / plurals 定义数 |
|---|---|
| `strings.xml` | 1972 |
| `root_capabilities.xml` | 158 |
| `linux_environment_ui.xml` | 8 |
| `execution_service.xml` | 6 |
| `ui_list_details.xml` | 5 |
| `browser_sessions.xml` | 3 |
| 合计 | 2152 |

死项正好 **3 条**，与 lint 的 `UnusedResources`（202 条 `R.string` + 2 条 `R.plurals`）逐条对上。
这也是判断口径是否完整的依据：多了说明误伤，少了说明漏扫。

### 2. 改了什么

- 基准从单文件改为 `values/` 目录下所有 xml。**不含** `values-night` / `values-v*`：那些是同一资源的
  另一份取值（例如夜间配色），不是新键；
- 引用识别加 `R.plurals.x` / `@plurals/x`；动态取名判据从「只认 string」放宽到 string 与 plurals；
- 基线条目带类型前缀（`string:name` / `plurals:name`）。当前两类没有同名，但这个坑不值得等它出现再补——
  同名时纯名字集合会让「有引用的 string」把「没引用的 plurals」救活。

### 3. 删除（三语同步，共 24 行）

| 资源 | 英文 | 简中 | 繁中 |
|---|---|---|---|
| `plurals/work_completed_steps` | 4 行 | 3 行 | 3 行 |
| `plurals/linux_missing_core_commands` | 4 行 | 3 行 | 3 行 |
| `string/capability_workspace_private_summary` | 1 行 | 1 行 | 1 行 |

中文 plurals 只有 `other` 一个数量（中文不分单复数），所以比英文少一行。
删后 `dead_strings` 归零（string 0 / plurals 0），基线为空。

### 4. 连带检查（这次没有额外的）

lint baseline 同步删 3 条 `UnusedResources`（206 → 203，纯删除、无插入）。这次没有别的类型跟着失效：
两个 plurals 的内容不含 `...`（不触发 `TypographyEllipsis`），而 `PluralsCandidate` 针对的是
**string** 里「`%d` 后跟词」，不是 plurals 本身。

### 5. 结果

- 门禁 `STAGES=test,lint,assemble` 全绿：契约 minSdk=33 ｜ 动作 65 ｜ 死文案 0 ｜ 单测 **876 / 0** ｜
  lint 无新增、baseline **203** 命中 ｜ 包 `33,588,715` 字节 sha256 `035b41c504ed8544…`；
- 本轮没有应用代码改动，只有资源与门禁脚本。

### 6. 现在的边界

- `values-night` / `values-v*` 不参与基准：某个键若只在夜间版定义，本脚本看不见
  （§32.46 补了一例单测：night 不得凭空造键）；
- 颜色 / 样式 / drawable / xml 仍未纳入。原文写「还剩 12 条：7 color、2 xml、1 drawable、1 style、
  1 条 `ic_assistant_keyboard`」是**重复计数** —— 最后一项就是那 1 个 drawable，实数 11 条。
  §32.46 删掉其中 9 条，只剩 2 条 xml（被 manifest 按属性名引用，属 lint 误报）；
- 仍未装机验证：本轮同样只删资源，运行时引用已由门禁保证，但**界面没跑过**。

## §32.45 救活一条「从来没跑过」的用例，它立刻报出 14 个工具没图标（2026-11-01 凌晨，承接上节）

### 1. 问题：门禁报 876 全绿，这 6 个用例不在里面

`ToolCatalogUiTest` 有 6 个用例，守着「每个运行时工具与工具卡都有专属图标」。它一直跑不起来，
原因有两层，叠在一起就完全隐身：

- `@Config(sdk = [36])`：本机 Linux aarch64 没有对应的 Robolectric native runtime；
- `gate.sh` 的 `FAST_TEST_FILTERS` 是**显式白名单**，它不在里面 —— 注释里正好记着
  「Robolectric 测试别塞进这个列表」。

所以「门禁绿」这句话里，这 6 个用例的真实状态是**没跑**，不是通过。§71 记过同一类：
没跑门禁会伪装成门禁放行。

### 2. 改法：只把「取文案」这一层拆出来

它用 Robolectric 只为一件事：`RuntimeEnvironment.getApplication()` 拿 Context 调
`context.getString()`。而它真正验证的是分组结构、工具 id、元数据 —— 与文案无关。

`buildToolsState` 加一个重载：

```kotlin
internal fun buildToolsState(context: Context): AgentToolsUiState =
    buildToolsState { context.getString(it) }

internal fun buildToolsState(text: (Int) -> String): AgentToolsUiState = ...
```

测试改成 `buildToolsState { id -> "res:$id" }`，删掉 Robolectric 的三行 import 与两个注解。
生产路径不变（`AgentAppRoot` 两处仍走 Context 重载）。

顺带把断言从「逐条 `assertNotEquals`」改成「收集全部 offenders 再比」—— 原来的写法在第一个
失败处就停，修一个冒一个，看不到全貌。

### 3. 第一次跑起来就红：14 个工具落到默认图标

| 组 | 工具 |
|---|---|
| 虚拟屏 | `virtual_display_create` / `list` / `launch` / `release` |
| ACP 代理 | `acp_agent_list` / `acp_delegate` / `acp_session_status` / `acp_session_close` |
| 插件 | `plugin_list` / `install` / `uninstall` / `enable` / `reload` |
| 工作目录 | `working_directory` |

14 个全部落在 `Icons.Rounded.Build`。已按语义补图标：虚拟屏 `Monitor`、ACP `AccountTree`、
插件 `Extension`、工作目录 `FolderOpen`。

补的时候还踩了一个小坑：`Icons.Rounded.Monitor` 是扩展属性，**必须单独 import**；
只写 `Icons.Rounded.Monitor` 会报 `Unresolved reference 'Monitor'` —— 那是缺 import，
不是「图标不存在」。我第一反应以为是后者，换了 `DesktopWindows` 才发现同一个错。

### 4. 结果

- 用例数 **876 → 882**。这不是新增测试，是把一直没跑的 6 个接进统计；
- 门禁 `STAGES=test,lint,assemble` 全绿：882 / 0 ｜ lint 无新增、baseline 203 命中 ｜
  包 `33,588,959` 字节（比上轮 +244）sha256 `e14d1aec741f061b…`；
- 门禁注释里补了一条：这类「白名单外」的用例，加进来之前等于不存在。

### 5. 边界

- 只改了 `ToolCatalogUiTest` 一个类的运行方式；其它 Robolectric 测试（`ToolCatalogUiTest` 之外）
  仍在本机跑不了，仍靠 `STAGES=test-all` 或真机探针；
- 图标只保证「不是默认 Build」与语义大体贴切，**没有装机看实际渲染**——图标是否好看、
  尺寸是否协调，得看屏幕。

## §32.46 把 lint 里剩下的死资源逐条判死刑，删掉 9 条，并把「night 不许凭空造键」变成单测（2026-11-01，承接上节）

### 1. 先数清楚还剩几条：上一节自己写错了

上一节的边界写「还剩 12 条：7 color、2 xml、1 drawable、1 style、1 条 `ic_assistant_keyboard`」，
把同一个 drawable 数了两次 —— 7 + 2 + 1 + 1 = 11。实测 `lint-baseline.xml` 里 `UnusedResources`
正是 11 条，与 7+2+1+1 一致。已在上节更正：**文档里的数字也要能被自己加一遍**。

### 2. 逐条核实：只有「真没人引用」才删

| 项 | 数 | 怎么核实的 | 处置 |
|---|---|---|---|
| `colors.xml` 的 purple_200/500/700、teal_200/700、black、white | 7 | 全仓 grep `@color/x` 与 `R.color.x` 零命中（Compose 配色在 `Theme.kt` 里硬编码） | 删 |
| `drawable/ic_assistant_keyboard` | 1 | 代码引用的是 `ic_assistant_keyboard_float`（另一个文件）；`@drawable/ic_assistant_keyboard\b` 零命中 | 删 |
| `style/Theme.EtaVoiceInteractionSession` | 1 | manifest 只用了 `Theme.Eta` 与 `Theme.EtaVoiceAssistBridge`；`R.style.` 全仓零命中 | 删（values 与 values-night 两份一起） |
| `xml/backup_rules`、`xml/data_extraction_rules` | 2 | manifest 用 `android:fullBackupContent` / `dataExtractionRules` **按属性名**引用，lint 的 `UnusedResources` 看不到 | 保留 |

### 3. 我的脚本先骗了我一次

第一次比对 `values-night` 与默认资源时，脚本报「night 有 3 个 style，默认 0 个」—— 差点写成
「night 里有凭空造键」。实际是脚本 bug：key 里带了目录前缀，`en.get("values-night:style")`
永远取不到。按 tag 重新比对：两边各 3 个（删掉那个无用 style 后各 2 个），完全一致。

顺带实测到一条 aapt 行为：探针写成 `<style name="Theme.NightOnlyProbe">` 时 **aapt 直接链接失败**
（`resource style/Theme not found` —— style 名的点号表示隐式继承）。所以要造「凭空键」的探针，
名字不能带点号。

### 4. 改法

- `values/styles.xml` 与 `values-night/styles.xml` 各删 7 行（同一个 style、parent 不同，必须两份一起删）；
- `colors.xml` 删空后整文件删掉，`ic_assistant_keyboard.xml` 直接删；
- `lint-baseline.xml` 按 file+message 精确删 9 块：**2235 → 2136 行**（每块 11 行），
  没有用 `updateLintBaseline`（§32.43 记过它会按当前报告整份重写）。

### 5. 把人工核对固化成门禁（`LocaleResourceParityTest` 新增一例）

`nightOverridesOnlyKeysThatExistByDefault`：读 `values/` 与 `values-night/` 的**全部**顶层定义，
按 `tag/name` 比集合，night 多出来的键就红。理由不是洁癖 —— 只在 night 定义的键，白天配置下
根本不存在，一旦被 manifest 或代码引用就是 `Resources$NotFoundException`。

注入实验（喂一个已知会失败的输入）：往 night 加一个默认没有的 `NightOnlyProbe` style →
用例红，失败信息精确点名 `style/NightOnlyProbe`；删掉探针 → 全绿。

### 6. 结果

- `STAGES=test,lint,assemble` 全绿（删资源那次）：882 / 0 ｜ 死文案 0 ｜ lint 无新增、
  baseline **194** 命中（203−9）｜ 包 `33,588,875` 字节 sha256 `feb207fa80b3a231…`；
- 随后加单测再跑 `STAGES=test`：**883 / 0**；
- 包体比上轮小 84 字节 —— 只记数字不解释（删的是 2.8KB 的 vector + 7 个色值 + 1 个 style，
  压缩与对齐会让差值不成正比）。

### 7. 边界

- 剩下的 2 条 `UnusedResources` 是 lint 误报（manifest 按属性名引用），理由留在报告里；
- 仍未装机：删的是资源，引用已由全仓 grep 与 lint 双向确认，但图标与主题的**实际渲染没看**；
- 新用例只守 `values-night`；将来若出现 `values-v*`，按同样规则加（默认目录必须有同名键）。

## §32.47 OOM 修复的定量评估，以及几处被数据推翻的判断（2026-11-01 凌晨）

> 本节原误编为 §32.45，与「救活 ToolCatalogUiTest」那节撞号；已改为 §32.47（§32.46 在本节之前）。

### 1. 起因：想验证「OOM 已修」，先量一次分配

§32.39 的复用修复只有「装包后 1 小时没崩、没被杀」这一条证据，文档里也写明**这不等于 OOM 已修**
（崩溃发生在 8,435 条消息的会话上）。这次用一个临时 JVM 探针（跑完即删）量了同量级下的分配量：

- 方法：`com.sun.management.ThreadMXBean.getThreadAllocatedBytes(当前线程)`，测量
  `List<AgentChatMessageUi>.toTimelineEntries(previous)` 单次调用的分配字节；
- 规模：8,435 条消息（与崩溃会话同量级），冷构建一次 + 连续追加 100 步取平均；
- 构成：全部普通消息 / 全部过程消息（Thinking，形成一个工作过程块）/ 5000 普通 + 3435 过程。

| 构成 | 冷构建 | 条目数 | 单步追加 |
|---|---|---|---|
| 全部普通消息 | 252,568 B | 8,435 | **34,462 B** |
| 全部过程消息 | 582,904 B | 1 | **578,062 B** |
| 混合 | 393,624 B | 5,001 | 84,396 B |

### 2. 结论：map 那条路没了，但「复制整块」还在

- 普通消息：单步 34 KB —— 游标复用生效，只新建最后一条 + 输出列表本身；
- **过程消息：单步 578 KB，是普通消息的 17 倍**。原因不是 map（旧实现里对每个 id 建 Pair +
  HashMap 节点，正是 `HashMap.resize` 崩溃点），而是 `workMessages.toList()` 每帧复制整块 8,435 条
  引用，加上 `ArrayList` 扩容的累计复制；
- 混合：84 KB —— 与过程消息占比成正比。

即：**修复把「每帧建 8,435 个 map 节点」换成了「每帧复制 8,435 个引用」**，分配量降了约 2 倍
（旧实现按当时的测量约 1 MB+/帧），但**工作过程块仍然是 O(n)**。这解释了为什么它能避开
`HashMap.resize` 的崩溃点，却不足以声称「长会话不会再 OOM」——短命对象会推高 GC 频率，
而 Compose 快照可能让上一版列表多活一帧。

**没有改实现**：要真正消掉这 578 KB，需要让 `WorkProcess.messages` 共享前缀（持久化列表或
两级拼接视图），或让 UI 只渲染块的尾部——前者引入新依赖/新结构，后者改变可见语义，两者都必须
在真机上验证重组与滚动锚点。本轮只做测量，不做没有验证手段的改动。

### 3. 顺带核实：那句「门禁盲区」在改动之前是对的

我先按记忆把它写成「这是错的」，查完 git 又反过来 —— **错的是这句更正本身**：

| 时点 | 证据 | 它当时是什么 |
|---|---|---|
| `35a1408^`（改动前） | `git show 35a1408^:tools/gate.sh` 里 grep `ToolCatalog` **零命中**；文件带 `@Config(sdk = [36])` | 本机起不了 Robolectric 运行时 + 不在白名单 → 从没跑过 |
| `35a1408`（改动后） | 该提交在 `FAST_TEST_FILTERS` 新增第 111 行；文件删掉 Robolectric 后 99 行纯 JUnit | 能跑，全量看 `tests="6" skipped="0" failures="0"` |

所以「它是盲区」描述的是 `35a1408` **之前**的状态，不是把某个类记混了。真正的错法是：
**拿改完之后的文件去否定改之前的判断**（事后视角），再顺手把这句责备成「记混了另一个
`@Config(sdk = [36])` 的类」。后者不算全错 —— 仓里确实还有 20 个类带 `@Config(sdk = [36])`，
且它们全都不在 `FAST_TEST_FILTERS` 里（本轮实测）—— 但那不是这条结论的原因。

该记的教训：**纠正一条旧结论前，先确认那条结论当时的前提是否还成立**。同一句话在改动前后
可以一会儿真、一会儿假；用新状态去否证旧判断，等于把「已经修好了」记成「当初看错了」。

### 4. 真正的门禁覆盖：891 / 3248（27.4%）

既然跑了一次全量，就顺手量了真实覆盖率。统计口径写进 `tools/gate_coverage.py`（只读、可复算）：

| | 数量 |
|---|---|
| 全量用例（468 个类） | 3,248 |
| 失败 | 442（全部是 Robolectric native runtime） |
| 跳过 | 9 |
| 通过 | 2,797 |
| 门禁实际执行 | **891（27.4%，失败 0）** |
| 门禁外通过 | 1,906 |

这两个数是修完 §32.48 之后重跑的。修复前本节初版写的是「876 / 门禁外 1,929 / 78 个类」，
三处都要纠：

- 876 是 `35a1408` 之前的口径（少 6 例 `ToolCatalogUiTest`、1 例 §32.46 新用例、8 例
  `AgentToolRequirementsTest`）；
- 1,929 把 9 个 `skipped` 算成了通过（3248 − 443 − 876 = 1929）；
- 78 是数类时的口径问题，按 XML 的 `testsuite` 实测 **76** 个类有失败。

更要紧的是原因也不「统一」：修复前 443 条失败里 442 条是
`The Robolectric native runtime is not supported on Linux (aarch64)`，剩下 1 条是
`AgentToolRequirementsTest` 的真实断言失败 —— **门禁只覆盖四分之一，已经在丢缺陷**（§32.48）。

这不是本轮能解决的（要消除 Robolectric 依赖或换 x86_64 环境），但必须写下来：
**「门禁 891/0」不等于「全部测试通过」**。

### 5. 边界

- 分配量是 JVM（HotSpot）上的线程分配计数，不等于真机堆占用；真机还受 Compose 快照、OkHttp
  缓冲、GC 策略影响；
- 没有真机长会话，所以仍然**不能**说 OOM 已修；
- 探针是临时文件，已删除；方法可复现（`ThreadMXBean.getThreadAllocatedBytes` + 8435 条消息 + 100 步追加）。

## §32.48 「门禁只覆盖 27%」不是统计数字，它已经藏了一个真 RED（2026-11-01，承接上节）

### 1. 怎么撞见的：给失败分类时多看了一眼

上节要写「443 条失败原因统一是 Robolectric」，落笔前按 `failure@message` 分组数了一遍 ——
442 条确实是 `The Robolectric native runtime is not supported on Linux (aarch64)`，
**剩下 1 条是裸的 `java.lang.AssertionError`，没有 message**。

如果直接照抄「统一」，这条就被抹掉了 —— 它恰好不是环境噪声。

### 2. 定位：一条断言，一个 root 路径

`AgentToolRequirementsTest.rootlessProjectionRemovesPrivilegedToolsAndNarrowsMixedSchemasWithoutMutatingSource`
第 50 行：

```kotlin
assertFalse(projected.toString().contains("/data/local/tmp/eta"))
```

单独跑复现：`8 tests completed, 1 failed`，就是这一行。

根因在 `AgentSelfAuditToolCatalog.kt`：`eta_self_audit` 的 `source_root` 描述里写死了
「不传则自动探测 `/data/local/tmp/eta/eta-v2`」。这个工具是 `RootRequirement.PARTIAL`
（无 root 时仍保留，用来查堆压力与权限缺口），而 `projectUnprivileged` 只对
`read_image` / `press_key` / `search_personal_orders` 三个工具做了无 root 改写，没管它 ——
于是无 root 的 schema 里带着 root 侧路径，正好踩中测试守的契约。

引入这一行的提交是 `d9e3b2a`（§32.39 加 `eta_self_audit`）：**新工具进了目录，但没人给它补
无 root 版本**；守它的测试当时也不在门禁里，所以两边都不知道。

### 3. 改法

- `projectUnprivileged` 加一条 `eta_self_audit` 分支：无 root 时把 `source_root` 的描述换成
  不含路径的版本（与已有的三条改写同一机制）；
- 把 `AgentToolRequirementsTest` 加进 `FAST_TEST_FILTERS`（纯 JVM，8 例，跑得动）；
- `tools/preinstall-gate.sh` 的第 1 段也加上它 —— 装前门禁是另一条独立路径，两边都要覆盖。

### 4. 结果

- 单类：`8/0`；
- 全量：失败 443 → **442**（剩下的全是 Robolectric 环境噪声，没有真失败）；
- 门禁 `STAGES=test,lint,assemble` 全绿：**891 / 0**｜lint 无新增、baseline 194 命中｜
  包 `Eta-v3.3.5-debug.apk` `33,588,875` 字节 sha256 `85d726a48fab01ec…`；
- 包大小与 §32.46 那轮**完全相同**，但 dex 里能搜到新描述、sha256 也不同 —— 只记数字，
  不拿大小当「没改动」的证据；
- 顺手把覆盖率统计固化成 `tools/gate_coverage.py`（只读，逐 testcase 判定）。

### 5. 教训

1. **「统一原因」这类措辞要先分组数一遍**。443 条里 442 条一样，就差一条 —— 而这一条是唯一
   有价值的。写「全部是环境噪声」比写错一个数字的危害更大：它会把真缺陷一起归档成噪声。
2. **「门禁绿」的代价是可量化的**：891 例绿、2357 例没在本机执行（442 跑不了 + 9 跳过 +
   1906 能跑但没被选中）。被漏掉的缺陷不会因为没跑就消失。
3. **新工具加进目录时，要顺口问一句「无 root 版本长什么样」**，并且**同时**把它加进门禁 ——
   两件事只要落一件，另一件就会在别处以「门禁绿 + 全量红」的形式出现。

## §32.49 把「能跑但没人跑」的 2754 例做成第二档门禁（2026-11-01，承接上节）

### 1. 动机：上节那 1906 例既然能跑，为什么不跑

§32.48 的数字摆在那里：891 例进门禁、1906 例「本机能跑通但没被选中」。这不是资源不够 ——
全量跑一遍单测只要 1 分多钟，真正的问题是**默认门禁的名单是一只手动维护的白名单**，
新测试类不加进去就等于不存在。

### 2. 先确认这些用例是「稳定能跑」的，而不是偶然

- 第 1 轮：全量跑 → 全通过类 **385 个 / 2,754 例**；
- 第 2 轮：`--rerun-tasks` 再跑一遍 → 同样 385 类 / 2,754 例，两轮名单 `diff` **完全一致**；
- 剩下的 494 例 = 442 失败 + 9 跳过 + 43 个「所在类里有失败/跳过用例」的通过用例。

### 3. 落地

- 名单落盘 `tools/wide-tests.txt`（385 行，排序、每行一个类）；
  刷新：`python3 tools/gate_coverage.py --write-wide`（读当前 test-results，非全量结果时写出的名单会很小，
  所以必须先自己跑一轮全量）；
- `tools/gate.sh` 新增 `STAGES=test-wide`：名单展开成 `--tests`，**并上原来的 FAST 白名单**
  （wide 名单排除了「有 skip 的类」，其中几个类里有门禁已覆盖的用例；`--tests` 是并集）；
- 默认 `test` 档不变。

### 4. 实测

| 档 | 用例 | 耗时 | 覆盖 |
|---|---|---|---|
| `test`（默认） | 891 | 58 秒 | 27.4% |
| `test-wide` | **2,761** | **76 秒** | **85.0%** |

只多 20 秒左右，换来 3 倍覆盖 —— 没把它设成默认，是留给「本地快速迭代」；发版前跑 wide。

### 5. 为什么不全自动（诚实交代）

`wide-tests.txt` 是**静态名单**：新写的测试类不会自己进去，仍要刷新一次。这意味着它没有根治
「新增测试类没人跑」，只是把漏网面积从 73% 压到 15%。要根治得改成「全跑 + 排除跑不了的类」，
那需要动 `build.gradle` 的 test filter，风险比收益大，本轮没做。

### 6. 边界

- 名单只代表「这台机器、这套 JDK、今天」跑通；换机器/换 JDK 可能不一样，所以
  `--write-wide` 的输出里写明「跨版本/跨机器需重跑确认」；
- 43 个「混合类里的通过用例」被按类排除了，属于已知损失；
- wide 档还没接进 `ci.sh` / `preinstall-gate.sh`：装前门禁仍跑它自己的小集合；
- 跑完 wide 档后 `test-results` 里只剩 385 类，想复算覆盖率得重新全量跑。

## §32.50 把「派出去就忘」补上：会话级子代理任务台账（2026-11-01）

### 1. 动机：super-agent 的调度层缺了「记录」

把 DeepSeek super-agent 的调度拆成四层，对着 Eta 的现状看：

| super-agent 的层 | Eta 现状 | 本批 |
|---|---|---|
| 主 Agent 调度子 Agent | 有 `subagent`（同轮同步执行）+ `resume`（按会话隔离） | — |
| 任务台账 / 状态查询 | **没有**：子代理结果只活在一轮的工具结果里 | 本轮补 `tasks` |
| 后台 worker（长任务） | 有 `terminal_job` / `terminal_daemon` / `acp_delegate`，但各有一套状态 | 下一批 |
| 事件触发（automation） | 没有 | 后续 |

缺台账的具体后果：第二轮想接着用一个子代理，模型必须自己记得 `sub_id` —— 它记不住
（工具结果会被压缩 / 淘汰），用户也看不到「刚才那个还在跑吗」。

### 2. 落地

- 新文件：`AgentTaskRecord`（单条）、`AgentTaskLedger`（32 条上限、按开始时间倒序、同 id 覆盖）、
  `AgentTaskLedgerStore`（`files/agent/tasks/<sha256(scope)>.json`，`.tmp` + rename，64KB 上限）。
- 写入：`SubagentExecutor` 四个出口 —— 开始 / 完成 / 失败 / 取消；模型配置加载失败也补一条
  FAILED，否则台账会永久停在 RUNNING。全部 `runCatching`：台账是旁路记录，写不进去不能拖垮子代理。
- 读取：`tasks` 工具（只读，`status` 过滤、`limit` 默认 10 / 上限 32）。**只读是刻意的** ——
  一份能被模型改写的「执行状态」既不能用来判断「还有谁在跑」，也会和子代理会话的真实状态矛盾。
- 并发：锁是**进程级一把**（`AgentTaskLedgerStore` 的 companion），不是每实例一把 ——
  子代理执行器与工具执行器是两个实例，指向同一批文件。
- 接线：`AgentToolCatalog`、`ToolArgumentSchema`、`AgentToolRequirements`（NONE）、
  `AgentPlanModePolicy`（只读白名单）、`AgentOverlayText` + 三语文案、`AgentToolsCatalogUi`、
  `ToolIcons`、`AgentTraceFormatter`（卡片摘要）。

### 3. 实测

- 新增 25 例纯 JVM 单测（11 台账模型 / 8 存储 / 6 目录），全过；
- 门禁 `STAGES=test,lint,assemble`：**916 / 0**（原 891，+25）｜lint 无新增、baseline **194** 命中｜
  死文案 0 条；
- 包：`Eta-v3.3.5-debug.apk` **34,544,806 字节**，sha256 `b21ddc20a551f6ab…`。
  比文档里 §32.48 记的 33,588,875 大 955,931 字节 —— **原因未查明，不做推测**
  （可能那轮记录的是更早的产物；本轮没有改依赖、没有加资源）。

### 4. 这轮踩到的两个坑

1. **测试夹具自己写错了 `startedAt`**：并发用例拿 `index` 当开始时间，`sub-0` 的
   `started_at=0` 被读端当成坏记录丢掉，表现成「8 条并发只留下 7 条」—— 差点被读成锁失效。
   读端拒收非法值是对的，错在夹具；也提醒「读端丢弃」必须有明确契约（`AgentTaskRecord.fromJson`
   里 `startedAt <= 0` 即丢）。
2. **`gate.sh` 的「JDK 固定」其实不生效**：`gradle/gradle-daemon-jvm.properties` 写着
   `toolchainVersion=25`，daemon 永远跑 JDK 25，`export JAVA_HOME=jdk17` 只作用于客户端。
   本轮 lint 阶段约 8 分钟，daemon 进程确为 `java-25-openjdk`（`ps` 实测）。**没在本轮改** ——
   动 toolchain 影响整个构建，先记录下来。

### 5. 边界（未验证，别当已完成）

- **真机往返未验证**：没有在设备上让模型实际调 `tasks`、再据其 `resume` 一个子代理；
- 台账不跨会话（同一批的第二轮已把它接进系统提示，见 §32.51）；进程被杀残留的 `running` 不自愈；
- `ToolCatalogUiTest` 是 Robolectric，本机跑不了；「每个工具都有专属图标 / 显示名」这条
  由新增的映射本身保证，本机门禁只覆盖 `ToolIconMappingTest` 钉住的那几条。

## §32.51 台账进系统提示：模型不用调工具也知道派过谁（2026-11-01）

§32.50 把台账写进磁盘、用 `tasks` 读回来，但**读不读由模型决定**。跨轮之后它看不见上一轮的工具结果，
常见后果是「重新派一个一样的子代理」，或者忘了那个还在跑。

### 1. 做法

- `AgentTaskLedger.promptSection(maxLines = 8)`：`<subagent_tasks>` 段 = 一行汇总 + 逐条
  `[status] 标题 · id — 摘要首行`；**运行中的先占名额**，超过 8 条只留一句指向 `tasks`。
- 注入点：`AgentPromptBuilder.buildSystemMessages`，紧跟 `<todo_plan>`、在项目规则之前
  （台账与清单同属「当前工作状态」；它变勤，放这里只影响它之后的字节）。空台账不注入；
  剧情会话不注入（与 goal / todo 同一约定）。
- 取值：`AgentRuntimeRunExecutor` 每次 run 读一次（读失败当没有台账，记
  `agent_task_ledger_prompt_failed`），同一次 run 内不重读 —— 中途重算会让已发出的前缀失效。
- 签名：`buildInitialMessages` / `buildSystemMessages` 新增 `taskLedgerPlan`；`AgentModelClient`
  三处调用改成命名参数。原来是位置传参，插入新参数会让 `mode` 静默落到台账位上。

### 2. 验证（本机）

| 项 | 结果 |
|---|---|
| 定向 4 类 | 43 例 0 失败（台账渲染 +3、系统提示注入 +3） |
| `STAGES=test,lint,assemble` | 934 例 0 失败；lint baseline 194；死文案 0 |
| `STAGES=test-wide` | 2792 例 0 失败 |
| 包 | 34,544,806 字节 `05aea3c93e8c84e9…`（大小与 §32.50 相同，sha256 不同） |

### 3. 顺带

`AgentPromptBuilderTest` 此前只在 `test-wide` 名单里。系统提示注入位置是每轮都走的核心路径，
这次一并加进 `gate.sh` 的 fast 列表，所以 fast 用例数 916 → 934（新增 6 例，另 12 例是补进 fast 的旧例）。

### 4. 仍未验证

真机上没有跑过「模型读到这段后真的去 `resume`，而不是重新派一个」。仍是纯 JVM + 门禁验证。

## §32.52 进程重启后的孤儿条目：用写者身份代替时间判据（2026-11-01）

§32.50 曾把「stale running 不自愈」列为已知边界，理由是「判据只能靠时间，误伤长跑的风险大于收益」。
这个前提是错的：子代理只在本进程内执行，所以**写者身份**就是精确判据。

### 1. 做法

- 台账文件顶层加 `owner`（`ProcessOwner.current`，进程级 UUID 前 16 位，仅落盘时写）。
- `AgentTaskLedgerStore.load()`：`owner` 与当前进程不一致时调用
  `AgentTaskLedger.abandonRunning()`，把 RUNNING 改成 CANCELLED（摘要为空才补说明）。
- 读路径不写盘；下一次 `recordStarted` / `recordFinished` 落盘时自然带上新 owner。
- 老文件（无 `owner`）按上一个进程处理 —— 升级必然伴随进程重启。
- 同进程内的 RUNNING 原样保留：卡死是另一类问题，用时间猜只会把「跑得久」当成「已死」。

### 2. 验证（本机）

| 项 | 结果 |
|---|---|
| 定向 3 类 | 34 例 0 失败（Store +5、Ledger +1） |
| `STAGES=test,lint,assemble` | 940 例 0 失败；lint baseline 194；死文案 0 |
| `STAGES=test-wide` | 2798 例 0 失败 |
| 包 | 34,544,806 字节 `20522adb1e9a9404…`（连续三轮大小相同、sha256 不同，原因未查） |

### 3. 边界

- 前提是「同一会话作用域只由一个进程写」；语音进程用 runId 作作用域，不共用文件。
- 卡在活进程里的子代理仍显示 running（没有心跳机制）。
- 真机未验证：设备上装的还是旧版本（`files/agent/tasks` 目录不存在），只有单测覆盖。

### 4. 顺带

修掉 §32.51 文档里的一处重复：「不跨会话」条目被写了两遍（上一轮替换脚本把锚点前后各留了一份）。

## §32.53 台账上界面：谁在跑，用户自己也能看见（2026-11-01）

前三批把台账写进磁盘、塞进系统提示、跨重启自愈，但**用户一次都看不到它**：`subagent` 的结果只挂在
各自的工具消息里，会话一长就滚出屏幕，也回答不了「刚才那个还在跑吗」。这一批只做可见性，不动调度语义。

### 1. 做法

- 卡片：`AgentTaskLedgerCard`，位置在底部常驻区 `AgentTodoPlanCard` 之后；收起一行
  `子代理任务 · 已完成/总数`（运行中主色、失败错误色、已取消弱化），展开最多 8 条 `标题` + `状态 · id`，
  超出用 `+N`。
- 数据：`AgentHomeScreen` 用 `rememberTaskLedger(conversationKey)` 读 store；空台账 / 没有会话作用域时不显示。
- 刷新：`AgentTaskLedgerStore.changes`（`StateFlow<Long>`，落盘成功与 `clear()` 成功各递增一次，与
  `AgentTodoStore.changes` 同一约定）。**不按消息条数刷新** —— 子代理结束只改磁盘状态，消息列表不变。
- 只读：不提供 resume / 取消。工具调用必须经过模型，界面直接触发会让「谁在调度」变成两套真相。
- 计数抽成 `AgentTaskLedger.cardSummary()`（纯函数，复用 `counts()`）：界面本身在本机跑不了，
  纯映射能进 JVM 单测。

### 2. 验证（本机）

| 项 | 结果 |
|---|---|
| 定向 3 类 | 35 例 0 失败（Summary +5、Store +2） |
| `STAGES=test,lint,assemble` | 947 例 0 失败；lint baseline 194；死文案 0 |
| `STAGES=test-wide` | 2805 例 0 失败 |
| fast 名单 | 940 → 947（新类已进 `gate.sh`，否则绿灯来自「没跑到」） |
| 包 | 34,557,382 字节 `b1326528a5f8b862…` |

### 3. 边界

- **卡片没有真机截图**：设备上装的仍是旧版本（`files/agent/tasks` 目录都不存在），装机重启会打断
  当前会话，没有擅自做。界面层目前只有编译 + 纯逻辑单测。
- 底部常驻区不可滚动：目标卡 + 清单卡 + 台账卡 + 追问卡同时出现时高度会叠加，小屏未实测。
- 台账按会话作用域存，卡片只显示当前会话；语音入口（runId 作用域）没有对应界面。
- 卡片只有状态与 id，不显示开始 / 结束时间。

### 4. 顺带

收起行原本是 Row 里并排三个 Text（标题 / 已完成 / 运行中）。Row 不换行，前两个占满后第三个被挤成
零宽**直接看不见**；改成同一个 `Text` 的 `AnnotatedString` 分段着色，换行交给 Text 自己处理。

## §32.54 后台任务上提示：模型隔几轮也知道 daemon 还在跑（2026-11-01）

前四批管的是「派出去的子代理」。`terminal_job` 与 `terminal_daemon` 是另一类：它们**活过一轮对话**，
但模型只看得到自己最近一次工具结果。隔几轮回来，它不知道「还有个 daemon 在跑」—— 要么重复启动
同一份服务，要么让用户自己去终端页翻。这一批只做提示，不动任务本身的生命周期。

### 1. 做法

- 新增 `BackgroundTasksSection`：`running()` 取 `completedAt == null` 的记录并按 `startedAt` 升序；
  `render(tasks, now)` 输出 `<background_tasks>…</background_tasks>`，含未结束条数、job / daemon 各几条、
  最多 6 个 id（超出写「另有 N 条未列出」）、最早一条的年龄，并写明「没有做存活探测，
  要读日志 / 确认状态 / 停止请用 terminal_job / terminal_daemon 的 list」。
- 新增 `DetachedTaskSupervisor.readRecords(file)`：**只读快照**。不探活、不认领、不补结束时间、不清理，
  坏文件 / 坏条目按「没有」处理、不在读路径改写；超过 256KB 整份跳过。它与 `list()` 的差别就是没有副作用
  —— 系统提示每轮都构建，不能为此起 shell 或写盘。
- 注入：`AgentRuntimeRunExecutor` 在 run 开始时读一次，`AgentPromptBuilder` 把它排在子代理台账之后、
  项目规则之前；条件为 `roleplayContext == null && config.terminalTools && 段非空`。
  `AgentModelClient` 透传，root 变化重建系统消息时同样带上。
- **不带命令正文与描述**：记录里的命令可能是用户在终端里手敲的（含路径、令牌），
  而系统提示会被发到模型服务商；要看内容就让模型显式调 list。
- 读失败兜底：`runCatching` 包住，按空段处理并 `warnThrottled("agent_background_tasks_prompt_failed")`。

### 2. 验证（本机）

| 项 | 结果 |
|---|---|
| 定向 2 类 | 29 例 0 失败（BackgroundTasksSectionTest +10、AgentPromptBuilderTest +3） |
| `STAGES=test,lint,assemble` | 961 例 0 失败；lint baseline 194；死文案 0；扫描 1330 文件 |
| `STAGES=test-wide` | 2819 例 0 失败 |
| 包 | 34,557,382 字节 `7005fe2967038977…`；dex 含 `BackgroundTasksSection`，测试类未进包 |

### 3. 边界

- **真机未验证**：设备上装的仍是旧版本（`files/agent/tasks` 目录都不存在）。
  「提示里真的出现这一段」「关掉终端工具后不再出现」都只有 JVM 单测。
- **模型行为未验证**：没有实测过「模型读到这段后不再重复启动同一个 daemon」。段落只提供事实，
  改变行为是另一回事。
- 不探活：被系统杀掉但记录里没标结束的任务仍会计入，直到某次 `list()` / 写入触发清理；
  记录文件涨到 256KB 以上时整段消失，不是只丢几条。
- 只看得到记录，看不到资源占用（CPU / 内存）。
- 6 个 id 是固定上限，任务更多时模型必须自己调 list 才能拿到完整清单。

## §32.55 后台任务结束事件：跑完了也要说一声（2026-11-01）

§32.54 报的是「现在还有谁在跑」。任务一旦结束就从那一段消失：下一轮提示只说「没有未结束的任务」，
模型不知道「你让我跑的那个已经结束、日志在这里」。用户不主动问，这一趟就白跑了。这一批补「收尾」，
仍然只做提示，不做自动触发。

### 1. 做法

- `BackgroundEvents`：`collect(records, deliveredUntil)` 取已结束且晚于水位线的记录（按结束时间升序），
  `render` 输出 `<background_events>`：最多 6 条 `job_x（job）：已完成 · 12 分钟前`，超出写「另有 N 条」；
  不认识的状态词原样带上，不猜「成功」；段末写明「只是记录，不代表任务成功」。
- `BackgroundEventStore`：按会话作用域存**水位线**（`delivered_until`）而不是 id 列表 ——
  事件不需要重放，只需要「别再报一次」；O(1) 状态不会因为条数上限把旧 id 挤掉后重报。
  只前进不后退：两个 run 并发时，后完成的那次可能带着更早的事件，直接覆盖会让已告知的又重报。
- 接线：`AgentRuntimeRunExecutor` 在 run 开始时读一次记录（任务段与事件段共用这份快照），
  渲染成功后推进水位线；推进失败只会让下一次重报一遍，不丢事件。
- 注入位置紧跟 `<background_tasks>`，条件相同（非剧情会话、`config.terminalTools`、段非空）。

### 2. 验证（本机）

| 项 | 结果 |
|---|---|
| 定向 3 类 | 40 例 0 失败（BackgroundEventsTest +10、BackgroundEventStoreTest +9、AgentPromptBuilderTest +3） |
| `STAGES=test,lint,assemble` | 982 例 0 失败；lint baseline 194；死文案 0；扫描 1334 文件 |
| `STAGES=test-wide` | 2840 例 0 失败 |
| 包 | 34,557,382 字节 `49399305068fb9a9…`；dex 含 `BackgroundEvents` 与 `BackgroundEventStore`，测试类未进包 |

### 3. 边界

- 真机未验证：设备上仍是旧版本（`files/agent` 下没有 tasks / background_events 目录）。
- 模型行为未验证：没有实测过「看到结束事件后会去读日志并接着处理」。
- 不探活：daemon 被 SIGKILL 时 `completedAt` 仍是 null，不产生事件，要等下一次 `list()` 才可能发现。
- 水位线按会话作用域：多个会话各自看到同一事件一次；同一毫秒结束的多条被一起压住。
- 没有界面展示，也没有「已读」标记；初次（无水位线文件）会把记录里所有已结束任务当新事件，最多列 6 条。
- 不做触发 / 自动续跑：那涉及循环保护与计费边界，另开一轮。

### 4. 顺带

- 包大小连续三轮都是 34,557,382 字节，但 sha256 每轮不同（本轮新增了两个 Kotlin 文件）。
  **大小相同的原因未查明**，不做推测；判断产物是否更新以 sha256 与 dex 内的类为准。
- 定向测试第一版就红了一次：测试夹具 `store()` 的默认参数每次都 `newFolder("events")`，
  同一用例里调用两次就撞名抛 IOException。这是夹具问题，不是实现问题；
  但它提醒「默认参数里有副作用」在测试里同样会咬人。

## §32.56 后台任务上界面：用户也能看见谁在跑、最近谁结束了（2026-11-01）

### 1. 做了什么

§32.54 让模型知道「还有谁在跑」，§32.55 让模型知道「谁结束了」。这两段都只进提示 ——
用户想看，仍然要去终端页翻 `terminal_job` / `terminal_daemon` 的列表。这一批把同一份记录
做成聊天页底部常驻卡，**只读**，不动任务的生命周期。

- 入口：`AgentChatBody` 的底部常驻区，紧跟 `AgentTaskLedgerCard`；记录为空时整卡不出现。
- 数据：`DetachedTaskSupervisor.readRecords`（只读快照，与提示段同源）。新增
  `DetachedTaskSupervisor.changes`（`StateFlow<Long>`，**落盘成功才自增**）：任务结束只改记录、
  不产生会话消息，按消息刷新永远看不到收尾（与台账卡同一条教训）。
- 投影：`summarizeBackgroundTasks(records)` → `BackgroundTasksView`（job / daemon 计数、
  运行中按开始时间升序最多 6 条、最近结束按完成时间倒序最多 3 条、已结束总数）。
  只把 id、类型、状态、年龄交给界面；命令正文、令牌、pid、日志路径都不进渲染路径。
- 行素材：`backgroundTaskLine(task, now)` 只给字段与毫秒年龄，时间格式化留在界面
  （运行中用 `DateUtils.formatElapsedTime`，结束用 `getRelativeTimeSpanString`）。
  纯逻辑里写中文，英文界面就会冒出中文时间。
- 文案：复用已有的 `daemon_status_*` 与 `tool_status_running`（三语齐全），不认识的状态码原样显示，
  不猜成「失败」；新增只有两条键 `background_tasks_finished`、`background_tasks_card_hint`（三语同步）。
- 只读：不提供停止 / 读日志按钮 —— 这些动作必须经过 `terminal_job` / `terminal_daemon`，
  界面直接触发会让「谁在调度」变成两套真相。

### 2. 顺手抓到自己写的一个「界面说假话」

第一版 `now` 是 `remember(records) { System.currentTimeMillis() }`：时间只在记录变化时算一次。
后果是运行中的一行写着「已运行 00:05」就永远停在 00:05，挂十分钟也不动 —— 与
`AgentToolActivity.rememberRunningSeconds` 当初修的是同一类问题（那里已经写明「静止的计时器就是假话」）。

改法：展开期间重算。有运行中任务 1 秒一跳（那一行显示的是「已运行多久」，秒级才有意义）；
只剩已结束的行时 30 秒一跳（那里是「多久以前」，一秒一跳纯属白重组）。收起时停掉 ——
收起状态本来就不显示时间，展开瞬间会立刻重算一次，所以不会先闪一下过期秒数。

### 3. 验证（本机）

| 项 | 结果 |
|---|---|
| 定向 2 类 | 20 例 0 失败（`BackgroundTasksViewTest` +8、`DetachedTaskSupervisorTest` +1） |
| `STAGES=test,lint,assemble` | 991 例 0 失败；lint baseline 194（183 命中 + 11 条依赖更新提示）；死文案 0；扫描 1337 文件 |
| `STAGES=test-wide` | 2849 例 0 失败 |
| 包 | 门禁产物 36,040,839 字节 `1ca8792824e17a99…`；删包重建 32,140,697 `55a29a55eaceb0c9…`（详见 §6） |

`changes` 的单测直接盯住了「落盘成功才通知」这条不变量：写入失败时计数不动，
界面就不会拿着尚未落盘的记录去渲染。

### 4. 门禁自己的两个缺陷（这一批真正花时间的地方）

第一次跑门禁就红了，且两次都不是业务代码：

1. **`GradleDependency` 把门禁变红**：上游发版后 lint 报 4 条「有新版本可用」
   （kotlin 2.4.20→2.4.21、markdown 0.7.16→0.7.18、hiddenapibypass 6.1→6.2）。
   这类检查依赖远端元数据，与代码质量无关，离线构建也不可能自动升级；它**还带 autofix**
   （lint 打印过「This issue has been automatically fixed」）。升级依赖应当是一次显式决定，
   不该由 lint 每轮捎带决定 → 在 `app/build.gradle.kts` 的 lint 块 `disable += "GradleDependency"` 并写明原因。
2. **对账脚本只认得一个类型名**：`tools/lint_baseline_audit.py` 报 `LintBaselineFixed`，
   但 `NETWORK_DEPENDENT_TYPES` 里只有 `GradleDependency`，而 lint 的消息里还带着
   `AndroidGradlePluginVersion`、`NewerVersionAvailable`，且类型名末尾追加了 ` (N)`
   （未复现条数，不是类型名的一部分）。→ 三个类型一起视为「依赖更新提示」，
   并在解析时剥掉尾部的 ` (N)`。

改完**不敢直接信**，因为门禁最怕「看起来在拦、其实永远放行」。喂了四个已知答案的合成输入：

| 输入 | 期望 | 实测 |
|---|---|---|
| 11 条依赖类陈旧 + 19 条 baseline 过滤 8 条 | 通过 | 通过（打印「另有 11 条…已放过」） |
| 陈旧条目里混入 `UnusedResources (2)` | 红 | 红（`[LintBaselineFixed]`） |
| baseline 19 条但只过滤 18 条 | 红（账目不平） | 红（差 1） |
| 报告里有新增问题 | 红 | 红（`[UnusedResources] b.kt:9`） |

也就是说：放过**只**覆盖「整条消息里的未复现类型全是依赖更新检查」这一种情况，
真类型一旦混进来照样红。

### 5. 边界（如实记录）

- **真机未验证**：设备上仍是旧版本（`files/agent` 下没有 `detached_tasks.json`），
  卡片的布局、小屏高度、长 id 截断、计时器是否真的每秒跳，都只有编译 + 纯逻辑单测。
- 底部常驻区仍不可滚动（与 §32.53 同一处）：目标卡 + 清单卡 + 台账卡 + 后台任务卡 + 追问卡
  同时出现时高度会叠加。
- 刷新只覆盖本进程写入：别的进程启动 / 结束的任务，要等下一次写入或重进界面才出现。
- 卡片只列最近 3 条结束；完整历史仍在 `terminal_job` / `terminal_daemon` 的 list 里。
- 展开 / 收起用 `rememberSaveable`，只在进程内保留，重进页面会收起。
- 不显示退出码、环境、cwd；30 秒档的「多久以前」最多差 30 秒。
- 不探活（沿用 §32.54 的边界）：被 SIGKILL 的 daemon 仍显示为运行中。
- 不做事件触发 / 自动续跑：那涉及循环保护与计费边界，另开一轮（与 §32.55 一致）。

### 6. 顺带

- **包体积不是可复现的量**（这一轮终于有证据）：同一份源码，门禁那次打出 36,040,839 字节，
  删掉 APK 再 `assembleDebug` 打出 32,140,697 字节，连做两次都是 32,140,697（sha 也一致）。
  363 个条目两边相同，差异全在 dex：解压后 92,067,582 vs 87,537,288 —— 多出来的约 4.5 MB
  在 dex 里（是否是增量 dex 合并带进了旧分块，本轮没有逐块核对，不做结论）。
  **结论只到这一步**：包体积不能当「产物是否更新」的证据，看 sha + dex 内的类。
  前几轮的体积对比也因此失去意义（同样的源码可能是 32.1 MB 或 36.0 MB）；而「大小相同、sha 不同」
  那几次体积是一致的，这一条**仍无法解释**，继续留着。
- baseline 仍是 194 条，其中 11 条是依赖更新检查留下的陈旧条目（`GradleDependency` 已被关闭，
  另外两类的远端元数据不稳定）。对账脚本会把它们打印成提示行，而不是静默吞掉。

## §32.57 任务结束后要不要接着跑：交给用户自己设（2026-11-01）

### 1. 为什么是「可设置」而不是我替他选

上一节交付了卡片：能看到「谁在跑、谁刚结束」，看完还得自己去开一轮。把「接着跑」接上时
有两条路 —— 用户点一下才发，或者任务一结束就自动发。差别不只是手感：

- 自动续跑会在用户不在场时**花掉额度**；
- 「模型派任务 → 任务结束 → 自动续跑 → 模型又派任务」是一个能**自我维持的循环**。

所以不做二选一，而是做成用户可设的两项，并且默认「点一下才发」。

### 2. 做了什么

| 项 | 键 / 位置 | 默认 |
|---|---|---|
| 卡片上提供「继续」 | `AGENT_BACKGROUND_RESUME_BUTTON` | **开** |
| 任务结束后自动继续 | `AGENT_BACKGROUND_RESUME_AUTO` | **关** |

- 手动：卡片里每条已结束任务一个「继续」；点击走 `onSubmit`，与用户手打一条消息
  **完全同一条路径**（运行中会排队，不另开一条隐藏通道）。
- 提示词只带 id 与类型（`job` / `daemon`），不夹带命令正文与描述：
  界面不替模型编造输出，让它自己用 `terminal_job` / `terminal_daemon` 的 logs 去读。
- 自动：判定抽成纯逻辑 `BackgroundResume.planAutoResume(...)`，四条规则：
  1. 只认卡片打开**之后**结束的任务（否则一进旧会话，历史里所有已结束任务会被当成新事件续一遍）；
  2. 续过的 id 不再续 —— 手动点过也算，避免「刚点完又自动发一条」；
  3. 一次只发一条：第二条要等这一轮跑完、界面重新空闲；
  4. 预算 `AutoResumeMaxPerSession = 2`，用完只返回 `skipped`、不再发送。
- 运行中不发：`canSend = !isStreaming` 同时管住「继续」按钮与自动路径。

### 3. 验证（本机）

| 项 | 结果 |
|---|---|
| 定向 2 类 | 16 例 0 失败（`BackgroundResumeTest` +8、`BackgroundTasksViewTest` 8 例） |
| `STAGES=test,lint,assemble` | 999 例 0 失败；lint baseline 194（183 命中 + 11 条依赖提示）；死文案 0 |
| `STAGES=test-wide` | 2857 例 0 失败 |
| 包 | 34,577,450 字节 `a0c977288737b856…`；dex 含 `BackgroundResume` 与 `agent_background_resume_auto`，测试类未进包 |

`BackgroundResumeTest` 的 8 条用例：空记录、运行中不算候选、打开之前结束的不算（含边界
「恰好等于」）、已续过的不再续、取最早结束的那条、预算用完、超预算不越界、上限 0 永不发。

### 4. 踩到的坑（同一类错误的第二次）

第一版用 `Prefs.isEnabled(key)` 读两个开关。它看起来就是「读配置」，但实现是：

```kotlin
val preferences = if (key in Keys.LOCAL_AGENT_KEYS) localAgent ?: remote else remote
```

也就是说**只有列进 `LOCAL_AGENT_KEYS` 的键才读本地**，其余一律读 remote；而设置页的
`SwitchPref` 写的是本地 prefs（`eta_agent_preferences`）。结果：开关点了能写上，
卡片永远读默认值 —— 表现为「设置没反应」。

改成直接读 `Prefs.localAgentPreferences(context)`（并在注释里写明为什么不能用 `isEnabled`）。

这与上一节的对账脚本（只认得 `GradleDependency` 一个类型名）是同一类：
**名字像「读配置 / 做对账」的东西，语义范围往往比名字窄**。两次都是先写「看起来对」的调用，
再靠「读实现」才发现范围不对。下一轮遇到这种通用命名的工具函数，先看它认哪些键 / 哪些类型。

### 5. 边界（如实记录）

- **真机未验证**：设备上仍是旧版本。开关是否真的写进本地 prefs、按钮点下去是否真的发出、
  自动续跑是否真的在结束那一刻触发，只有编译 + 纯逻辑单测。
- **自动只在「会话界面开着」时生效**：判断挂在 Compose 副作用里，切走、锁屏、进程被回收都不触发。
  所以它的实际语义是「你看着的时候自动」，不是后台托管。
- 计数与已续集合都存在进程内（`remember`）：重进界面或进程重启清零。防线只剩
  「只认打开之后的结束」，不是 §32.55 那种持久水位线。
- 记录里没有「任务属于哪个会话」：别的会话起的任务结束，也会成为本会话的候选。
- 不区分成功 / 失败：失败的任务同样会被续（让模型自己读日志判断该不该继续）。
- 没有计费提示弹窗，只在设置摘要写「会消耗额度」；上限 2 是硬闸，用户无法调高。
- 卡片仍只列最近 3 条结束：更早的结束任务没有「继续」按钮。

### 6. 顺带：快跑抓不到的那一条

补上两个默认值之后，快跑（`STAGES=test,lint,assemble`）照样 999/0 绿，但宽跑红了一条：

```
PrefsDefaultsTest > defaultsMatchRecommendedInitialSettings FAILED
java.lang.AssertionError at PrefsDefaultsTest.kt:10
2857 tests completed, 1 failed, 2 skipped
```

原因是那条用例把整张 `BOOLEAN_DEFAULTS` 钉成了快照（键与默认值全列出来）。这不是缺陷，
而是**快门口禁的已知盲区**：`FAST_TEST_FILTERS` 不包含 `config.*` 下的快照类用例。
把两条新键（`true` / `false`）补进快照后，宽跑 2857/0 转绿。

值得记住的是顺序：**先跑快跑得到的「全绿」不等于可以提交** —— 这次要是不跑宽跑，
默认值快照就带着旧内容进仓库了，下一个人加开关时会拿到一条莫名其妙的红。

## §32.58 真机先打脸：那两条防线其实一条都没站住（2026-11-01，承接上节）

### 1. 真机怎么发现的

第八批装 canary 上虚拟屏，手动点「继续」——模型确实收到了提示，自己调 `terminal_job list`
与 `terminal_daemon list`，回答「没有需要接着做的事」。手动路径是真的通了。

但同一轮跑完之后，「继续」按钮**又出现了**。原因是卡片把「续过哪些」放在
`remember { mutableStateListOf<String>() }` 里：重组 / 运行结束后这份状态丢了，
`task.id !in resumed` 恒为真，于是：

- 「同一条不再续」失效；
- 「最多 2 次」也失效（计数 `autoResumed` 是另一个 `remember`，一起归零）。

按文档写的「进程内生效、重启清零」本来就不该用 `remember` —— 文档说的是「进程内」，
`remember` 给的是「这次组合内」。

### 2. 两步修

**第一步（commit `7406747`）**：把状态挪到进程级对象 `BackgroundResumeMemory`，
上限 64 条、超限丢最旧。单测 6 例。这一步只解决「重组丢状态」。

**第二步（本节）**：按会话作用域落盘。

| 组件 | 位置 | 作用 |
|---|---|---|
| `BackgroundResumeStore` | `agent/runtime/` | `files/agent/background_resume/<sha256(scope)>.json`，只存 `{"resumed": [...]}` |
| `BackgroundResumeScope` | `agent/terminal/` | 一条作用域的内存态：`resumed` 落盘、`autoResumed` 只在进程内 |
| `BackgroundResumeMemory` | `agent/terminal/` | 作用域 → Scope 的进程级表 |

几个刻意的取舍：

1. **只落盘「续过哪些」，不落盘次数**。次数是「这一轮的预算」，落盘会变成
   「自动续了 2 次」永久生效 —— 与 §122「一次失败即永久停用」同一类错误。
2. **读盘完成前不许判定**。`isLoaded` 之前集合是空的，拿它判「没续过」会把一条
   已经续过的任务再续一遍。自动续跑的 `LaunchedEffect` 因此把 `isLoaded` 当 key 并提前 return。
3. **坏文件当没有记录**。8KB 上限、JSON 坏、字段缺失、作用域空串，一律安静退回空集合：
   这个记忆的唯一用途是少显示一个按钮，读不出来就该退回原来的行为。
4. **作用域键沿用 `conversationId ?: runId`**（卡片接 `goalScopeId`，与台账 / 目标 / 待办同一个）。
   记录本身没有会话归属，但「在 A 会话续过」不该让 B 会话少一个按钮。

### 3. 验证（本机）

| 项 | 结果 |
|---|---|
| 定向 2 类 | **23 例 0 失败**（`BackgroundResumeMemoryTest` 12 + `BackgroundResumeStoreTest` 11） |
| `STAGES=test,lint,assemble` | **1022 例 0 失败**；lint baseline 194（183 命中 + 11 条依赖提示）；死文案 0 |
| `STAGES=test-wide` | **2880 例 0 失败** |
| 包 | 33,628,697 字节 `8c9537a2…` |

`BackgroundResumeStoreTest` 的 11 条：写读往返、未知作用域、后写覆盖、作用域隔离、
超上限只留最新、空白 id 丢弃、重复 id 保留最近位置、空作用域不落盘、坏文件当空且能再写、
超限文件忽略、字段缺失当空。

### 4. 又一次被 lint 抓住

第一版把 `scopeId` 放在 `modifier` 前面，lint 直接红：

```
BackgroundTasksCard.kt:92: Error: Modifier parameter should be the first optional parameter
[ModifierParameter from androidx.compose.ui]
```

`STAGES=lint` 单跑复现 → 改成 `modifier` 在前、`scopeId` 在后，重跑全绿。
教训很直白：**Compose 组件的可选参数顺序是硬约束**，加参数时 Modifier 必须留在第一个可选位置。

### 5. 边界（如实记录）

- **已真机验证（2026-11-01 19:48~19:53，canary 覆盖安装）**，见第 6 节；但设置页两个开关
  的**可视化**仍未真机确认 —— 会话页「更多选项」里没有设置入口，本轮没有再往下找。
- **自动续跑仍只在「会话界面可见」时触发**：判断挂在 Compose 副作用里，切走 / 锁屏 / 回收都不触发。
- 作用域表进程内不回收：每个会话一个对象、每个对象最多 64 个 id；会话很多时会缓慢增长。
- 记录里没有「任务属于哪个会话」，所以别的会话起的任务结束仍是候选；
  只有「续过」这件事按会话分开。
- 不区分成功 / 失败；没有计费提示；上限 2 仍是硬闸。
- 跨进程并发（主进程 + 语音）只有「进程级一把锁 + rename」，没有文件锁：
  两个进程同时写同一作用域时最后一次 rename 胜出，可能丢掉另一个进程刚记的 id。
- 包体积又一次不可复现：同一份源码，两次打包 33,628,670 与 33,628,697 字节、sha256 不同。
  与本轮改动无关（改动只碰 Kotlin 源码），原因仍未查明。

### 6. 真机验证（2026-11-01 19:48~19:53，canary，虚拟屏 display 3）

装机：`Eta-v3.3.5-debug.apk` 32,159,217 字节 `d3c326b4…` → `pm install -r -d --user 0`。
`firstInstallTime` 仍是 2026-10-29 16:35:18（覆盖安装，没重置），`lastUpdateTime` 变 19:48:42；
设备侧 `base.apk` 的 sha256 与构建产物一致；21 个 dex 分卷里能搜到 `BackgroundResumeStore` /
`BackgroundResumeScope`，`BackgroundResumeStoreTest` 0 命中（测试类没进包）。

| 动作 | 观察 |
|---|---|
| 展开卡片，点 `job_d8947cdd` 的「继续」 | 消息流出现「后台任务 job_d8947cdd（job）已结束…」；模型回「没有要接着做的事」 |
| 同一时刻看磁盘 | `files/agent/background_resume/f12875e4….json` = `{"resumed":["job_d8947cdd"]}` |
| 轮次结束后看卡片 | `job_d8947cdd` 的「继续」消失，`job_410a1ca3` / `job_ab0c5232` 仍有（不是 `isStreaming` 的连带效果） |
| `am force-stop` 后冷启动到虚拟屏 | 新 pid、内存表为空；展开卡片后 `job_d8947cdd` 仍无「继续」，另两条仍有 |

最后一行才是持久化真正要证明的东西：进程重建后这个状态只能来自磁盘。

**踩到的坑（方法学）**：一开始查 `/data/user/0/io.github.mangi.eta.canary/files/agent/background_resume`
得到「No such file or directory」，差点判成「没落盘」的真缺陷。实际是**这台设备的 root shell
在受限挂载命名空间里** —— `/data/user/0` 只有 2 个条目，连微信目录都看不见。
用 `nsenter -t 1 -m` 进 init 的命名空间后，目录和文件都在。
教训：**用 root 读应用私有目录前，先确认自己看的不是被隔离的视图**；
「目录不存在」与「没有权限」都可能是命名空间造成的假象。

顺带确认：主包 `io.github.mangi.eta` 的 `lastUpdateTime` 仍是 2026-10-31 22:09:21，全程未被触碰。

## §32.59 任务结束的触发点搬出界面，记录也认会话（2026-11-01）

### 1. 上一节末尾那两条「未做」，正是这一批

§32.58 第 5 节写着：

- 「自动续跑仍只在『会话界面可见』时触发（Compose 副作用），切走 / 锁屏 / 回收都不触发。」
- 「记录仍无会话归属：别的会话起的任务结束仍是候选。」

第一条是触发点位置问题，第二条是数据模型问题。两条都不是「再加个开关」能解决的：
判定还写在卡片里就一定受组合生命周期约束；记录不认主人就分不清「谁的任务」。

### 2. 记录加 `session_scope`

| 环节 | 变化 |
|---|---|
| `DetachedTask` | 新增 `sessionScope: String?`（JSON 键 `session_scope`，加在末尾，位置参数构造不受影响） |
| `DetachedTaskSupervisor.start` | 新增 `sessionScope: String? = null`；根守护 / 命令 / 用户守护三个构造点都带上 |
| `BackgroundTerminalJobs.start` | 透传 |
| `RootShellTerminalController` | 新增 `backgroundScopeId`（默认空串），`terminal_job` 与 `terminal_daemon` 两处 start 都传 |
| `AgentLocalTools` | 构造 controller 时传 `goalScopeId`（= `conversationId ?: runId`，与台账 / 目标 / 待办同一个键） |

兼容性：旧记录没有这个键 → `null`；空串、空白与字面 `"null"` 也一律按无归属。
`DetachedTaskRecordTest` 5 例守着往返、缺键、空串、字面 null、未知键。

### 3. `planAutoResume` 的 `scopeId` 是必填参数

```kotlin
internal fun planAutoResume(
    records: List<DetachedTask>,
    scopeId: String,          // 必填：空串永不命中
    handled: Set<String>,
    finishedAfterMs: Long,
    alreadyResumed: Int,
    maxCount: Int = AutoResumeMaxPerSession,
): AutoResumePlan
```

刻意的选择：**不给默认值**。给默认值的话，漏传的调用点会静默变成「谁的都算我的」——
这类缺陷在界面上完全看不出来（只是偶尔多续一次）。必填会让它编译期就红。
`BackgroundResumeTest` 从 8 例扩到 12 例：别的会话不算候选、旧记录不算候选、空作用域永不命中、
混合场景只在本会话内排序。

### 4. 进程级 `BackgroundAutoResumeWatcher`

`agent/terminal/BackgroundAutoResumeWatcher.kt`：

- `start(context)` 在 `EtaApp.onCreate` 调一次（`TerminalRuntime.initialize` 之后、`resume()` 之前）；
  监听 `DetachedTaskSupervisor.changes`，另有 30 秒兜底扫描。
- `runOnce(records, finishedAfterMs, scopeFor, dispatch)` 是可测的纯编排：按 `sessionScope` 分组，
  每个会话先 `loadFromStore()`（没读完不判定），再用 `planAutoResume` 挑一条。
- **先 `dispatch` 成功再 `markAutoResumed` + `persist`**：发不出去（会话在跑 / 不在内存）不占预算。
- `startedAtMs` 是 watcher 起跑时刻：只续这之后结束的任务，避免一启动就把历史任务全续一遍。
- `install` / `uninstall` 存分发器引用：Activity 重建时新旧实例先后注册，旧实例注销只清自己。

`BackgroundAutoResumeWatcherTest` 10 例：无归属不派发、运行中不派发、早于起跑不派发、
正常派发并记账、派发失败不记账、预算只影响该会话、已续过不再派发、每会话每轮最多一条、
磁盘记忆阻止重启后重复续、空记录零调用。

### 5. 按会话发消息：`AgentAppState.resumeConversationAfterBackgroundTask`

`AgentAppState` 注册的分发器实现这个方法。与 `sendCurrentMessage` 的差别是**不碰界面态**：

- 不用 `homeState`、不碰输入框 / 草稿 / 编辑态、不改 `selectedConversationId`；
- 会话消息被释放过（`messagesDeferred`）时先 `ensureMessagesLoaded(listOf(id))` 再发
  —— history 不完整就发，模型看到的是残缺上下文；
- 该会话正在跑（`isStreaming`）→ 返回 false，下个 tick 再来；
- 只读外部归档会话 → 不发；
- `launchConversationRun(..., asPrimary = conversationId == selectedConversationId)`：
  后台会话的 run 不抢 `currentRunId` / `currentRunJob`（那是「停止」按钮的目标）。

`launchConversationRun` 里原来无条件写 `currentRunId` / `currentRunJob`，现在都按 `asPrimary` 判断。
`AgentAppViewModel.onCleared` 注销分发器；watcher 本身不随 Activity 结束。

### 6. 验证（本机 2026-11-01）

| 项 | 结果 |
|---|---|
| 定向 4 类 | **39 例 0 失败**（`BackgroundResumeTest` 12 + `BackgroundAutoResumeWatcherTest` 10 + `BackgroundResumeMemoryTest` 12 + `DetachedTaskRecordTest` 5） |
| `STAGES=test,lint,assemble` | **1041 例 0 失败**；lint 无新增问题（baseline 183 命中 + 11 条依赖提示未复现）；死文案 0；包 32,168,865 字节 `a3e5f8e2…` |
| `STAGES=test-wide` | **2899 例 0 失败** |

门禁第一次跑红在一处真错：`EmptySuperCall` —— `onCleared` 里调了 `super.onCleared()`
（AndroidX 里是空实现），lint 判错。删掉即可。这不是「格式问题」，是习惯性写法带来的错。

### 7. 边界（别把它读成「一定能续」）

- **只在 App 进程存活时触发**。进程被系统杀掉后不触发；回来后第一次探活写回的
  `completed_at` 会被当成「刚结束」，受每会话 2 次上限约束。
- **守护任务（`terminal_daemon`）结束不保证被感知**：watcher 读记录不探活，只有命令任务的
  完成线程会写 `completed_at`。守护任务要等有人调 `list()`。
- 自动续跑会把该会话的消息读进内存（大会话有真实开销）。
- 语音 / 一次性入口没有会话键 → 记录无归属 → 不自动续（手动「继续」仍可用）。
- 「续过」按会话落盘、「自动续了几次」仍只在进程内（重启归零，见 §32.58 的取舍）。
- 卡片里那份判定仍然保留（界面正开着时立即响应）；两边共用同一个作用域对象去重。
- 记录跨进程写仍只有进程级锁 + rename，没有文件锁。

### 8. 真机验证（2026-11-01 21:30~21:45，本机 PKX110 / Android 16）

装的是**主包** `io.github.mangi.eta`（不是 canary）：`/data/local/tmp/eta-b10-canary.apk`
其实是 20:50 那次 `STAGES=test,lint,assemble` 的产物，`applicationId` 没有后缀，
文件名里的 canary 是复制时起错了。核对：`lastUpdateTime=2026-11-01 20:50:43`、
`firstInstallTime` 仍是 10-03（未重置）、`base.apk` 32,169,213 字节 `07c4d21e…`；
21 个 dex 里能搜到 `canOfferManualResume` 与 `BackgroundAutoResumeWatcher`，
测试类（`BackgroundResumeTest` 等）0 命中。

做法：新建一块虚拟屏（display 6）把 Eta 启动过去，主屏留给用户，全程没碰主屏前台。

| 观察点 | 实际 |
|---|---|
| 卡片 | 「后台任务 · 已结束 32」展开，列最近 3 条：`job_23578824`、`job_46ae6eb0`（都属于当前主会话 `conv-1a25f9f1…`）、`job_89a250fc`（`session_scope=null`，无归属） |
| 所在会话 | 切到一个 **idle 的旧会话**（「你是什么模型，有什么能力」），不是这三条任务的归属会话 |
| 「继续」按钮 | **三条都没有** |
| 对照条件 | `agent_background_resume_button` 在 `eta_agent_preferences.xml` 里**没有这个键** → 取默认 `true`（`Prefs.kt:129`）；该会话 `isStreaming=false` → `canSend=true` |

按钮开关是开的、会话也是空闲的，三条任务仍不出现「继续」。按老代码
（`task.id !in resumed` 就算可续）它们会**全部**出现 —— 两条属于别的会话、
一条根本没有归属。这一轮要修的就是这个，真机上已经看不到了。

**没验证到的**：属于**该会话自己**的已结束任务仍然显示「继续」——正向对照没做
（要造这种记录得真的让那个会话跑一个后台任务，属于会花钱、会改用户会话的操作），
由单测 `manualResumeOnlyForOwnFinishedTask` 覆盖（4 例）。

自动续跑本身仍未真机验证：`agent_background_resume_auto` 默认 `false`，打开后任务一结束
就会真的发起一轮模型调用（改用户会话、花额度），本轮没有擅自打开。
`BackgroundAutoResumeWatcherTest` 10 例覆盖的是纯编排。

### 9. 补丁（同日）：手动「继续」也认主人

第 2~5 节把**自动**路径按会话收紧了，卡片的**手动**按钮却还只判「没续过」：

```kotlin
val canResumeThis = resumeButtonEnabled && canSend && task.id !in resumed
```

后果：别的会话（以及第九批之前没有 `session_scope` 的旧记录）只要排进「最近结束」，
当前会话里就会长出一个「继续」——点一下等于用这个会话的额度替别人干活。

改法：判定抽成 `canOfferManualResume(task, scopeId, buttonEnabled, canSend, handled)`，
与 `planAutoResume` 共用同一条归属规则（`scopeId.isNotBlank() && task.sessionScope == scopeId`），
卡片只调它。抽成函数而不是复制条件，是因为两处判定分居两个文件时最容易只改一处
——这一轮的缺陷就是这么来的。

`BackgroundResumeTest` 加 4 例（本会话显示、别的会话不显示、旧记录不显示、已续过/开关关不显示）。

门禁：`STAGES=test,lint,assemble` **1045／0**（lint 无新增，baseline 194）；
`STAGES=test-wide` **2903／0**；包 32,169,213 字节 `07c4d21e…`（主包）。

## §32.60 守护任务死了没人记账：补一个进程级探活（2026-11-01）

### 1. 缺陷：命令任务有完成线程，守护任务没有

`DetachedTaskSupervisor.list()` 里给记录补结束时间的只有一条分支：

```kotlin
if (task.commandTask && !running) { ... completeCommand(...) }
```

命令任务（`terminal_job`）自己有完成线程，不需要这条分支兜底；守护任务（`terminal_daemon`）
**没有完成线程**，进程被 kill 之后唯一能发现它死了的地方，就是下一次有人调 `list()` ——
也就是模型主动问 `terminal_daemon list`。没人问，记录就永远停在「运行中」。

后果不是「显示不好看」，是三件具体的事：

1. 会话卡片一直显示「运行中」，用户以为服务还活着；
2. `<background_tasks>` 每轮都告诉模型它还在跑，模型据此判断「不用重启」；
3. 自动续跑永远等不到这次「结束」—— 批十做的触发链对守护任务是空转。

装机前设备上有 32 条记录、其中 31 条 `completed_at` 有值：说明这不是「经常」发生
（模型常会自己 `list` 一下），但**一旦发生就是永久的** —— 没有任何东西会再来修正它。

### 2. 改法：把「该不该记结束」抽出来，只在探到死时写

- `shouldMarkCompleted(task, probeDead)`：纯函数，`probeDead && completedAt == null`。
- `list()` 的判定有两条来源：命令任务看 `!running`（原来就是），守护任务看
  **`aliveById[id] == false`**。区别很重要 —— `aliveById` 里 `null` 表示「这次没探到」
  （shell 起不来、无 root），`false` 才是「探到了、确实没了」。探测失败也写 `completed_at`，
  等于把活着的服务记成死亡；记录一旦写上结束时间就再也不能被认领。
- `completeCommand` 改名 `completeTask`（两类任务共用），三个调用点一起改。
- 新增 `BackgroundTerminalJobs.probe()`：只是 `supervisor.list()`，把「巡检」这个副作用
  暴露给 App 层。
- 新增 `DetachedTaskProbe`：每 5 分钟一次；**先读一次 JSON**，只有存在未结束的守护任务时
  才起 shell。
- `EtaApp.onCreate` 起这个循环，排在 `BackgroundAutoResumeWatcher.start` 之后、`resume()`
  之前 —— 探活写回的 `completed_at` 会被 watcher 当成「刚结束」，基准时间必须早于它（同批十的坑）。
- 循环里 `runCatching` 兜住异常并记 warn：这类「后台静默循环」一次失败就永久停掉的话，
  界面上只会表现为「功能没了」，且没有任何报错（§122 的教训）。

### 3. 窗口打开了，字段却没有：`terminal_daemon list` 的第二处

记录里第一次真的会写 `completed_at` 了，但 `terminal_daemon list` 的输出里没有它 —— 原来只输出
`task_id/pid/running/command/cwd/identity/environment/started_at`。模型看到的是：

```json
{"task_id":"dm_3208aa76","running":false,...}
```

「它没在跑，什么时候停的、是不是异常退出，一概不知」。而命令任务的 `taskJson` 一直有
`completed_at` / `exit_code` / `end_reason` —— 同一件事两套字段名，调用方只能踩一次空。

改法：抽 `daemonTaskJson(status)`（顶层函数，可本机测），补 `completed_at` / `end_reason`，
字段名与命令任务一致。`DaemonTaskViewTest` 3 例守着（运行中为 null、结束带原因、字段名一致）。
**没有**补 `description`：守护任务的启动入口根本不接受这个参数（`daemonStart` 不传它，
记录里恒为空串），输出一个空字段只是噪音。

### 4. 验证（本机 2026-11-01）

定向：`DetachedTaskRecordTest` 9 + `DaemonTaskViewTest` 3 = **12／0**。
`STAGES=test,lint,assemble` **1052／0**；lint baseline 194（183 命中 + 11 条依赖提示未复现）；
死文案 0；包 32,749,029 字节 `615ca341…`。`STAGES=test-wide` **2910／0**。

（这个 32,749,029 的包里有 576,132 字节是旧产物留下的无效填充，见第 6 节；内容本身没问题。）

### 5. 真机（本机 PKX110 / Android 16）

**第一次（21:56 装机，中间版本：有探活、没有日志）。** 装的不是「刚构建的」，而是门禁产物：
`base.apk` 32,748,249 字节 `4e1050e9…` 与 `app/build/outputs/apk/debug/Eta-v3.3.5-debug.apk`
一致，`firstInstallTime` 仍是 10-03（未重置），21 个 dex 里能搜到 `DetachedTaskProbe` /
`needsDaemonProbe` / `shouldMarkCompleted`，测试类 0 命中。

做法（**全程没有调 `terminal_daemon list`**，否则就变成「有人问了才更新」）：

| 时刻 | 事件 |
|---|---|
| 21:56:04 | 装完启动，`onCreate` 起探活循环（第一次应在 22:01:04） |
| 21:57:12 | `terminal_daemon start` 起 `sleep 900` → `dm_f92dab77`，pid 9388，记录 `command_task=false`、`completed_at=null` |
| 21:57:28 | `kill -9 9388`，进程消失；此后没有任何人调 `list()` |
| 22:01:07.973 | 记录被写回：`completed_at=1793541667973`、`end_reason=interrupted`、`exit_code=null` |

kill 到写回 **220 秒**，正好是「启动 + 5 分钟」那一轮（22:01:04，加一次 shell 巡检约 4 秒）。
记录文件同时在 22:01 被改写（22,879 → 22,190 字节）：多了一条结束时间，并按 32 条上限
裁掉最老的一条。

**第二次（22:15 装机，最终门禁包 `615ca341…`，带 debug 日志）。** 同样的造法：
`dm_3208aa76`（pid 3549，`command_task=false`）→ 22:16:07 `kill -9` → 22:20:16.506 记录写回
`completed_at` / `end_reason=interrupted`（kill 后 249 秒，正是 22:15:13 启动后的第一轮），
同一时刻 `logcat` 出现：

```
11-01 22:20:16.685 D/Eta ( 3168): Agent detached task probe: pending=1 records=33
```

`terminal_daemon list` 也跟着变了（这是第 3 节那处修复的直接证据）：

```json
{"task_id":"dm_3208aa76","running":false,...,"completed_at":1793542816506,"end_reason":"interrupted"}
```

**「不空转」的证据（这条最容易自欺，所以写细一点）。** 探活只在真的去巡检时打一条
debug 日志；没有未结束的守护任务时提前返回、不打日志。但「日志不出现」本身**不算证据** ——
默认 `logcat` 的 main 环形缓冲只有 256 KiB，实测 3 分钟就被冲干净（22:20:16 那条日志，
到 22:23:40 已经查不到了）。所以这次把缓冲临时调到 8 MiB 再测：

- 22:23:54 清空日志、缓冲 8 MiB；
- 22:25:13 那一轮（此时记录里已没有未结束的守护任务）；
- 22:25:47 检查：缓冲里 **34,122 行**、Eta 当时还在写 agent 事件（`22:25:51 D/Eta ... tool_started`），
  而 `detached task probe` **0 条**。

缓冲区健康、应用在写日志、这一轮没有巡检日志 —— 三者同时成立，才说明它真的没起 shell。
测完把缓冲恢复成 256 KiB。

### 6. 顺带：APK 体积每轮不同，原因找到了

前几轮一直有个没解释的现象：同一份代码打出来的包，体积和 sha 每轮都不一样。这次顺手量了：

| | 体积 | 条目间空隙 |
|---|---|---|
| 增量打包（在旧包上重写） | 32,749,029 | 576,132 |
| 删掉旧包再打 | 32,173,053 | 156 |

差 575,976 字节，正好是空隙差。看空隙内容：开头是 `PK\x03\x04`（一个 local file header），
后面几十万字节全是 0 —— 是被挪走的旧条目留下的残骸，不在 central directory 里。所以
`unzip -t`、`zipalign -c -p 4`、`apksigner verify` 全部通过，安装也正常，**只有体积不可比**。

根因：AGP 的增量打包往已有的 APK 文件里写（不是重新拼），旧条目挪位置后原地留下的
header + 填充没人清理。

处理：`gate.sh` 的 `run_assemble` 在打包前 `rm -f app/build/outputs/apk/debug/*.apk`。
实测连续两次重打都是 32,173,053 字节 `6bdf363d…`，体积这才成为可比指标（门禁脚本这一处
单独用 `STAGES=assemble` 验证过）。

### 7. 边界（如实记录）

- **5 分钟粒度**：最坏晚 5 分钟才发现；App 进程被杀之后不探，要等下次启动。
- **写的是「探活时刻」，不是「真实退出时刻」**：守护任务没有退出时间来源（没有 `.exit` 文件），
  所以 `completed_at` 只能记「什么时候被发现的」。
- **`end_reason` 只会是 `interrupted`**：同样因为没有退出码，`exit_code` 恒为 `null`。
- **探测失败不记账**：root 未授权、shell 起不来时保持「运行中」—— 宁可显示旧状态，
  也不能把活着的服务写死。
- **只在 App 进程里跑**：与批十同一个前提，进程不在就不探。
- 每轮要起一次 root shell（仅当存在未结束的守护任务时）；没有任务时只读一次 JSON，不开进程。
- `terminal_daemon list` 新增的两个字段是**纯新增**，旧调用方不受影响；但也意味着
  读 `completed_at` 前要允许它是 `null`。
- 自动续跑端到端仍未真机验证（auto 开关默认关，打开会真发模型调用、花额度、改用户会话）；
  本批只保证「守护任务结束」这件事现在能被感知到。

## §32.61 事件触发收尾：水位线与链长都落盘（2026-11-01）

### 1. 为什么是「收尾」而不是「再加一个事件源」

§32.50 的映射表把第四层记成「事件触发（automation）｜没有｜后续」，并写明推迟理由：
涉及循环保护与计费边界。第十批已经把这类事件接上了（后台任务结束 → 自动续跑），
但只做成「进程活着、界面打开过」的形态。对着代码看，真正缺的不是「更多事件源」，
而是事件本身的三个性质：

- **不丢**：旧实现拿「进程启动时刻」当水位线。任务刚结束、扫描还没跑，进程被杀 →
  它比下一次启动早，重启后永远不会被续。
- **不重放**：升级后第一次启动，记录里可能躺着几个月前结束的任务；没有持久水位线时，
  它们全都会被当成「新事件」。
- **不无限**：链长计数只在进程内，杀掉 App 再打开就归零 —— 循环保护的最短绕过路径。

所以这一批不新增通知 / 定时等事件源（那些要新权限、新 UI、新隐私面），
而是把已有事件做成一条可持久、可审计的触发链。

### 2. 做了什么

| 件 | 位置 | 变化 |
|---|---|---|
| 事件水位线 | `BackgroundResumeStore` / `BackgroundResumeScope.seenUntil` | 按作用域落盘 `seen_until`；只认严格晚于它的结束事件 |
| 链长预算 | `chain` + `AutoResumeMaxPerChain` | 从「进程内计数」改「落盘链长」；用户主动发消息清零 |
| 单一触发点 | `BackgroundTasksCard` | 删掉卡片里的自动续跑 `LaunchedEffect`；自动续跑只在 watcher |
| 可见性 | 卡片展开区 | 「本链已自动续 1/2」「自动续跑已暂停，发一条消息后恢复」 |
| 判定 | `planAutomationTick` | Initialize / Dispatch / Discard / Idle 四态纯函数 |

链长语义变了：不是「这个会话一辈子两次」，而是「用户最后一次说话之后，连续自动续几次」。
`AgentAppState.launchConversationRun` 新增 `automatic` 参数：自动续跑传 true，
其余所有入口（用户发消息、语音、分享、回答提问、排队消息补发）都会 `resetChain()`。

为什么链长必须落盘：只在内存里，进程被杀就归零 —— 用户（或系统）杀掉 App 再打开，
就能绕过上限。上一个版本的注释把「不落盘」当成「防止永久停用」，但真正防止永久停用的
是「用户说话就清零」，而不是「重启就清零」。

### 3. 验证（本机）

| 项 | 结果 |
|---|---|
| 定向 4 类 | 63 例 0 失败（`BackgroundResumeTest` 18、`BackgroundAutoResumeWatcherTest` 14、`BackgroundResumeMemoryTest` 15、`BackgroundResumeStoreTest` 16） |
| `STAGES=test,lint,assemble` | **1066 例 0 失败**；死文案 0；lint 无新增（baseline 183 命中 + 11 条依赖提示未复现）；包 32,177,405 字节 `e7a45bc96311f24f…` |
| `STAGES=test-wide` | **2924 例 0 失败** |

用例覆盖的边界：首次初始化不补发历史、水位线严格大于、模拟进程重启后水位线之后的
任务仍会被续、链长跨重启保留、链满明确放弃到最晚一条、发不出去不推进水位线、
`resetChain` 在未加载时不会抹掉磁盘记忆、老文件只有 `resumed` 时其余字段按默认值读。

### 4. 踩坑：两个「先写后读」的顺序问题

（1）`resetChain()` 如果直接清内存再落盘，会把还没加载的磁盘记忆一起写成空 ——
`resumed` 与 `seenUntil` 都会丢。它必须先 `loadFromStore()`。

（2）反过来，`loadFromStore()` 不能在 watcher 先立水位线之后把内存覆盖回磁盘旧值。
两个字段各带一个 `touched` 标记，加载时只填没被改过的。

这两条方向相反，但都源于同一件事：**同一份状态有两个写入口**（watcher 与用户发消息）。
上一批的教训是「同一件事有两个触发点就是两套判定」；这一批的教训是「同一份状态有两个
写入口，就要想清楚谁先谁后」。

### 5. 边界（如实记录）

- 仍然只有一类事件源：后台任务结束（命令任务由完成线程写盘；守护任务靠 5 分钟探活）。
  通知、定时、文件变化都没有触发源。
- 只在 App 进程存活时触发；进程被杀不触发，回来靠下一次记录写入或 30 秒兜底。
- `seen_until` 用的是墙上时钟：用户把系统时间往回拨，新结束的任务可能落在水位线之前，
  直到时间重新超过它。没有做时钟回退检测。
- 链满之后的候选会被**明确放弃**（水位线推进到最晚一条）：那条任务之后不会再被自动续，
  手动「继续」仍可用。
- 界面状态行只在展开卡片、且自动续跑开关打开时出现；链为 0 时不显示。
- 真机未验证：本批只有编译 + 单测。自动续跑端到端仍未验证（默认关，打开会真发模型调用、
  花额度、改用户会话）。
- 旧版文件没有 `chain` / `seen_until`：升级后第一次扫描会把它当作「还没立过水位线」，
  以当时的时刻初始化 —— 即升级前结束的任务不补发。

## §32.62 自动续跑剩下的两个静默失效点（2026-11-01）

### 1. 为什么还有这一批

§32.61 收尾时把三条边界写进文档，这一轮就对着**那三条逐条找「静默」的**：不报错、不崩溃、
界面看不出异常，但功能已经不工作了。找到两个：

| 边界 | 实际后果 | 严重度 |
|---|---|---|
| 系统时间回拨 | 回拨之后结束的任务 `completed_at` 都小于水位线，`done > seenUntil` 永远为假 → 自动续跑**哑掉**，直到真实时间重新追上旧水位线（可能是几小时、几天） | 高：无任何提示 |
| Activity 重建换分发器 | tick 读到记录、遍历作用域期间旧实例被销毁；`launchConversationRun` 仍返回成功 → 记成「已续」，run 发到一个没人看的实例 | 中：一次事件被吞 |

两者共同点：**失败被当成成功**。前者是「判定永远为假」，后者是「发送返回 true 但没人接」。

### 2. 改法

（1）时钟回拨：`planAutomationTick` 在水位线比「现在」还晚 `ClockSkewToleranceMs`（60 秒）
以上时返回新的 `AutomationTick.Rebaseline(at, absorb)`：

- 水位线重建到「现在」；`BackgroundResumeScope.reestablishSeenUntil()` 是唯一允许**后退**的入口
  （`markSeenUntil` 仍只前进 —— 它守的是「事件不重放」）。
- `absorb` 是**本作用域里 `completed_at` 比现在还晚**的已完成任务：它们是回拨前的历史，
  不吸收就会被当成「未来事件」反复选中（发两次、链满、水位线再被推到更远的未来 → 再次卡死）。
- 顺序是**先吸收、再后退水位线**：反过来的话中途失败会停在「水位线已后退、未来记录仍是候选」，
  下一次 tick 就会把这些历史真的发出去。

（2）分发器身份：`runOnce` 新增 `isCurrent: () -> Boolean`，tick 传 `{ dispatcher === current }`；
Dispatch 分支在发送前复核，换掉了就 `continue`（不发、不记账、不推进水位线）。

### 3. 验证（本机）

- 新增 8 例：`BackgroundResumeTest` 4（回拨重建 / 小抖动不重建 / 只吸收本作用域的未来记录 /
  重建优先于候选）、`BackgroundAutoResumeWatcherTest` 2（回拨重建并落盘且不重放 /
  分发器换掉时不发不记账）、`BackgroundResumeMemoryTest` 2（允许后退并落盘 / 非正数与相同值忽略）。
- **反证实验**：把回拨分支改成 `if (false && …)` 后重跑，**4 例失败**（3 个 plan 用例 +
  1 个 watcher 用例）；恢复后定向三类 55 例 0 失败。这一步是为了证明用例真的在守这个行为 ——
  只跑一次绿证明不了什么（断言写错了也会绿）。
- 定向：`BackgroundResumeTest` 22、`BackgroundAutoResumeWatcherTest` 16、`BackgroundResumeMemoryTest` 17。
- `STAGES=test,lint,assemble`：**1074 例 0 失败**；lint 无新增（baseline 183 命中 + 11 条依赖提示未复现）；包 32,179,129 字节 `768a1a422c905d3e…`。
- `STAGES=test-wide`：**2932 例 0 失败**。

### 4. 边界（如实记录）

- **回拨期间结束、还没被处理的任务会被当成历史（漏一次自动续跑）**：回拨之后新记录的时间戳
  更小，与更早的历史在时间上不可区分。取舍是「漏一次」而不是「重放一批」——
  重放会花掉两次额度在旧任务上，然后把水位线推到更远的未来，再次卡死。
- 容忍值 60 秒：回拨不足 60 秒时，那一小段时间内结束的任务仍会被挡在水位线之外，最多 60 秒后自愈。
- 吸收的 id 会计入 `resumed`（上限 64 条，`records` 上限 32 条），极端情况下会挤掉更早的
  「已续」记忆 —— 后果是那些任务的「继续」按钮重新出现（手动可再点一次）。
- 反方向（把时间往前拨）不重建：水位线在现在之前，本来就能正常工作。
- 分发器身份复核只覆盖「tick 期间被换掉」；如果旧实例的协程作用域已经取消，
  `launchConversationRun` 内部仍可能「记了 run 但立刻被取消」—— 这一层没有额外防护。
- 两条修复都**没有真机验证**：时钟回拨要在设备上改系统时间（可能影响其他应用、也会被自动校时
  拉回），分发器替换需要 Activity 重建的时序配合。都是有单测、无真机。

## §32.63 同一毫秒结束的任务不再被水位线吞掉（2026-11-01）

### 1. 动机：时间轴没断，精度不够

§32.62 修的是「时间轴断了」（时钟回拨）。这一批是同一张水位线的另一个方向：
水位线是毫秒时间戳，Dispatch 记账时直接推进到该任务的 `completed_at`。
于是**同一毫秒里结束的其余任务**从此永远不满足「严格晚于水位线」，被静默跳过 ——
不报错、不发消息、日志里也看不出来。

不是理论问题：模型一轮里派几个短命令（`echo`、`true`、`cat`）时，完成线程写
`completed_at` 落在同一毫秒很常见。

### 2. 改法

- Dispatch 记账：`markSeenUntil(completedAt - 1)`，水位线停在这条任务的**前一毫秒**。
  下一轮扫描时，同一毫秒的其余任务 `done > seenUntil` 成立，仍能被看到（一次一条）。
- Discard **不动**：它推到最晚一条，语义是「这一批明确放弃」。如果那里也减 1，
  被放弃的同一毫秒任务会每 30 秒变回候选、再被放弃一次 —— 不发钱，但永远重算。

### 3. 顺带补的可观测性

自动续跑会在用户不在场时花额度，而在此之前真机日志里**没有任何**关于它的记录 ——
用户问「为什么它自己发了一条」，只能靠猜。

- `runOnce` 新增 `onPlan: (String, AutomationTick) -> Unit`（默认空）：每个作用域判定完回调一次。
  抽成回调而不是直接写日志，是因为 `runOnce` 是纯 JVM 可测的，`android.util.Log` 在单测里会抛
  「not mocked」。
- `AutomationTick.logLine(scopeId)` 是纯函数：`idle` / `initialize` / `dispatch` /
  `discard skipped=N through=…` / `rebaseline absorbed=N`。日志文本本身可断言。
- `tick()` 里每个判定打一条 `AndroidAgentLogger.debug`（Release 会删掉整次调用）。

### 4. 验证（本机）

- **先红**：`tasksFinishedInSameMillisecondAreDispatchedOneAfterAnother` 在旧实现下失败
  （第一轮 1、第二轮 0）；同时加的 `discardedSameMillisecondTasksDoNotComeBack` 保持通过。
- 实现后三类 **59 例 0 失败**（`BackgroundResumeTest` 23、`BackgroundAutoResumeWatcherTest` 19、
  `BackgroundResumeMemoryTest` 17）。顺带修正一条旧断言：`finishedTaskOfThisConversationIsDispatchedAndRecorded`
  里 `seenUntil` 由 600 变 599 —— 这是行为变更，不是测试让步。
- `STAGES=test,lint,assemble`：**1078 例 0 失败**；lint 无新增（baseline 183 命中 + 11 条依赖提示未复现）；包 32,180,441 字节 `af791200b9a6a7c2…`。
- `STAGES=test-wide`：**2936 例 0 失败**。

### 5. 边界（如实记录）

- 水位线语义从「已处理到某刻」变成「已处理到某刻之前 1ms」。它只在内部比较，不展示给用户；
  但以后若有人把 `seen_until` 当审计时间读，要知道这 1ms。
- `completedAt = 1` 时 `coerceAtLeast(1)` 后水位线不前进：此时靠 `resumed` 集合挡住重复，
  不会重发；真实时钟不会出现这个值（毫秒时间戳量级）。
- 同毫秒多条仍受链长限制：一条链最多自动续 2 条，其余被 Discard（现在日志里有 `skipped=N`）。
- 日志是 `debug`：Release 构建会把整次调用删掉，正式版仍然没有这条线索。
- 没有真机验证（自动续跑端到端仍未验）。

## §32.64 触发循环本身能活多久（2026-11-01）

§32.62 / §32.63 修的都是**判定**（该不该发、发哪一条）。这一批往下看一层：
判定结果要靠 `BackgroundAutoResumeWatcher.start()` 起的两个协程去执行，而这两个协程
**没有任何异常边界**。

```kotlin
scope.launch { DetachedTaskSupervisor.changes.collect { tick(appContext) } }
scope.launch { while (true) { delay(FALLBACK_INTERVAL_MS); tick(appContext) } }
```

### 1. 为什么这是「静默失效」而不是「偶发异常」

- `started` 是 `AtomicBoolean.compareAndSet(false, true)`：进程内**只能启动一次**，
  再调 `start()` 直接返回。协程死了没有任何重启入口。
- `SupervisorJob`：一路死了另一路还活着，不会表现为「整体崩了」。
- 第十二批起卡片里那份自动续跑已删掉，这两个协程就是**唯一**触发点。
- `tick` 里有真实可抛路径：`dispatch` 进会话层 `launchConversationRun`、
  `ensureMessagesLoaded` 读会话 JSON、`scope.persist()` 写盘。`loadState` / `write` /
  `readRecords` 内部各包了一层 `runCatching`，但最长的这条 `dispatch` 路径没有。

结果：一次异常 → 事件触发永久失效（只剩 30 秒兜底）；兜底再失败一次 → 自动续跑**永久停掉**，
不报错、不弹窗，用户只看到「任务结束之后再没接着跑」。

同一类缺陷 §122 已经踩过（「一次失败即永久停用」），`EtaApp` 的 5 分钟探活循环当时专门包了
`runCatching`；watcher 这里漏了 —— 说明「知道这个模式」和「每一处都做到」是两件事。

### 2. 修法

把两段循环抽成可测函数，每轮走 `runGuarded`：

```kotlin
internal suspend fun runChangeLoop(changes: Flow<Long>, tick: suspend () -> Unit,
                                   onFailure: (Throwable) -> Unit = ::logTickFailure)
internal suspend fun runFallbackLoop(intervalMs: Long, tick: suspend () -> Unit,
                                     delayBefore: suspend (Long) -> Unit = { delay(it) },
                                     onFailure: (Throwable) -> Unit = ::logTickFailure)
```

- 失败只上调 `onFailure`（默认 `AndroidAgentLogger.warn("… type=SimpleName")`），**循环继续**。
- **不能**用 `runCatching`：它会把 `CancellationException` 一起吞掉 —— 协程已被取消、循环还在转。
  `runGuarded` 先 `catch (cancellation: CancellationException) { throw cancellation }`，其余才上调。
- `delayBefore` 只为测试注入，真机就是 `delay`。

### 3. 验证（本机）

- **先红**：新增 `BackgroundAutoResumeLoopTest`（3 例），未实现时编译失败（`Unresolved reference`）。
- **反证**：把 `runGuarded` 换成裸 `tick()` →
  `fallbackLoopKeepsRunningAfterTickFailure` 与 `changeLoopKeepsCollectingAfterTickFailure`
  **2 例红**，失败信息就是 tick 抛的异常**直接穿出循环**（`IllegalStateException: tick exploded`）；
  恢复后全绿。第三例 `fallbackLoopPropagatesCancellation` 守「取消不能走 onFailure」。
- 定向四类 **62 例 0 失败**（`BackgroundResumeTest` 23、`BackgroundAutoResumeWatcherTest` 19、
  `BackgroundResumeMemoryTest` 17、`BackgroundAutoResumeLoopTest` 3）。
- `STAGES=test,lint,assemble`：**1081 例 0 失败**；lint 无新增（baseline 183 命中 + 11 条依赖提示未复现）；
  包 32,182,569 字节 `0f021a3250473597…`。
- `STAGES=test-wide`：**2939 例 0 失败**。

### 4. 边界（如实记录）

- 修的是「触发链断掉之后能不能自愈」：单轮失败只丢一轮，下一轮照常。**不是** `tick` 内部的幂等。
- `resetChain()`（用户发消息时清零）走 `scope.launch(Main)`，**不经过** watcher 的 `running` 锁，
  与 `loadFromStore()` / `persist()` 理论上可并发（窗口极窄；后果是 `resumed` 可能重复、
  `chain` / `seenUntil` 可能被旧值覆盖一次）。本轮未处理。
- `start()` 仍然只启动一次；循环自己现在不会因异常退出，但**进程被杀**后仍要等下次进程启动
  —— 这是既有语义，文档已写。
- 没有真机验证（与第十三、十四批相同：自动续跑端到端仍未验）。

## §32.65 一条「防坏文件」的上限把整条链锁死了（2026-11-01）

### 1. 两条读取路径，只有一条有天花板

同一份 `detached-tasks.json` 有两个读者：

| 读者 | 用途 | 大小处理 |
|---|---|---|
| `loadTasksLocked()`（实例） | API / 探活 / 裁剪 | 读全量，**无上限** |
| `readRecords()`（companion） | 卡片、`<background_tasks>` 提示、`DetachedTaskProbe`、自动续跑 watcher | 超过 256 KiB → **返回空列表** |

返回空的后果不是「少看几条」：

- `DetachedTaskProbe.probeOnce` → `needsDaemonProbe([]) == false` → **不再巡检** →
  守护任务的 `completed_at` 永远不会写；
- `BackgroundAutoResumeWatcher.tick` → `records.isEmpty()` → 直接 return → **自动续跑完全停摆**；
- 卡片与系统提示看到「没有任何后台任务」。

### 2. 为什么会长到 256 KiB（而且再也缩不回去）

`pruneExitedRecords` 原来只在 `aliveById.isNotEmpty()` 时调用 —— 即「本轮确实探活过某个
未完成任务」。当所有任务都已结束（最常见：用户派了几个命令，都跑完了），`aliveById` 是空的
→ 不裁剪 → 记录只增不减。

于是形成**自锁**：文件越大 → `readRecords` 越早返回空 → 探活越不跑 → 越没人裁剪。
正常记录 32 条约 16 KiB，256 KiB 约 500~800 条；高频使用几个月就会到，到了没有任何提示。

### 3. 修法

1. `MAX_RECORDS_BYTES` 256 KiB → **4 MiB**：只用来挡「文件被写坏」（正常上限 32 条约 16 KiB）。
2. `list()` **无条件**裁剪，不再要求「本轮有探活结果」。安全性来自已有语义：没有探活结果的
   未完成任务 `running` 是保守的 `true`，不会被删。
3. 淘汰规则抽成纯函数 `pickPruneVictims`，并把排序键从 `startedAt` 改成 `completedAt`：

   原排序的后果是「跑得最久的守护任务永远排第一」—— 它一结束就被删，而它的完成事件正是
   自动续跑要消费的东西，连一次扫描都赶不上。再补 60 秒保护期（兜底扫描 30 秒的两倍），
   刚完成的记录不淘汰。

```kotlin
internal fun pickPruneVictims(statuses, maxRetained, nowMs): List<DetachedTaskStatus> =
    statuses
        .filter { !it.running && (!it.task.commandTask || it.task.completionNotified) }
        .filter { (it.task.completedAt ?: 0L) <= nowMs - PruneGraceMs }
        .sortedBy { it.task.completedAt ?: it.task.startedAt }
        .take(excess)
```

### 4. 验证（本机）

- **先红**：`DetachedTaskRecordsTest.oversizedRecordsFileIsStillReadable` 造 800 条**合法**记录
  （实测文件 > 256 KiB），旧实现读出 **0 条**（`expected:<800> but was:<0>`）。
- **反证**（三处分别回退，一次跑出 3 红）：
  - 上限回 256 KiB → 同一例红；
  - 排序回 `startedAt` → `prunesEarliestCompletedFirst` 红（选中了完成得最晚的）；
  - 去掉 60 秒保护期 → `keepsRecordsCompletedWithinGrace` 红（选中了刚完成的那条）。
- 定向 `agent.terminal.*`：**518 例 0 失败**（新增 5 例）。
- `STAGES=test,lint,assemble`：**1086 例 0 失败**；lint 无新增（baseline 183 命中 + 11 条依赖提示未复现）；
  包 32,183,105 字节 `7bc6395a10cf4419…`。
- `STAGES=test-wide`：**2944 例 0 失败**。

### 5. 边界（如实记录）

- 4 MiB 仍是硬上限：真到这个量级说明文件坏了，`readRecords` 返回空；坏文件在
  `loadTasksLocked` 解析失败时会留一条 `records corrupted, resetting` 日志，不是完全无声。
- `readRecords` 是**只读快照**，不做裁剪；裁剪只发生在 `list()`（卡片刷新 / 探活 / CLI 列表）。
  如果某个进程只读快照、从不调 `list()`，文件不会被裁 —— 但主进程的卡片与 5 分钟探活都会调。
- 未处理：跨进程写记录没有文件锁（两个进程同时 save 仍可能丢写），与第十二批记录的边界一致。
- 没有真机验证。

## §32.66 续跑状态写盘失败没人知道，两个写者还会互相覆盖（2026-11-01）

`BackgroundResumeScope.persist()` 原来把 `saveState` 返回的 Boolean 直接丢掉。写盘失败
（目录建不出来、改名失败、空间不足）时内存里一切正常：这一轮照常判定、照常发消息，
**重启之后退回旧值** —— `seenUntil` 回退会把已处理的任务再续一遍，`chain` 回退会让
「一条链最多 2 次」的上限失效。两者都花用户额度，而且没有任何日志。

同一作用域还有两个写者：watcher 的 `runOnce`（读内存 → 判定 → 记账 → 写盘）与用户发消息时
`AgentAppState.resetBackgroundResumeChain` 里另起协程调用的 `resetChain()`。两者都可能先读到
旧的 `seenUntil` 再写盘，后写者把新值覆盖回去 —— 同样表现为重启后重放。

修法：

1. `persist()` 改为 `suspend fun persist(): Boolean`，如实返回 `saveState` 的结果；失败时经新增的
   `BackgroundResumeMemory.onPersistFailed`（进程级回调，保持这个文件纯 JVM 可测）上报，
   `BackgroundAutoResumeWatcher.start()` 里把它装成
   `AndroidAgentLogger.warn("Agent auto-resume state not saved: scope=…")`。写成功时不上报，
   免得正常路径变噪音。
2. `BackgroundResumeMemory.mutexFor(scopeId)` 新增按作用域的 `Mutex`（`reset()` 一起清），
   `runOnce` 的每个作用域桶整段 `withLock`，`resetChain()` 拿同一把锁 —— 两个写者串行，
   「读旧值 → 写盘」不再能交叉。
3. 顺带堵上判定与执行之间的第二个窗口：`Dispatch` 分支在 `isCurrent()` 之后再查一次
   `plan.task.id in scope.resumed`（用户可能正好在这中间手动点了「继续」），已记过就不发。
   与第十三批的 `isCurrent` 同类：判定与执行之间状态会变。

验证：

- **先红**：`BackgroundResumeDurabilityTest` 4 例（失败必上报 / 成功静默 / 分发期间持有锁 /
  判定后被记成续过就不发），实现前编译失败 `Unresolved reference 'onPersistFailed'`。
- **反证**（三处同时回退：去掉锁、去掉 `resumed` 复查、去掉失败上报）→ 3 红：
  `scopeIsLockedWhileDispatching`、`failedPersistIsReported expected:<[conv-a]> but was:<[]>`、
  `skipsDispatchWhenTaskWasResumedAfterPlanning expected:<0> but was:<1>`。
- 定向 `agent.terminal.*`：**522 例 0 失败**（新增 4 例）。恢复实现文件后复跑同样 **522 例 0 失败**，
  4 例全绿。
- `STAGES=test,lint,assemble`：**1090 例 0 失败**；lint 无新增（baseline 183 命中 + 11 条依赖更新
  提示未复现）；包 32,185,577 字节 `655cc6b1dfe22276…`。
- `STAGES=test-wide`：**2948 例 0 失败**。

### 边界（如实记录）

- 锁是**按 scopeId 粒度**：不同会话互不阻塞（本来就该这样，A 会话不该等 B 会话），同一会话的
  两次 tick 排队；`runOnce` 本来就是 30 秒一次，不会堆积。
- `resetChain()` 外面仍是 `runCatching`：失败（例如加载失败）只丢这一次重置，不抛到 UI。
  与第十五批「循环整体异常边界」同思路 —— 用户手动操作不能因为内部状态问题崩掉。
- 写盘失败只留一条 warn，**不重试**：重试要引入退避与队列，这批只解决「静默」。失败后内存里仍是
  最新值，同一进程内判定是对的，重启才会回退。
- 没有真机验证：自动续跑默认关闭，打开会真发模型调用、改用户会话。第十三批起每批都只有单测。

## §32.67 手动「继续」是第三个写者，而且写在会消失的作用域里（2026-11-01）

第十七批把 watcher 的 `runOnce` 与 `AgentAppState` 的 `resetChain()` 串到同一把作用域锁上，
文档与注释都写「同一作用域有两个写者」。**实际有三个** —— 卡片里点「继续」的那一行是
`markResumed()` + `rememberCoroutineScope().launch { persist() }`。

它写的是**整份**状态（`resumed` / `chain` / `seen_until`），既没有拿锁，也不在任何人的串行区间里。
落在 watcher 之后就把新值盖回旧值：重启后 `chain` 回到 0（链上限被重置，多花额度），
或 `seenUntil` 回退（已处理的任务变回候选，重放）。内存里两边是同一个对象，所以**当场看不出来**，
只有「随后进程被杀」才暴露。

同一个 `uiScope` 还有第二个问题：它是 `rememberCoroutineScope()`，随组合销毁取消。用户点完立刻
离开会话（或界面被回收），那个 `launch` 还没跑就被取消 —— 这次点击只活在内存里，重启后等于没点过：
按钮重现，自动续跑也可能再发一条（水位线还没推进时）。

修法：

1. 新增 `BackgroundResumeScope.resumeManually(id)`：**记账同步**（点下去立刻生效），落盘交给
   进程级写入器 `BackgroundResumeMemory.persistSoon()`。
2. `persistSoon()` 跑在 `writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)` 上，并拿
   `mutexFor(scopeId)` —— 与 watcher、`resetChain()` 同一把锁，第三个写者并入同一串行区间。
3. 写入器里的异常不能变成闪退：`launch` 里未捕获的异常会走到线程 uncaught handler。按第十五批
   `runGuarded` 的规则处理：`CancellationException` 原样抛，其余上报 `onPersistFailed`。
4. 卡片改调 `resumeManually()`，删掉 `rememberCoroutineScope()` 与本文件里的 `launch` 导入。

验证：

- **先红**：2 例（持锁期间不许写盘 / 没有调用方协程也必须写成），实现前编译失败
  `Unresolved reference 'resumeManually'`。
- **反证一**（`persistSoon` 去掉 `withLock`）→ 1 红：`manualResumeWaitsForScopeLock`
  「持锁期间不许写盘（否则与 watcher 互相覆盖）」。
- **反证二**（`resumeManually` 只记账不落盘）→ 2 红：`manualResumeWaitsForScopeLock`
  「解锁后必须落盘」、`manualResumeWritesWithoutCallerScope`「没有调用方协程也必须写成」。
- 定向 `agent.terminal.*`：**525 例 0 失败**（新增 3 例；第三个是写入器异常上报
  `writerFailureIsReportedNotCrashed`）。恢复实现文件后复跑同样 **525 例 0 失败**。
- `STAGES=test,lint,assemble`：**1093 例 0 失败**；lint 无新增（baseline 183 命中 + 11 条依赖更新
  提示未复现）；包 32,186,737 字节 `bbdb2fc054477368…`。
- `STAGES=test-wide`：**2951 例 0 失败**。

### 边界（如实记录）

- 「点完立刻离开」这一半**没有用例守**：组合销毁要 Compose 运行时（本机 aarch64 跑不了
  Robolectric）。用例只覆盖「不需要调用方协程也能写成」；机制上已由「记账同步 + 进程级写入器」
  消除，但这一步是推理，不是真机验证。
- 锁仍按 scopeId 粒度：同一会话的手动续跑与自动续跑排队，不同会话互不阻塞。
- `persistSoon` 不返回任务、不重试：失败仍只有一条 warn（第十七批）。界面不会因为写盘失败卡住，
  代价是「这次失败只能从日志看出来」。
- 没有真机验证（自动续跑默认关闭，打开会真发模型调用、改用户会话）。

## §32.68 手动「继续」与自动续跑各拼一套提示词（2026-11-01）

**症状**：卡片上点「继续」发出去的那句话，括号里是「job」/「daemon」：

> 后台任务 job_ab12（job）已结束。请用 terminal_job / terminal_daemon 的 logs 读取它的输出……

括号里本该是任务名（描述），却是一个类型词。同一份模板、同一个 `%2$s`，自动续跑填的是
`task.description`，手动按钮填的是 `if (task.commandTask) "job" else "daemon"` —— 占位符的含义
随入口变。用户在自己的聊天记录里看到这句，模型拿到的也是这句：它认不出这是哪条任务，
因为「job」对同会话的每条 job 都成立。

**根因**：模板只有一份（`R.string.background_tasks_resume_prompt`），格式化却有两份实现：
`BackgroundTasksCard.kt` 里的私有 `resumePrompt()`，与 `AgentAppState.kt` 里内联的
`String.format`。两处都能改模板、都能改参数，第三批人再写第三份也不会有人发现 ——
这和第六十六批「同一个判据写两遍」是同一类缺陷。

**修法**：

1. `agent/terminal/BackgroundResume.kt` 新增 `internal fun backgroundResumePrompt(template, task)`：
   `Locale.ROOT`，`%1$s` = `task.id`，`%2$s` = `task.description.ifBlank { task.command }`
   （没写描述就退回命令本身：宁可长，也不能退成空或类型词）。语义写在注释里。
2. 卡片删掉私有 `resumePrompt()` 与随之失去用途的 `java.util.Locale` 导入，改调统一函数。
3. `AgentAppState.resumeConversationAfterBackgroundTask()` 改调同一函数（原先内联格式化）。
4. `BackgroundResumeStore.save()` 补注释：它是**合并写入**；第十八批起生产侧已无调用方，
   将来要恢复调用得先判断这次是「整份写」还是「合并写」。

**为什么用 `Locale.ROOT`**：`String.format` 的默认 locale 会带来本地化数字与分隔符。这里参数
都是字符串，眼下看不出差别，但「用默认 locale 格式化给模型看的文本」是迟早出事的写法 ——
与第十二批统一 `Locale.ROOT` 的理由相同。

验证：

- **先红**：3 例（描述进括号 / 无描述退回命令 / 括号里不能是类型词），实现前编译失败
  `Unresolved reference 'backgroundResumePrompt'`。
- **反证一**（把 `%2$s` 改回 `if (task.id.startsWith("dm_")) "daemon" else "job"`）→ **3 红**：
  `resumePromptCarriesTaskDescription`、`resumePromptFallsBackToCommandWhenDescriptionBlank`、
  `resumePromptNeverSubstitutesTaskType`。
- **反证二**（去掉 `.ifBlank { task.command }`，让空描述原样输出）→ **1 红**：
  `resumePromptFallsBackToCommandWhenDescriptionBlank`。恢复后文件 sha256 与反证前一致
  （`29a20f263d93adf5…`），`RESTORE=OK`。
- 定向 `agent.terminal.*`：**528 例 0 失败**（`BackgroundResumeTest` 26 例）。
- `STAGES=test,lint,assemble`：**1096 例 0 失败**；lint 无新增（baseline 183 未增加）；
  包 32,186,873 字节 `af4e4cb3d94876a1…`。
- `STAGES=test-wide`：**2954 例 0 失败**。

**边界（如实记录）**：

- 用例守的是**函数**，不是**调用点**：卡片要 Compose 运行时（本机 aarch64 跑不了 Robolectric），
  所以「以后有人再写第四份格式化」只在代码评审里有防线，没有用例防线。能守住的只有
  「函数只有一份、语义固定」。
- 手动路径不 `.trim()`、也不判空白（自动路径两者都有）。模板是资源常量、内容非空，
  所以空提示词在运行期不可达；这段不对称因此没改。
- 仍未真机验证：自动续跑默认关闭，打开会真发模型调用、改用户会话。

## §32.69 落盘先删再换：写失败变成了「把上次记住的也删掉」（2026-11-01）

`BackgroundResumeStore.write()` 落盘分三步：写 `.tmp` → `delete()` 旧文件 → `renameTo()`。
中间那一步的理由写在原注释里：「直接 rename 覆盖在某些文件系统上会失败，先删再换」。
可 App 的 `filesDir` 就在 Linux 文件系统上，`rename(2)` **本来就替换目标** —— 前提不成立，
代价却是实的：

- 进程在 `delete()` 与 `renameTo()` 之间被杀 → 这个作用域的记忆文件**不存在了**；
- `renameTo()` 失败（只读、`ENOSPC`、目标被占用）→ 同一结局，而且已经晚了。

重启后 `resumed` 空、`chain` 0、`seen_until` null：已续过的任务重新变成候选（同一条任务会被
再自动续一次，花的是用户额度）、卡片按钮重新出现、链上限被重置、水位线回退。
全程静默 —— 写的那一侧只留一条 warn，读的那一侧什么都不知道。

修法：把「换到目标位置」抽成可注入的 `moveIntoPlace`，生产默认就是 `tmp.renameTo(target)`
（原子替换），**不再先删**；换名失败只清临时文件，旧文件保持不动。注入点只为测试存在
（真机上没法让 rename 失败）。

验证：

- **先红**：3 例（新构造参数还不存在时编译失败 `Unresolved reference`）。
- **反证**（把 `write()` 改回「先删再换」）→ **2 红**：`failedMoveKeepsPreviousMemory`
  （换名失败后旧记忆被清空）、`moveReplacesWithoutDeletingFirst`（换名那一刻旧文件已经不在了）。
  恢复后文件 sha256 与反证前一致（`8302358b431250d5…`，`RESTORE=OK`）。
- 定向 `agent.terminal.*` + `BackgroundResumeStoreTest`：**547 例 0 失败**（Store 19 例）。
- `STAGES=test,lint,assemble`：1099 例 0 失败；lint 无新增；包 32,187,229 字节 `b22c30f82eb2a520…`。
- `STAGES=test-wide`：2957 例 0 失败。

**边界（如实记录）**：

- `renameTo` 真失败时仍只有一条 warn（`onPersistFailed`），不重试、不换目录。
- 同仓库其余几个 store（`AgentTodoStore` / `AgentGoalStore` / `AgentTaskLedgerStore` /
  `BackgroundEventStore` / `TerminalEnvFile`）本来就是「先换名」，这次是把出格的那一个对齐。
- `DetachedTaskSupervisor.saveTasksLocked()` 的兜底是 `writeText` 就地覆盖（非原子），本轮未动 ——
  它只在 rename 失败时才走，而且写的是任务记录（可从探活重建），判为下一步而不是本轮。
- 没有真机验证。

## §32.70 整份状态写入前没读盘：第一帧点「继续」会把记忆抹成默认值（2026-11-01）

用户视角的症状：以前续过的任务又长出「继续」按钮；重启后这条链又能自动续两次；水位线
莫名回到「还没立过」。三个现象都指向同一个动作 —— 某一次落盘把整份状态写回了默认值。

根因是一条顺序问题：

1. 卡片第一帧渲染时 `resumed` 还是空的 —— `loadFromStore()` 在 `LaunchedEffect` 里异步跑，
   所以**所有已结束的任务都会先出现「继续」**；
2. 这一刻点下去 → `markResumed(id)`（同步记账）+ `persistSoon { persist() }`（进程级写入器）；
3. `persist()` 把内存状态**整份**写盘，而内存里只有刚点的这一条：
   `resumed=[new]`、`chain=0`、`seenUntil=null`；
4. 磁盘上的 `resumed` / `chain` / `seen_until` 被覆盖，且没有任何报错。

**为什么不是纯理论**：自动续跑默认关，watcher 的 `tick()` 在 `autoResumeEnabled` 那一步就返回，
`runScopeOnce` 里的 `loadFromStore()` 根本走不到 —— 默认配置下没有第二个读者替卡片先读盘。

后果：

- `chain` 归零 → 重启后这条链又能自动续两次（花的是用户额度）；
- `seen_until` 丢失 → 下次启动走 `Initialize`（把启动前结束的任务当历史，不会误发，但水位线语义回退）；
- 旧 id 丢失 → 已经续过的任务重新给按钮，再点一次就是重复发言。

修法：`BackgroundResumeScope.persist()` 在整份写入前 `if (!isLoaded) loadFromStore()`。
放在 `persist()` 而不是 `resumeManually()`，是因为写者不止一个（卡片点击、watcher、
`resetChain()`、`persistSoon`），拦在最后一道门上才不会漏掉下一个。

验证：

- **先红**：`manualResumeBeforeLoadKeepsStoredState` →
  `expected:<[job_old, job_new]> but was:<[job_new]>` —— 磁盘上真的只剩新记的那一条。
- **反证**（删掉守卫）→ 同一例红；恢复后文件 sha256 与反证前一致（`29305c64754dd901…`，
  `RESTORE=OK`）。
- 定向 `BackgroundResumeDurabilityTest` + `BackgroundResumeMemoryTest`：**25 例 0 失败**。
- 定向 `agent.terminal.*` + `BackgroundResumeStoreTest`：**548 例 0 失败**。
- `STAGES=test,lint,assemble`：1100 例 0 失败；lint 无新增；包 32,187,341 字节 `6b238fefbb7ef8ae…`。
- `STAGES=test-wide`：2958 例 0 失败。

**边界（如实记录）**：

- `resumeManually()` 仍然同步返回：读盘发生在写入器的 IO 线程上，界面不用等；代价是这次点击
  落盘的时机比点击晚几毫秒。
- 读盘失败（坏文件）仍按「没有记录」处理，于是仍会整份覆盖 —— 这是 store 层既有的取舍
  （§32.69 之后至少不会把旧文件删掉）。
- 这条路径多一次读盘（仅限作用域还没加载时），不影响已加载的常规 tick。
- 没有真机验证。

## §32.71 满额读盘把「刚点的继续」挤掉：磁盘 id 排在了内存 id 之后（2026-11-01）

用户视角的症状：点了「继续」，按钮**立刻又出现**；再点一次，同一条任务被续了两遍。
不是每次都有 —— 只有磁盘上已经攒满 64 条记忆、且这次点击赶在卡片读盘之前时才会发生。

顺序：

1. 卡片第一帧 `resumed` 为空，`loadFromStore()` 还在 `LaunchedEffect` 里排队；
2. 点击 → `markResumed(id)` 记进内存（此时列表里只有它）→ `persistSoon { persist() }`；
3. `persist()` 先读盘（第二十一批）→ `loadFromStore()` 把磁盘的 64 条**追加**在内存那条之后；
4. `remember()` 见 65 条 → `removeAt(0)` → **删掉的正是刚点的那一条**；
5. 整份写盘，磁盘上与之前完全一样：这次点击从未存在过。

为什么点不中的是「刚点的」：`resumed` 的约定是**末尾最新**（`remember` 从尾部加、从头部挤，
store 的 `sanitize` 也是保留尾部）。`loadFromStore` 的追加破坏了这条约定 —— 磁盘上的旧 id
反而排到了内存新 id 的后面。

后果：按钮立刻重现（同一屏里就能看到）；再点一次 → 同一条任务两条续跑消息；自动续跑开着
且水位线在那条之前 → 它重新成为候选，再花一次额度。

修法：`mergeStored(stored)` —— `(stored + resumed).distinct().takeLast(MAX_IDS)`，磁盘在前、
内存在后；与 `remember`、`sanitize` 同一口径。内容没变时直接返回，不重建列表。

可达性依赖第二十一批：`persist()` 不读盘时，这条路径表现为「整份覆盖」而不是「挤掉一条」，
两种症状都指向同一个「内存只有一条却当作全量」。

验证：

- **先红**：`manualResumeAtFullCapacityKeepsTheClick` → 「满额时这次点击也必须落盘」
  AssertionError（`waitForResumed` 2 秒超时，磁盘上根本没有这条）。
- **反证**：把 `mergeStored(...)` 换回 `state.resumed.forEach { remember(it) }` → 同一例红；
  恢复后主文件 sha256 `f1afa88f…`，`RESTORE=OK`。
- 定向 Durability + Memory：**26 例 0 失败**；`agent.terminal.*` + Store：**549 例 0 失败**。
- `STAGES=test,lint,assemble`：1101 例 0 失败；lint 无新增（baseline 183，11 条依赖更新提示
  未复现）；包 32,187,277 字节 `986e32a82474b968…`。
- `STAGES=test-wide`：2959 例 0 失败。

**边界（如实记录）**：

- 64 条上限本身仍会挤掉最旧的一条。手动续跑不推水位线，所以那条若 `completed_at` 仍晚于
  `seen_until`，自动续跑还可能把它当候选再发一次 —— 本批没改，下一批处理。
- 合并顺序假设「内存里的 id 都比磁盘新」（第二十一批的读盘时序）。同一进程 `persist()`
  之后不再读盘，现有写者下成立；两个进程同时写同一作用域时这个假设不保证。
- 没有真机验证。

## §32.72 运行详情 tok/s 偏低：把工具耗时算成了模型时间、还把 TTFT 摊进速度（2026-11-02）

用户视角：同一次运行，运行详情里的「速度」比实际低一大截，工具越多的运行越难看。
一次 438 轮、59 分钟的运行报 **76.6 tok/s**，输出 233,356 token —— 看起来像模型很慢，
实际是分母算错了。

两个缺陷叠在一起：

1. **边界取错了时刻**：`modelEndMs` 拿该轮第一条 `tool` 记录当「生成结束」，而 `recordTool`
   写在工具**执行完**才落盘（`at` = 结束时刻、`ms` = 执行耗时）。于是整段工具墙钟被算成
   「模型在生成」：这次运行里 **1,439,216ms（47%）** 是工具。
2. **分母起点太早**：从 `request`（请求发出）起算，把 TTFT（首字延迟）与网络等待也摊进
   解码速度。旧 trace 上实测 11.1 tok/s 的一段，按「首字 → 结束」应是 66.7。

改成 dsh 的口径（`ui-chat/src/client/contract/turn-metrics.ts`：`decodeMs =
completedTime - firstTokenTime`，速度 = `decodeTokens / (decodeMs / 1000)`）：

- 追踪新增 `KIND_FIRST`：每个请求的**第一个内容块开始**时刻，每个请求只记一条
  （`AssistantBlockStart` 会来很多次，`awaitingFirstToken` 保证只写第一条）；
- 速度窗口 = `first` → 段内最后一条内容记录（`response/first/thinking/text/usage`），
  工具执行与首字等待都不在分母里；
- 旧 trace 没有 `first`，退回 `response`（响应头）—— 仍比「请求发出」接近解码；
- 分子只取该请求自己的 usage，但**扫描到下一次请求为止**：有供应商把 usage 放在工具调用
  之后，跟着生成边界截断会静默丢掉这段 token（速度虚高）；
- `modelMs` 保留（「模型耗时」展示），边界改 `tool.at - tool.ms`；它的 getter 与
  `AgentRunTrajectory.modelMsOf` 合并为同一个函数，避免两处规则漂移。

验证：

- **先红**：`model time ends where the tool starts not where its record was written`
  （断言 6,000，实测 7,000）；`decode speed excludes the wait before the first byte`
  （断言 66.67，实测 11.11）。
- **反证三处**：① 工具边界改回 `boundary.at` → 前一个用例红；② 速度改回 `tokens/modelMs`
  → decode 用例红；③ usage 扫描收回段内 → `usage reported after the tool still counts
  for that request` 红。恢复后主文件 sha256 均为 `c7edd27b…`。
- 定向 `AgentRunTrajectoryTest`（11）+ `AgentRunTraceStoreTest`（9）：**20 例 0 失败**。
- `STAGES=test,lint,assemble`：**1101 例 0 失败**；lint 无新增（baseline 183）；
  包 32,190,505 字节 `837b3e49729b4443…`。
- `STAGES=test-wide`：**2962 例 0 失败**（上一批 2959，新增的 3 条都在里面）。
- 真实数据（本机旧 trace，无 `first`，按 `response` 回退，故最后一列是下界）：

  | 运行 | 请求 | 输出 token | 旧口径 | 边界修正 | 纯解码 |
  |---|---|---|---|---|---|
  | `run-8edd17cc` | 438 | 233,356 | 76.6 tok/s | 145.2 tok/s | 172.7 tok/s |
  | `run-16fc8652` | 18 | 12,615 | 123.6 tok/s | 138.6 tok/s | 159.5 tok/s |

**边界（如实记录）**：

- 旧 trace 没有 `first`，回退 `response` 后仍含「响应头 → 首字」一小段等待，数字略低于
  真实解码速度；新 trace 不再有这个问题。
- 样本 trace 由上一版本写出，上表「纯解码」是下界而非最终值。
- 没有真机核对界面：数字来自磁盘 trace 与单测。
- `formatSpeed` 仍是 ≥100 取整、否则一位小数，与 dsh 的 ≥10 取整不同，未改。

## §32.73 手动续跑被 id 上限挤掉后，自动续跑把它又发了一遍（2026-11-02）

第二十二批留下的那条边界，把后果说清楚了：手动「继续」只记 id、不推水位线，
所以那条如果 `completed_at` 仍晚于 `seen_until`，理论上仍可能被自动续跑重发。
这次把「可能」变成了具体条件：`resumed` 只有 64 条（`BackgroundResumeStore.MAX_IDS`），
溢出时删最旧的；被删的 id 如果还晚于水位线，就真的重新成为候选，再发一遍。

为什么不能简单地「手动续跑就推水位线」：推了会把**更早的未处理任务**永久跳线
（静默丢任务比重复更糟）。于是改成每次 tick 先做一次**安全压缩**：

- 该作用域已结束的任务按 `completed_at` 分组；
- 从最早开始，只有**整组都已在 `resumed` 里**才把水位线推过这一组；
- 遇到第一条没处理过的立刻停 —— 越过的每一毫秒都确实处理完了。

整组一起看是关键：`Dispatch` 分支推 `completed_at - 1`，是为了让同一毫秒的其余任务
仍是候选；压缩是批量宣告，越过就没机会了，所以同毫秒必须全部处理过。实测过第一版
（逐条 + `-1ms`）：水位线停在 599，任务仍是候选 —— 等于没修。

压缩只在 `Idle` 分支自己落盘（其它分支本来就会 `persist`），否则重启后水位线退回，白做。

验证：

- **先红**：`manualResumeIsCoveredByWatermarkBeforeTheIdLimitCanDropIt` —— 水位线停在
  500（期望 600），模拟 64 条上限挤掉 id 后第二次 tick 又发一条。
- **反证两处**：① 压缩改回逐条 + `-1ms` → 同毫秒用例与集成用例红；② 去掉 watcher 的
  `markSeenUntil(compressed)` → 集成用例红、纯函数用例绿。恢复后两份主文件 sha 均为
  `a116f46b…` \/ `458c087c…`。
- 定向续跑相关（`BackgroundAutoResume*` + `BackgroundResume*` + `DetachedTask*`）：**121 例 0 失败**。
- `STAGES=test,lint,assemble`：**1103 例 0 失败**；lint baseline 183；包 32,192,689 字节
  `6d0d507f33017b59…`。
- `STAGES=test-wide`：**2964 例 0 失败**（上一批 2962，新增的 2 条都在里面）。

**边界（如实记录）**：

- 只有「连续且整毫秒都处理过」的段会被压缩。手动续跑的那条前面如果还压着一条没处理过
  的任务，它被挤出后仍可能重发 —— 下一批再评估要不要把 `resumed` 改成带时间戳的集合。
- `resumed` 仍是 64 条上限，超出后 UI 也不再记得「更早的续过」（按钮可能重现）。
- 没有真机验证。

## §32.74 缓存命中率把「已含缓存」的输入又加了一次（2026-11-02）

用量面板的「缓存命中率」一直偏低，和 provider 返回的记录对不上。查下来不是取数错，
而是**分母口径**错：

`AgentUsageLedger.Totals.cacheShare` 原来统一用 `cached / (cached + input)`。这个公式
只在「`input` 是未缓存输入」时成立 —— Anthropic 的 `input_tokens` 确实是未缓存输入，
但 OpenAI Chat 的 `prompt_tokens`、Responses 的 `input_tokens`、DeepSeek 的
`prompt_tokens`、Gemini 的 `total_input_tokens` **本身就已经包含缓存命中**。
对它们再加一次 `cached`，等于把命中 token 算了两遍：prompt=1000、cached=800 时
真实命中率 80%，旧公式给出 800 / 1800 = 44%。

dsh 最新源码（`packages/experimental/client-ui-stats/src/client/stats-usage.ts`）的口径
可以直接对照：`contextTokens = inputTokens + cacheReadTokens + cacheWriteTokens`，
`cacheHitRate = cacheReadTokens / contextTokens`；`packages/llm/token-meter/src/turn-usage.ts`
也把 `uncachedInputTokens`、`cacheReadTokens`、`cacheWriteTokens` 定义为**互不重叠**的三个桶。
换句话说，命中率的分母必须是「总输入」，而不是「厂商原样的 input 再拼一次 cache」。

修法：

- `AgentTokenUsage` 新增 `promptTokens`（总输入）。四个 provider 各自折算：
  - OpenAI Chat：`prompt_tokens`（或 `input_tokens`）；
  - OpenAI Responses：`input_tokens`（或 `prompt_tokens`）；
  - Anthropic：`input_tokens + cache_read_input_tokens + cache_creation_input_tokens`；
  - Gemini Interactions：`total_input_tokens`。
- 账本 `Record` / `Totals` 新增 `prompt`；新记录写 `prompt`，命中率 = `cached / prompt`
  并夹到 `[0, 1]`；**旧账本没有 `prompt`**，读取时回退到旧公式，行为不变。
- `AgentRuntimeWire` 新增 `usage_prompt` 键（旧 Runtime 不发送 → 读到 null → 回退）。
- 文案从「缓存命中」改成「缓存命中率」，三语同步；说明改为「cached ÷ 服务商返回的总输入」。

验证：

- **先红**：新增 5 条账本用例，在旧公式下 4 条红（`uses the reported prompt…`、
  `uses disjoint prompt…`、`clamped…`、`mixed records…`），旧记录回退那条绿。
- **反证两处**：① `cacheShare` 改回 `cached / (cached + input)` → 同样 4 条红；
  ② Anthropic 的 `promptTokens` 置 null → `usageReportsContextOccupancy…` 红。恢复后定向全绿。
- 定向 5 个类（`AgentUsageLedgerTest` + 四个 provider 测试）：**84 例 0 失败**。
- `STAGES=test,lint,assemble`：**1103 例 0 失败**（这 5 个类不在 `FAST_TEST_FILTERS`，用例数不变）；
  lint baseline 183；包 32,193,681 字节 `533e08ad83cce0fe…`。
- `STAGES=test-wide`：**2969 例 0 失败**（上一批 2964，新增的 5 条账本用例都在里面）。

**边界（如实记录）**：

- 旧账本没有 `prompt`，仍按旧公式回退 —— 它们的命中率继续偏低，直到滚出 180 天窗口；
  文件里没有 provider 协议标记，无法无歧义地追溯修正。
- `Totals.totalTokens` 仍是 `input + output`，没有改成 `prompt + output`；Anthropic 的
  缓存读写仍不进「总 token」。这是既有口径，与本轮命中率修复分开。（§32.75 已改为 `prompt + output`。）
- `AgentRuntimeWireTest` 带 `@Config(sdk = [33, 36])`，在 Linux/aarch64 上必撞 Robolectric
  native runtime（门禁注释已写明），所以 `usage_prompt` 的 Bundle 往返**没有在本机验证**；
  provider 解析与账本计算都有定向用例覆盖。

## §32.75 总 token 改成服务商口径，并补上从未接线的 prompt 写入（2026-11-02）

§32.74 把命中率的分母改成了「服务商总输入」，但**写入端没接上**：`AgentRuntimeRunExecutor.recordUsage`
构造 `AgentUsageLedger.Record` 时只填了 `input / output / cached / reasoning`，没有传 `prompt`。
读取端的旧公式回退因此永远生效 —— 线上新记录也拿不到总输入，§32.74 想修的两件事（命中率分母、
本轮的总量口径）都不会真正生效。读取端有键、写入端不写，日志与自检全绿也看不出来。

本轮做两件事：

1. **补接线**：`Record` 增加唯一构造入口 `fromUsage(...)`，运行时只经它记账，`prompt` 与其它
   四个字段一起落盘；用例直接守这个入口（不传 prompt 就红）。
2. **总 token 改口径**：`input + output` → **`prompt + output`**。
   - Anthropic 的 `input_tokens` 不含缓存读写，旧口径下「缓存命中越多、总 token 越少」，
     与 OpenAI 系（`prompt_tokens` 已含缓存）不可比：同一段上下文，Anthropic 报 200 + 100 = 300，
     OpenAI 报 1000 + 100 = 1100，差的正是缓存那一截。
   - 旧账本 / 旧追踪没有 `prompt`，读取时按当时的 `input + output` 回退 —— 不能拿命中率那个
     **合成**的 prompt（`input + cached`）当总量，否则旧记录会把缓存算两遍。

落点三处，口径必须一致：

| 位置 | 改动 |
|---|---|
| 账本 | `Totals` 新增逐条累加的 `tokens`；`summarize` 按 `(prompt ?: input) + output` 累加 |
| 运行追踪 | `KIND_USAGE` 行新增 `prompt` 键；`Entry.promptTokens`、`Trace.totalTokens`、列表页 `Summary.totalTokens`（另一条直接扫 JSONL 的路径） |
| 轨迹投影 | 每轮 `Turn.tokens` 同口径 |

验证：

- **先红 4 条**（旧值 → 期望）：账本 `cache reads…` 300→1100、`mixed eras…` 450→1250、
  追踪 `usage lines…` 400→1200、轨迹 `turn tokens…` 300→1100；两条旧记录回退用例本来就绿（守住回退）。
- **反证三轮**：① 三处总量改回 `input + output` → 同 4 条红；② 只把列表页 Summary 的读取改回
  `in` → 追踪用例在第 91 行（`store.list()` 那条断言）红，说明 `Trace` 与 `Summary` 两条读取路径
  各自被守住；③ `fromUsage` 不传 `prompt` → 接线用例红。恢复后三个主文件 sha256 与改前一致。
- 定向 6 类 **63/0** → 门禁 `STAGES=test,lint,assemble` **1103/0**（lint baseline 183，
  包 32,194,885 字节 `18e10deee47543f2…`）→ `STAGES=test-wide` **2977/0**（上批 2969，新增 8 条都在里面）。

**边界（如实记录）**：

- 面板上「输入」仍按厂商原样显示：Anthropic 不含缓存，所以新口径下「总 token」会大于
  「输入 + 输出」。用量说明补了一句「总 token = 总输入 + 输出」，三语同步。
- 旧账本 / 旧追踪没有 `prompt`，继续按旧口径回退，直到滚出窗口（180 天 / 保留条数）。
- 本轮没改线协议（`usage_prompt` 上一批已有），不涉及新的 Bundle 往返；
  `AgentRuntimeWireTest` 在 Linux/aarch64 上仍跑不了 Robolectric，这一点没变。
- 没有真机验证：门禁只跑 JVM 单测，账本与追踪的落盘路径由 `TemporaryFolder` 用例覆盖。

## §32.76 总输入要能自证：运行详情、导出与日志（2026-11-02）

§32.75 把总 token 改成 `prompt + output` 之后，运行详情与 Markdown 导出仍然只写
「输入 / 输出 / 缓存」：Anthropic 的输入不含缓存，界面上出现「输入 200 · 缓存 800」而头部写
「token 1100」，两个数字对不上；OpenAI 系反过来（输入已含缓存），同一个词在两种协议下含义不同。

改动：

- 追踪里有 `prompt` 键时，运行详情与导出改写「总输入」，与头部 total 同口径；旧追踪没有该键时
  继续写「输入」—— 不把原样 input 冒充成总输入（新增 `runs_entry_usage_detail_prompt`，三语）。
- 用量面板的说明补一句「Anthropic 的输入不含缓存（总输入 = 输入 + 缓存）」，让面板上的总量
  可以手算核对；面板本身的「输入」保持厂商原样，不换字段（旧记录没有真实总输入，
  换字段等于把合成值当事实展示）。
- `UsageReceived.toLogLine()` 带上 `prompt=`。以后再出现「读取端有键、写入端没写」，
  从一行日志就能看出来，不必去翻二进制文件。

验证：

- 先红 1 条（导出仍写「输入」）；同一批补的旧记录回退用例本来就绿，守住「不冒充」。
- 一致性用例把 `Trace.totalTokens`、列表页 `Summary.totalTokens`、轨迹各轮之和钉在一起：
  同一份记录（一条带 prompt、一条旧格式）三条路径必须同值 1250。
- 反证两处：① 导出分支退回「永远写输入」→ 导出用例红；② 轨迹口径退回 input-only →
  一致性用例红（1250 → 450）。恢复后两个主文件 sha256 与改前一致。
- 定向 3 类 21/0 → 门禁 1103/0（lint baseline 183，包 32,196,469 字节 `31e9b9ac0379b861…`）
  → test-wide 2980/0（上批 2977，新增 3 条都在里面）。

**边界（如实记录）**：

- 面板的「输入」仍是厂商原值（Anthropic 不含缓存），所以「总 token」会大于「输入 + 输出」；
  说明文案已经写出换算关系，但没有把面板字段换成 prompt。
- 对话消息里持久化的 `TokenUsageUi` 仍没有 prompt 字段：它目前不参与任何总量展示，这轮不动；
  将来若要在气泡上显示总 token，需要一起补上并做旧数据回退。
- 运行详情与导出的新文案只在 JVM 单测里验证（导出是纯 Kotlin；Compose 文案没有截图验证）。
- 没有真机验证。
