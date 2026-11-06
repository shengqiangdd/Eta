# Eta 设置与管理组件

Eta 在现有 Miuix 上提供面向设置、管理列表和功能入口的统一组件。视觉采用 ColorOS 17 风格的分组、图标配色、间距与控件比例；主题、连续圆角、控件动画、系统触感和弹层生命周期继续由 Miuix 提供。组件不依赖特定 ROM，依赖版本以项目构建配置为准。

## 组件选择

| 场景 | 组件 |
| --- | --- |
| 标准列表页 | `MiuixScaffoldPage`，已接入 `EtaPreferenceTheme` |
| 自定义分页或页面 | `MiuixScaffold`，已接入相同主题与宽屏骨架 |
| 卡片容器 | `EtaCard` |
| 设置项分组、分组标题 | `EtaPreferenceGroup`、`EtaPreferenceGroupTitle` |
| 独立懒加载条目组成的卡片 | `EtaPreferenceGroupItem`，传入实际首尾位置 |
| 信息、状态或自定义正文行 | `EtaPreference` |
| 跳转行、开关行 | `EtaArrowPreference`、`EtaSwitchPreference` |
| 自定义行内开关 | `EtaSwitch`，复用同一控件尺寸与 Miuix 行为 |
| 单选、复选行 | `EtaRadioButtonPreference`、`EtaCheckboxPreference` |
| Scaffold 内选择菜单 | `EtaOverlayDropdownPreference` |
| 窗口层选择菜单 | `EtaWindowSpinnerPreference` |
| 需要完整 DropdownItem 的选择菜单 | `EtaDropdownPreference` |
| 图标、正文对齐分割线 | `EtaPreferenceIcon`、`EtaPreferenceDivider` |
| 图标、标题、摘要型功能入口 | `EtaFeatureCard` |
| 按钮、确认按钮行 | `EtaTextButton`、现有 `MiuixDialogActions` |
| 窗口层或 Scaffold 内对话框 | `EtaWindowDialog`、`EtaOverlayDialog` |

`EtaPreferenceDefaults`、`EtaCardDefaults` 和 `EtaControlDefaults` 是尺寸的事实源；`EtaPreferenceColors` 管理分类图标颜色。分类色由调用方按功能选择，品牌图标保留原色，错误及其他运行状态继续使用现有状态语义。

## 分组与边距

`EtaPreferenceGroup` 默认自带页面横向边距和底部组间距。显式传入 `modifier` 时，由调用方管理外部布局；不要再重复叠加一套默认边距。卡片内部设置行自行消费行内边距，表单块按自己的内容需求消费内边距。

```kotlin
EtaPreferenceGroupTitle("连接")
EtaPreferenceGroup {
    EtaArrowPreference(
        title = "服务提供商",
        startAction = {
            EtaPreferenceIcon(icon = providerIcon, tint = EtaPreferenceColors.Blue)
        },
        onClick = onOpenProvider,
    )
    EtaPreferenceDivider()
    EtaSwitchPreference(
        title = "启用连接",
        checked = enabled,
        onCheckedChange = onEnabledChange,
        startAction = {
            EtaPreferenceIcon(icon = connectionIcon, tint = EtaPreferenceColors.Blue)
        },
    )
}
```

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
