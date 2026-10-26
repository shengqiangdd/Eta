---
name: virtual-display-playbook
description: 在独立虚拟屏上操作 Android 应用时，用最少工具调用定位并点中控件。适用于：需要在虚拟屏上配置某应用、反复点不中控件、observe_screen 结果被落盘成文件、虚拟屏任务突然消失、虚拟屏上无法滚动。
---

# 虚拟屏操作手册

目标是**用最少的调用**把事做完。虚拟屏每一步都贵（无障碍全树遍历 + 结果可能落盘 + 再读文件）。

## 定位：用节点 bounds，不要目测截图

1. **坐标只信 `ui_nodes` 的 `bounds/center`**。
   虚拟屏截图在模型侧可能被缩放，按图目测的 y 会偏上百像素 —— 实测过：按截图点 y≈1990，真实控件在 y≈2295，全落空。
2. 截图只用于判断「界面长什么样 / 结果是否出现」，不用来量坐标。
3. **先看结果里有没有 `ui_nodes_shown` 字段** —— 它就是线格式版本的判据：
   - **有**（fix151 起的新格式）：节点是紧凑投影，`max_nodes=60` 实测约 8.6KB、**不落盘**，一次拿全；
     `max_nodes=120` 会被体积预算裁剪到约 75~80 个节点并给出 `ui_nodes_note`。
     这种情况下不要为了「避落盘」把 `max_nodes` 调小，直接看 `ui_nodes_shown` 判断拿到了多少。
   - **没有**（旧格式）：`max_nodes=60` 约 13.6KB、`120` 约 27~30KB，**必落盘**。
     先用 `max_nodes=40` 避免落盘；不够再用 `120` + 只读文件尾部（底部固定控件在末尾）。
4. 用 `tap_element(index, observation_id)` 时，index 必须来自最近一次观察；过期就重新观察，不要硬试。

## 动作：先用掉 after

- `tap` / `swipe` / `type_text` 的返回里带 `after`（新 observation_id + 精简节点）。
  先用它判断是否生效，不要紧接着再 `observe_screen`。
- `screen_changed=false` 表示动作没起作用：换目标或换方式，**不要原样重放**。

## 起手：一次调用就能开工

`virtual_display_launch` 在核验任务落在该屏之后，会**自动观察一次首屏**：
结果里的 `first_observation` 带 `observation_id` + `ui_nodes`（约 60 个节点、不落盘）。

- 拿到 `first_observation` 就**直接 `tap_element`**，不要再 `wait` 或补一次 `observe_screen`。
- 它同时把这个观察登记成「当前观察」，之后漏传 `display_id` 的动作也不会跑到主屏。
- 首屏若是加载动画（`ui_nodes` 为空或只有占位），再 `observe_screen(display_id=…)` 刷新一次即可。
- **更省一步**：`virtual_display_create` 直接带 `package_name`（可再带 `activity`、`force`），
  建屏＋启动＋首屏观察一次做完，返回里同样有 `first_observation`。
  启动失败时 `ok` 仍为 `true` —— 屏已经建好了，真实原因在 `launch_error` 里，别以为屏没建成又去 create。

## 三个固定坑

1. **节点不接受点击时不要自己换算坐标**。
   虚拟屏上无障碍手势只作用于默认屏，所以 `tap_element` / `long_press_element` 在
   「节点本身不可点」时曾直接报 `GESTURE_UNSUPPORTED_ON_VIRTUAL_DISPLAY`；现在工具层会
   用该节点中心自动走 `input -d` 兜底，结果里带 `method=ROOT_COORDINATE_FALLBACK`。
   看到它说明**已经点下去了**，先看 `after`，不要再补一次 `tap_area`（会重复点）。
   真正失败（过期 / 无障碍断开 / 结果未知）仍会报错 —— 那时重新观察，别硬试。
   还有一条**安全拦截**：若这个节点的中心点落在**更小的**节点上（典型是容器：根
   `ScrollView` 的中心正好压在快捷开关行），盲点等于随机拨开关 —— 实测把飞行模式打开过，
   设备当场断网。此时返回 `code=COORDINATE_FALLBACK_UNSAFE_TARGET` 并带
   `blocked_target_index`：**手势没有发出**，下一步直接点那个 index（或它的文本子节点），
   不要换个坐标硬点。
2. **`type_text(submit=true)` 仍不可靠**：文本写进去了但没提交，界面不动。
   改为写入文本后点击发送按钮；发送后到消息列表里确认新消息真的出现。
3. **应用内跳页时任务可能被系统迁走**（本机 `DisplayInfo.canHostTasks=false`）。
   - 症状：`observe_screen` 报「该虚拟屏没有任务」「窗口未暴露」。
   - 处理：`virtual_display_list` 看 `task_count`，为 0 就 `virtual_display_launch(force=true)` 重拉；
     **不要**新建一块屏（丢进程状态、多等一次冷启动）。

## 滚动

- `scroll_element(节点)` 优先用节点的滚动动作；节点只支持手势时，工具层会自动
  退回 `input -d` 坐标滚动（`method=ROOT_SCROLL_FALLBACK`），**不需要**自己换算 swipe。
- 只有它仍报错时才手动 `swipe`：从边缘空白起手，避免落在输入框上被吞。
- 返回 `code=ACTION_OUTCOME_UNKNOWN`（带 `fallback_from=GESTURE_UNSUPPORTED_ON_VIRTUAL_DISPLAY`）
  时：**滑动已经发出去了**，只是没能确认位移（常见于紧跟一次点击、页面还在切换）。
  此时应**先 `observe_screen` 看现在滚到哪**，而不是再 `scroll` 一次 —— 再滚一次就是双倍位移。
- 另一种：`ok=true` 但 `verified_by=anchor_motion_partial`。内容确实移位了，只是幅度不一致
  （例如头部正在展开/收起），所以 `direction_confirmed=false`。这说明**已经滚动了**；
  需要确认方向时再 `observe_screen` 一次，同样别直接重滚。

## 坐标参考（1080×2400 @420dpi，Eta 聊天页实测）

- 内容区从 y≈137 开始（状态栏 0~137）。
- 底部输入栏工具行：y 2232~2358，中心 y≈2295。
- 上下文用量 (634,2294)、语音 (744,2294)、切换模型 (854,2294)、发送 (960,2295)。
- 换页面、换应用后这些都不成立 —— 重新取 bounds，别复用。

## 收尾

- 结束必须 `virtual_display_release`（`id=all` 清全部），否则屏与任务一直占资源。
- 释放会移除屏上任务：先确认里面没有需要保留的应用状态。
