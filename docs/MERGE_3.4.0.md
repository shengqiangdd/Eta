# 合并上游 3.4.0（本地分叉）

本文件记录把上游 `origin/main`（Eta 3.4.0，`e90922a`）合并进本地 `main`（`26a4b42`）的过程、取舍与验证结果。合并基是 `6bedba9`（3.3.5），本地在这一基线上有 179 个上游没有的提交。

## 1. 范围

| 项 | 值 |
| --- | --- |
| 本地合并前 | `26a4b42` |
| 上游 | `e90922a`（`chore(release): 准备发布 3.4.0`） |
| 合并基 | `6bedba9`（3.3.5） |
| 改动 | 71 个文件，+3804 / −1325（相对本地） |
| 冲突 | 19 个文件未合并（含 2 个文档、3 份多语言资源） |
| 备份分支 | `backup/pre-merge-3.4.0`（= `26a4b42`）、`backup/pre-merge-3.3.5`（= `5cdc1fa`） |

## 2. 上游本轮带来的提交

| 提交 | 内容 | 对本地的影响 |
| --- | --- | --- |
| `b0dc20e` | 后台虚拟屏执行、智能画布分流、顶部胶囊装裱视窗 | 新增 `agent/virtualdisplay/*`、`VirtualDisplayStageCard`、`hook/system/VirtualDisplaySystemHooks` |
| `419a14c` | 移除系统语音识别选项，打磨助手浮窗界面与动效 | 本地 `speechIssueMessage` 按问题分类的提示被上游新实现取代；`AsrProvider` 只剩千问/豆包 |
| `2bc0f21` | Android 与 Linux 双环境分工、共享目录映射提示 | 终端执行上下文与提示文案 |
| `f0617e4` / `3019c43` | README 补充后台虚拟屏、PR 与贡献说明 | 中英两份 README 内容合并 |
| `c1ac2d6` | 关闭 Dependabot 自动版本更新 | 删除 `.github/dependabot.yml`（基线里存在，按上游意图删除） |
| `6dcc665` | 兼容 API 33 虚拟屏创建签名，Runtime 权限失败关闭测试 | 虚拟屏控制器与测试 |
| `e90922a` | 版本号 3.4.0 | `versionCode 2026103101` / `versionName 3.4.0` |

## 3. 冲突处理

19 个未合并文件按四类处理。凡上游新结构与本地扩展同时存在的位置，都保留两边：上游提供新能力，本地保留既有语义。

### 3.1 两套虚拟屏的分层（本轮最关键的一处）

合并后有**两个同名 `VirtualDisplayController`**，职责不同，必须分清：

| 位置 | 形态 | 职责 |
| --- | --- | --- |
| `agent/device/VirtualDisplayController` | `class`（本地，435 行） | **受管虚拟屏**：按会话建屏、列表、释放、预算与调试宿主 |
| `agent/virtualdisplay/VirtualDisplayController` | `object`（上游，888 行） | **智能画布**：`display=auto` 分流、`launchAppOnVirtualDisplay`、`handoffToMainDisplay`、`present_to_user`、装裱视窗与快照 |

`AgentLocalTools` 用 import 别名区分：`ManagedVirtualDisplayController` 与 `CanvasVirtualDisplay`。调用方看到别名就知道在操作哪一层，不靠记忆。

`launch_app` 同时接受两种指定方式，并明确优先级：

- `display_id`（整数）：精确屏号，**显式写了就优先**，包含 `0` = 主屏；
- `display`（`auto` / `virtual` / `main`）：交给 `CanvasRoutingPolicy` 决策。

工具描述写明「与 `display` 同时给出时以 `display_id` 为准」。执行路径里先解析 `display_id`，命中画布屏时再走 `CanvasRoutingPolicy` 分流。

观察与手势贯穿 `displayId`：`observe_screen` 取当前活动画布（`targetDisplayId = 活动画布 ?: 指定屏 ?: 主屏`），`input_text` 带 `display_id` 时改走 `input -d`——无障碍的文本写入只覆盖默认屏，走无障碍会把字打到主屏焦点上。

### 3.2 多语言资源

1. 三语各补齐上游新增键（190 条），XML 可解析、三份文件内无重复键；
2. 合并后 `tools/dead_strings.py` 报 **210 条死文案**（基线 0 条）。逐条核对来源：
   - 199 条上游自己也没有引用（`action_add`、`action_install` 这类通用动作词）；
   - 11 条上游仍在使用，但本仓库对应界面是本地实现（例如自动朗读开关用 `speech_auto_read_on/off`，不是上游的 `speech_enable_auto_read`）。
   按仓库既定规则（死文案只许减少，不往基线里加）**三语一起删除**，没有往 `dead_strings_allow.txt` 增加条目。
3. 上游 `419a14c` 移除系统语音识别后，本地 21 条 `voice_speech_*` / `voice_model_*` / `speech_asr_system` 失去引用，同样三语删除。

删除后门禁口径归零：资源总数 2153、死文案 0。

### 3.3 文档

- README 中英两份做**内容合并**，不是选边：以上游 3.4.0 的能力列表为骨架，补回本地独有且上游没有的能力——环境变量、屏幕与能耗（保持屏幕唤醒）、消息操作、编码工作流（AGENTS.md / `project_info` / `dry_run` / 检查点）、文件工具的多处编辑与跨文件回滚。
- 顺带纠正本地一处过时说法：原 README 写「语音识别可选择系统服务」，而上游 `419a14c` 已移除系统识别选项，改为千问 / 豆包在线接口。文档跟着代码改，避免继续承诺已删除的能力。
- 英文版此前缺中文版早有的三条能力（环境变量、屏幕与能耗、编码工作流），本轮补齐，中英重新对齐。
- `.github/dependabot.yml` 随上游 `c1ac2d6` 删除（上游已关闭自动版本更新与外部 PR）。

### 3.4 构建脚本

`app/build.gradle.kts` 只取上游的 `versionCode` / `versionName`，其余保留本地：

- canary 并行安装（`-Peta.applicationIdSuffix=.canary`）；
- lint 基线 + `warningsAsErrors=true`（否则新增 warning 不会让门禁变红）；
- release 缺签名密钥时回退 debug 签名，便于真机验证混淆后的包。

## 4. 验证

在本机 Linux（Debian chroot、aarch64）跑 Eta 自带门禁 `tools/gate.sh`：

| 项 | 结果 |
| --- | --- |
| 契约（minSdk） | ✓ 33 |
| 测试类归属 | ✓ 491 个测试类（快速子集 128 + 基线 363），没有静默不跑的 |
| UI 动作接线 | ✓ 7 个动作接口 / 65 个动作，每个都有发出点 |
| 死文案 | ✓ 0 条（基线 0） |
| 单测（白名单内） | ✓ 1111 通过 / 0 失败 |
| lint | ✓ 报告无新增问题；baseline 155 条命中（共 166 条，另 11 条依赖更新类未复现、按规则放过） |
| 打包 | ✓ `Eta-v3.4.0-debug.apk`，32,272,553 字节，sha256 `13494e4d3b509cf8…` |

全量 `:app:testDebugUnitTest` 另有 445 条失败，全部是 Robolectric 原生运行库在 Linux aarch64 上不可用（`The Robolectric native runtime is not supported on Linux (aarch64)`），属本机环境限制，不是合并引入；这些用例只能在上游 CI 或 x86 环境跑。

### 4.1 lint 从「内部崩溃」到「能拦住新增」

合并后第一次跑 lint 时，`:app:lintAnalyzeDebug` 在 `AsyncExecutionService.getService must not return null` 处内部崩溃，
而那一次 ConcurrentModification 的时间点与“正在改同一个文件”重合，当时只能记为「疑竞态，不算绿」。
本轮在干净工作树上重跑，**崩溃不复现**；lint 完整跑完，并报出 10 条真错误：

| 类型 | 处数 | 处置 |
|---|---|---|
| `PrivateApi`（反射 `IActivityManager` / `ActivityThread` / `DisplayManagerGlobal` / `ActivityTaskManager`） | 6 | 保留：虚拟屏的 root 桥接与「交还主屏」没有公开 API 可替代；改为在具体函数上 `@SuppressLint("PrivateApi")` 并写清失败降级路径 |
| `ObsoleteSdkInt`（`SDK_INT >= P`，minSdk 33） | 2 | 删除恒真判断 |
| `UseKtx`（`Bitmap.createBitmap`） | 1 | 改用 `androidx.core.graphics.createBitmap`；裁剪重载没有 KTX 版，保留平台 API 并注明 |
| `SdCardPath`（上下文说明文本里的 `/sdcard`） | 1 | 不是路径拼接，`@SuppressLint("SdCardPath")` + 注释说明真实访问走 workspace |

同时清掉 28 条存量（baseline 195 → 166）：`AutoboxingStateCreation` 8 条（`mutableIntStateOf` / `mutableFloatStateOf`，
Compose 官方建议，避开每次写入的装箱）、`MutableCollectionMutableState` 1 条（State 里改存只读 List）、
`TypographyEllipsis` 20 条（三语进度文案的 `...` 改 U+2026，Material 排版惯例）。
清理时还发现门禁自身一个缺陷：`tools/lint_baseline_audit.py` 只认「N errors and M hints were filtered」，
而本次报告只剩「N errors were filtered」——账目实际是平的，却被判成解析失败。已改为两种句式都接受，
并用「注入一条假 baseline」验证它仍会报红。

## 5. 遗留

1. **两套虚拟屏仍然同名**。现在靠 import 别名区分，可读性够用但不够干净；若要改名，必须一次改完所有调用点，不能只改一半。
2. **上游 11 条文案本仓库未接线**，其中「删除运行中的会话」已在 `52defca` 接线（先停任务、等运行态落下再删）；
   其余 10 条（如备份文件打开失败的提示）仍是上游 3.4.0 的界面改进，本地对应界面还是旧版，
   要不要跟进需要单独排期 —— 不在合并提交里混行为变更。
3. **真机验证**：canary 包安装与冒烟结果见提交说明；本轮另补了「回合导航条压正文」的留白修复与截图核对。
