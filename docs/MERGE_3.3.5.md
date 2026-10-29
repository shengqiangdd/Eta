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
