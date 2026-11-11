# Eta 设置与管理组件

Eta 支持全局界面样式切换，提供 **COUI** 与 **Miuix** 两种选择。新安装及升级后尚未明确保存样式选择的用户默认使用 **COUI**；用户在“设置 → 外观与主题 → 界面样式”明确选择后，后续升级保留已选样式。两套界面共享同一套业务状态、Store、Repository 与路由栈，切换样式时通过 `AgentAppTheme` 的 `movableContentOf` 移动同一组合树，不丢失导航历史、页面草稿或正在运行的任务。

## 根主题与缩放不变式

- `AgentAppTheme` 同时提供 `CouiTheme`（COUI 模式）与 `MiuixTheme`，并通过 `LocalUiStyle` 暴露当前 `AppearanceUiStyle`（`COUI` / `MIUIX`）。
- COUI 模式在开启模糊且设备支持 `RuntimeShader` 时，在根部挂载 `ProvideCouiMenuBackdrop(isolateContent = false)`，供全局 COUI `Menu`、`Dialog` 与 `InputDialog` 采样窗口背景。
- 界面缩放以 `configuration.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT` 作为 100% 尺寸基准，并在 `applyInterfaceScale = true` 时同步写入宿主 `context.resources.displayMetrics.density`，保证主页面、独立 Activity（`AgentBrowserActivity`）以及 `Dialog` / `Popup` 独立窗口拥有一致的缩放比例。

## 组件选择与双界面组织

页面层将 `rememberSaveable`、草稿状态、Store 与副作用声明在 `when (LocalUiStyle.current)` 分支之前，再分别组织 COUI 与 Miuix 的界面组合：

| 场景 | COUI 组合（`ui/components/coui/` 及 `com.itos.coui.*`） | Miuix 组合（`ui/components/` 及 `top.yukonga.miuix.kmp.*`） |
| --- | --- | --- |
| 二级页（Column + 回弹滚动） | `CouiScaffoldPage`（`Scaffold` + `LargeTopAppBar` / `TopAppBar` + `couiVerticalScroll`） | `MiuixScaffoldPage` |
| 二级页（长动态列表） | `CouiLazyScaffoldPage` | `MiuixScaffoldPage` |
| 自定义容器二级页（分页、整页编辑器） | `CouiScaffold` | `MiuixScaffold` |
| 顶栏返回与操作按钮 | `CouiBackButton`、`CouiTopAppBarIconButton`、`CouiTopAppBarTextButton`、`TopAppBarButtonGroup` | `MiuixBackButton`、`IconButton`、`TextButton` |
| 分组卡片与标题 | `com.itos.coui.layout.Group(title = ...)` | `SmallTitle` + `Card` |
| 懒加载连续分组条目 | `EtaPreferenceGroupItem` → `CouiGroupItem` | `EtaPreferenceGroupItem` |
| 跳转行 / 信息行 | `CouiPreference`（右侧箭头使用 `CouiIcons.Next`，`12.dp × 24.dp`） | `ArrowPreference` / `BasicComponent` |
| 开关行 | `CouiSwitchPreference`（整行 `Role.Switch` + `CouiHapticFeedback.Switch`） | `SwitchPreference` |
| 下拉 / 弹窗选择行 | `CouiDropdownPreference` / `CouiPopupPreference`（`rememberCouiMenuAnchor` + `Modifier.couiMenuAnchor` + `CouiIcons.Popup` `14.dp × 24.dp` + `Menu`） | `OverlayDropdownPreference` / `WindowSpinnerPreference` / `OverlaySpinnerPreference` |
| 复选 / 单选行 | `CouiCheckboxPreference` / `CouiSelectPreference` | `CheckboxPreference` / `Checkbox` |
| 滑块行 | `CouiSliderPreference` | `SliderPreference` |
| 分组内分割线 | `Divider()`（无前导图标）/ `CouiIconDivider()`（有前导图标） | Miuix 列表间距 |
| 搜索栏 | `CouiSearchBarBox`（COUI `SearchBar` + 局部 `CouiBackdrop`） | `SearchBar` + `InputField` |
| 分页 / 筛选标签 | `CouiChipTabs`（COUI `ChipTabRow`） | `TabRow` / `EtaChoiceChip` |
| 行内输入框 | `CouiTextField` | `TextField` |
| 页面胶囊按钮 | `CouiButton`（`CouiButtonRole`） | `TextButton` |
| 确认 / 提示 / 底部弹框 | `CouiConfirmDialog`、`CouiNoticeDialog`、`com.itos.coui.dialog.Dialog`（`CouiDialogPlacement.Bottom`） | `WindowDialog`、`OverlayDialog`、`OverlayBottomSheet` |
| 单行输入弹窗 | `CouiInputDialog`（`com.itos.coui.dialog.InputDialog`） | `WindowDialog` + `TextField` |
| 上下文 / 溢出菜单 | `com.itos.coui.menu.Menu` + `rememberCouiMenuAnchor` | `WindowListPopup` / `OverlayListPopup` / `OverlayIconDropdownMenu` |

## 分组、分割线与状态边界

无图标行不要提供空的图标插槽；对应分割线使用 `hasLeading = false`。条件行与其前置分割线置于同一可见分支，只在已有前一项时绘制。长列表用稳定 key 的独立 Lazy 条目和 `EtaPreferenceGroupItem`，不要为了卡片外观将模型或文件全集放进一个 Lazy 条目。

## 内容插槽与交互

`EtaPreference` 的尾随 lambda 是自定义正文；无需自定义正文时使用 `title` 和 `summary`。内部 `EtaPreferenceRow` 将自定义正文明确命名为 `titleContent`，尾随 lambda 保留给右侧 `endActions`，防止控件误占正文。

设置行不固定文本高度：摘要、长标题和大字体自然撑高。左右内容在一次测量中分配空间，右侧内容有宽度上限，正文保留可换行区域。底部滑块或附加操作使用 `bottomAction`；无点击回调的展示行不创建虚假的整行点击动作。

开关、复选、单选的状态和持久化由页面或 Store 持有。组件只绘制状态并转发事件；图标禁用态、行语义和子控件语义共同维护，避免读屏重复播报。不要在组件中拼 Entity、写偏好设置或启动业务任务。

## 按钮与弹层

`EtaTextButton` 复用 Miuix 按钮的点击反馈和状态颜色；主要、普通与破坏性操作通过现有 `ButtonDefaults` 配色区分。`MiuixDialogActions` 保留取消在左、确认在右及独立可用状态。

窗口层对话框与 Scaffold 内弹层分别保留各自的宿主机制。不要把 `EtaOverlayDialog` 放在缺少 Scaffold 的上下文中，也不要为了样式把窗口弹层替换成 Overlay。共享弹窗使用主题的卡片表面色，与页面背景及次要按钮底色区分，避免取消按钮融入弹窗。共享封装统一外观参数；确认逻辑、取消回调和长内容滚动仍由调用方负责。

默认浅色设置页使用中性背景、白色卡片和蓝色强调色；Monet 和深色配置沿用 App 的主题。设置页主题不替换聊天正文、终端内容和浏览器画布的专用样式。

### 设置页的分组、折叠与搜索

设置页按功能分组（模型与用量、对话与委派、聊天与界面、上下文与扩展、工具、系统接管、厂商入口、通用、权限、关于）。分组标题可点击折叠，折叠状态用 `rememberSaveable` 保存；搜索时一律展开，否则命中结果只剩一行标题、用户还得再点一次才能看到目标项。

折叠态与展开态必须使用同一视觉语言：`EtaCollapsibleSection` 在折叠时把标题装进与内容同款的卡片（`EtaPreferenceGroup`），展开时标题是卡片上方的分组标签。早期版本折叠后只留一行无背景灰字，整页看起来是一串悬空文字——这是被用户直接指出过的缺陷，改动时不要改回去。

搜索框由 `MiuixScaffoldPage` 的 `header` 参数渲染，固定在列表上方而不是列表的第一个 item。它原先是列表项，滑到下面的分组后会滚出屏幕，想改关键词还得滑回顶部。`stickyHeader` 在该页不生效（LazyColumn 带顶栏 contentPadding），所以走 `header` 这条路径；`header` 为空时布局与原来完全一致，只有传了它才会把内容切成「header + LazyColumn」两段。

`header` 的水平留白由 `MiuixScaffoldPage` 统一负责（`horizontalCutoutPadding` + `sidePadding` + `SidePadding`），调用方只管垂直间距。这不是顺手写死的：列表的左右留白来自 LazyColumn 的 `contentPadding(sidePadding)`，而 header 在 Column 里拿不到它——第一版只按 `sidePadding` 补，手机上它是 0，搜索框直接贴屏幕边、和下面卡片错开 16dp；宽屏下又会因为卡片自己的内边距叠不上而跑偏。两个错误各出现一次，所以这层内边距只留一处。

同一套 `header` 也用在**模型提供商列表**（它原本同样是列表第一项，23 个提供商往下一滑搜索框就没了）。新增带搜索框的这类页面直接复用 `header`，不要再用 `item { InputField(...) }`。

搜索匹配的是组内每个条目的叫法而不只是组标题（用户搜「委派」时目标项在「模型提供商」组里），关键词表集中在 `SETTINGS_*_KEYWORDS`，新增设置项时要同步补一行，否则那一项搜不到。匹配与展开逻辑是纯函数（`settingsSectionMatches` / `settingsSectionExpanded`），由 `SettingsSearchTest` 覆盖。

## 验证

`EtaPreferenceRenderingTest` 在真实 Compose 布局树中检查开关、单选与选择菜单的标题仍存在，以及有无图标时右侧插槽的位置。控件跨页复用时应继续验证禁用态、条件项、长摘要、大字体、窄屏、输入法及弹层关闭行为。
