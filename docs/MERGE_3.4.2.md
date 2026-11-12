# 合并上游 3.4.2（本地分叉）

本文件记录把上游 `origin/main`（Eta 3.4.2）合并进本地 `main` 的过程、取舍与验证结果。
前两版见 `MERGE_3.3.5.md`、`MERGE_3.4.0.md`。

这一版和前两版最大的不同：**合并提交当时是「编译通过」就算完成**，单测、lint、装包、覆盖安装
四项都没做（合并说明里自己写了「未完成」）。后续补齐这四项时，暴露出 10 个实质缺陷，
其中 3 个是用户能直接撞到的功能问题。这份记录的重点是它们，以及为什么门禁没拦住。

## 1. 范围

| 项 | 值 |
| --- | --- |
| 本地合并前 | `6b3ea408`（WIP：冲突标记全部清零） |
| 上游 | 25 个提交（含 3.4.2 版本号） |
| 改动 | 71 个文件，+5336 / −8188 |
| 合并提交 | `ab93cc78`（单父提交，squash 式） |
| 后续修复 | `ab93cc78..` 共 10 个提交，见 §4 |

改动以「删」为主（−8188），因为上游重写了聊天列表、顶栏和记忆界面。

## 2. 上游本轮的结构性变化

| 变化 | 对本地的影响 |
| --- | --- |
| 首页顶栏改为双行头部（标题/角色 + 模型选择器常驻） | 本地顶栏的「视图模式」「自动朗读」入口在旧分支里，换分支后丢失 |
| 聊天列表改用新的滚动容器 | 分支、置顶、图片点击等行内交互被简化掉 |
| 记忆界面重写为 `MemoryDocumentCard` | 章节目录/单章编辑的 UI 入口整个消失 |
| Miuix/COUI 双风格并存 | 新增 UI 必须同时适配两套边距 |
| 会话消息改按需载入（`loadMessages`/`loadHistory`） | 旧 API `loadConversation`/`loadAllMessages` 消失，测试全部失效 |

## 3. 冲突处理原则

沿用上一版：上游新结构与本地扩展同时存在的位置，两边都保留——上游提供新能力，
本地保留既有语义。纯风格改造取上游。

这次补充一条教训：**「纯 UI 风格改造取上游」这条原则执行过头会丢功能**。
顶栏、聊天列表、记忆界面三处都按「UI 改造」处理，结果上游的新 UI 覆盖了本地的功能入口，
而合并时只看编译是否通过，没有发现。

## 4. 合并后补齐验证暴露的问题

按发现顺序列出。前 3 个是用户可感知的功能缺陷。

### 4.1 首页顶栏丢了「视图模式」和「自动朗读」（`c959dc28`）

`HomeMiuixTopBar` 分支的右侧只挂了溢出菜单，而 `ChatViewModeMenu`（全部/对话/轨迹）和
`SpeechAutoReadToggle` 仍留在旧的 `SmallTopAppBar` 分支里——首页走新分支，这两个入口
就再也渲染不出来。

修法：按旧顶栏顺序挂回双行头部右侧，并在宽屏下收起「会话历史」按钮让出宽度（与旧顶栏一致）。

顺带确认 `SpeechAutoReadToggle` 在 TTS 提供商为 NONE 时不渲染，是既有设计，非回归。

### 4.2 图片点开全屏从未接线（`49c30656`）

`ChatImagePreviewHost` 是一个完整的全屏查看器，但合并后**没有任何调用方**——
`AgentAppShell.kt` 里 import 还在，包裹层被去掉了。这是漏 merge 的典型特征：
import 留着说明曾经用过。

修法：壳层加回 host 包裹，`UserMessageBubble` 的图片可点开（带整组图与当前索引，
查看器内可左右翻）。预览器为空时（Preview 等场景）图片不可点。

### 4.3 记忆章节编辑没有 UI 入口（`49c30656`）

`AgentMemoryUiState` 的 `sections`/`sectionEditor`、reducer 的 `OpenSection`/
`SectionDraftChanged`/`SaveSection`/`CancelSection` 全都实现好了，`AgentAppRoot` 也在处理，
唯独界面没有渲染入口，所以那 4 个动作永远不会被发出。

修法：加章节目录（标题 + 行范围 + 字数）和单章编辑器；保存只替换正文，标题行不动。

### 4.4 重名章节无法编辑（`555a87d7`）

上一项修完后，用真实 `MEMORY.md`（783KB、241 个标题）验证，发现标题真的会重复：

```
382: ## 踩坑记录       905: ### 踩坑记录
437: ## 门禁与产物     900: ### 门禁与产物
```

而 `AgentMemoryDocument.find` 在重名时返回 `null`，目录按标题发 `OpenSection`，
结果是点目录里任何一行都提示「Section "X" was not found」——章节明明列在那里，
却根本编辑不了。

修法：目录项带上 `startLine`，按行号定位。`ReplaceSection` 增加可选 `startLine`
（默认 `null`），UI 传行号时按行号找，Agent 工具那条老路径不传，维持原有的按标题
解析与歧义报错，向后兼容。

顺带确认层级截断正确：`## 门禁与产物`（437）的正文到 440 行下一个 `##` 为止，
不会吞掉 900 行的 `### 门禁与产物`。

### 4.5 `endLine` 的注释与实现矛盾（`b991aa24`）

`AgentMemorySection.endLine` 的注释写「1-based，包含」，但 `replaceSection` 实际当
`subList` 的 `to` 用，是**排他**的。章节目录直接显示 `endLine` 会多报一行。

修法：展示时减一，注释改对，并补两个用例把语义钉住，防止以后有人按错注释「修正」回去。

### 4.6 追踪排序在 mtime 并列时不确定（`cab18dfc` → `6c3ec472`）

`latestRunIdFor`/`list` 只按 `lastModified()` 降序排。文件系统时间戳粒度粗（真机上是
秒级），同秒写出的多份追踪会拿到相同 mtime，此时 `sortedByDescending` 对并列元素的
次序取决于目录遍历顺序，换个文件系统或换次运行就变。

后果不只是测试抖动：同一会话连跑多轮时，速度段可能挂到前一轮的追踪上。

第一版修法用文件名末尾数字做次级键——**这是错的**。真机取证（47 份文件）确认真实
runId 形如 `run-<uuid>`：

```
run-04a1cfac-c749-4614-a5a1-f83b89f310e7.jsonl
call_00_A8qlwqOCnWUireZAtTtm3864-sub_1.jsonl
```

末段是十六进制而非递增序号（只有测试里才叫 run-1/run-2），取出的数字没有单调性。

第二版改用 meta 行的 `at`（毫秒）。为了不为此读每个文件头（`lookup` 最多看 512 份），
先按 mtime 排，只有检测到确实存在并列时才回退去读 `at`。

### 4.7 单测在合并后整体不可编译（`5c2fd9a1`）

67 条编译错误，整个 `:app:testDebugUnitTest` 跑不起来。原因都是生产 API 演进后测试没跟上：

| 原因 | 数量级 |
| --- | --- |
| `AgentLocalTools` 去掉 `isToolEnabled`、`guiControl` 改类型 | 若干 |
| `AgentConversationPersistence.branch` 返回值瘦身，内容改从库里读 | 1 个类重写 |
| `ConversationMetadata.isPinned` → `pinned` | 1 处 |
| `DataBackupScreen`/`WorkspaceScreen` 新增参数 | 各 1 处 |
| `ToolCatalogUiTest` 缺 `@RunWith(RobolectricTestRunner)` | 1 个类 |

另有 3 个用例是**语义已变**、需要按新语义重写，而不是改签名：

- 「关闭的工具回 TOOL_DISABLED」：开关从 `AgentLocalTools` 上移到 `Prefs`
- 输出总量：`7985ab12` 起 Gemini 的 thought 在解析层就折算进 output，测试仍按旧语义断言
- 用户暂停：`a62497ab` 起暂停按 run 记，不再全局冻结

### 4.8 Robolectric 测试在本机被静默跳过（`88fb2248`）

`app/build.gradle.kts` 有一整套自动排除逻辑：Linux/aarch64 上没有 Robolectric 的
native runtime，93 个带 `@RunWith(RobolectricTestRunner)` 的测试类会被整类跳过。

问题是我在 4.7 里**给 `ToolCatalogUiTest` 加了 `@RunWith`**，把它从「能跑」变成了
「被跳过」——这个类原本只是漏了 import 和注解，7 个断言全是纯 JVM 的。

修法：把唯一需要 Android 的用例（要真 `SharedPreferences`）拆到 `ToolCatalogPrefsTest`，
主类恢复成纯 JVM 测试。修完本机能跑到的用例 +7。

**顺带踩到一个陷阱**：那个排除逻辑是 `text.contains("RobolectricTestRunner")`，
在**注释里**提到这个词也会把整个类排除掉。第一版注释就踩了，已改成不提这个词。

### 4.9 测试分层没有归属检查（`88fb2248` 同批）

`tools/check_test_subset.py` 会检查每个测试类要么在快速子集里、要么在基线里，
不允许「静默不跑」。3 个新类没有归属，已登记进 `test_subset_baseline.txt` 并写明原因。

### 4.10 16 条改名遗留的孤儿文案（`49c30656`）

`action_pin_conversation`、`conversation_branch*`、`tools_*`、`reasoning_chip_*` 等
22 条文案没有引用。逐项核实后：3 个「功能丢失」里其实有 2 个是误报——
置顶、分支、工具开关的**功能都完好**，只是改用了别的文案（置顶用 `action_pin`/
`action_unpin` 并按 `isPinned` 切换）。只有图片预览和记忆章节是真的丢了。

确认无用的 16 条按项目规则三语一起删掉。

### 4.11 一个我的误判：COUI 顶栏并没有丢（`未提交）

我在排查死 import 时看到 `AgentAppShell.kt` 里有 24 条 COUI 相关的 import
（`CouiMenuItem`、`couiMenuAnchor`、`CouiTopAppBarDefaults`…）没被使用，又发现
合并前文件里有 9 处 `CouiTopAppBar`/`CouiTheme`、现在是 0，于是断定「合并时整段
COUI 顶栏被丢掉，选 COUI 的用户看到的是 MIUIX 顶栏」。我按上游 3.4.2 的代码
重写了一份 COUI 分支，结果编译报 `CouiLayoutScaffold`、`AgentTopBarChatHeader`
签名不匹配。

进一步查才发现：**上游已经把两套风格合并进 `AgentTopBarChatHeader` 内部**，
它自己带 3 处 `isCoui` 分支（`AgentModelPicker.kt:564` 起）。也就是合并后首页
仍然尊重风格选择，只是实现从「两套 Scaffold」变成了「一套 Scaffold + 风格感知
的组件」。我把它回退掉了（`git checkout` 整个文件）。

那 24 条死 import 确实是合并删代码后的残留（`SpeechAutoReadToggle` 已直接挂在
顶栏，不再需要菜单里那套 `chatAutoSpeak` 逻辑），但它们不意味着功能丢失。

教训：**死 import 是「这里改过」的信号，不是「这里坏掉」的结论**。必须先确认
对应功能是否还能到达，再决定是删 import 还是补回调用。我在 §4.2 的图片预览上
做对了（host 确实没有调用方），在 COUI 上差点做反。

### 4.12 lint 首次跑出 13 条错误（本次提交）

合并说明里写明 lint 未做，而它一直没补。本次首次全量跑（JDK21，先 `-e` 取全部错误）
出了 13 条 Error，分 4 类：

| 位置 | 条数 | 原因 | 处理 |
| --- | --- | --- | --- |
| `AgentAppTheme.kt` | 9 | `LocalContextResourcesRead` | 局部压制 + 写清为何必须这么写 |
| `CouiControls.kt` | 2 | `ObsoleteSdkInt`（`SDK_INT >= 33`） | 删除（minSdk 已是 33） |
| `SharedFoldersScreen.kt` | 1 | `UseKtx`（`Uri.parse`） | 改用 `toUri()` |
| `BrowserAddressBar.kt` | 1 | `ModifierParameter` 位置 | `modifier` 提到 `enabled` 之前 |

`AgentAppTheme.kt` 那 9 条不能按提示改成 `LocalConfiguration`——那段直接改写
`context.resources.displayMetrics` 是**有意的**：独立窗口（Dialog / Popup）的新
`AndroidComposeView` 从宿主 `Context.resources` 读密度，只读 `LocalConfiguration`
拿不到“别人将来会读的那个值”，界面缩放功能会失效。所以用 `@Suppress` 局部压制
并在 `DisposableEffect` 里还原，并把理由写在代码里，避免下一个人按 lint 提示改坏它。

顺带修了一处重复：原来的 `initialDisplayDensity` 与 `baseDensity` 算的是同一个值，
合并成一处。

## 5. 验证

| 项 | 结果 |
| --- | --- |
| `:app:compileDebugKotlin` | 通过 |
| `:app:compileDebugUnitTestKotlin` | 通过 |
| `:app:testDebugUnitTest` | 3639 例，0 失败，8 跳过 |
| `gate.sh STAGES=test-wide` | 七项静态检查全过 |
| `gate.sh STAGES=lint` | 13 条错误 → 0 |
| `:app:assembleRelease` | 通过（R8），APK 7.8MB，DEX 9.1MB |
| release 产物实机 | 装包后启动成功，界面完整渲染 |
| canary 装机 | 启动正常，顶栏三按钮渲染、视图模式菜单可弹出并切换 |
| 图片点开全屏 | **未在真机上完整走通**（虚拟屏在系统文件选择器上会熄屏），仅代码与门禁覆盖 |
| 记忆章节 UI 渲染 | **未验证**，见 §7 |

## 6. 门禁为什么没拦住

这是本次最值得记的一条。三个功能缺陷（4.1–4.3）都是「编译通过、静态检查通过、
但用户用不了」，因为：

1. **合并提交的标准是「编译通过」**。没有把单测/lint/装包/安装列为合并的一部分，
   于是它们留到了后面才发现。
2. **`check_ui_actions.py` 当时不在合并后的首次验证里**。它专门检查「每个 UI 动作
   都有发出点」，正好能抓住 4.3（4 个动作无发出点）。跑一次就能发现。
3. **`dead_strings.py` 同理**。22 条死文案是功能入口消失的强信号，但它当时也没跑。
4. **编译通过 ≠ 功能可用**。「import 了没用」这种漏 merge 的典型特征，编译器只会给
   警告，而 Kotlin 默认不把未使用 import 当错误。

可执行的改进：

- 合并的完成标准改成 `gate.sh STAGES=test-wide` 全绿 + 装包安装，而不是编译通过
- `check_ui_actions.py` 和 `dead_strings.py` 已经是门禁的一部分，但**跑不跑取决于
  人记不记得**。这一点没法靠工具解决，只能靠把门禁放进合并流程
- 考虑在 CI 或 pre-push hook 里跑门禁的静态部分（它们是秒级的，成本很低）

## 7. 遗留

### 记忆章节 UI 的渲染在本机验证不了

这一块必须说清楚：我新加的约 130 行 Compose 代码（章节目录 + 单章编辑器）
在本环境里**从未被渲染过一次**。已做到的验证只有：

- `:app:compileDebugKotlin` 通过（语法/类型正确）
- `toSectionUi()` 的 4 个纯函数用例（数据源正确）
- 门禁的 UI 动作检查（4 个章节动作都有发出点）

做不到的原因：

1. 虚拟屏上的应用会被系统很快回收，而记忆界面需要先造数据才能看到目录，
   操作链条太长
2. 项目没有 `compose-ui-test` 依赖，无法写 Compose 渲染测试
3. 即使有，Robolectric 在本机 aarch64 也跑不了（§4.8）

所以我只能保证它类型正确、数据源正确、动作接线正确，**不能保证它布局好看或
不崩**。实际用过之后如果有排版问题（比如章节多时目录限高 220dp 是否合这、
编辑单章时卡片让位到 120dp 是否够看），需要根据真实观感再调。

- 图片预览的真机验证没做完，建议实际用一次确认
- `AgentMemorySectionUi.endLine` 的排他语义现在有测试保护，但它和注释曾经矛盾过，
  说明这个 API 的名字不够自解释；将来如果大改记忆模块，建议改名为 `endExclusive`
- 合并提交是单父（squash 式），历史里没有保留上游 25 个提交的链。如果以后要按上游
  单个提交追查某一行的来历，会查不到
