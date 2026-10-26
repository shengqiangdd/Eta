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

## 三个固定坑

1. **`scroll_element` 不支持**（`GESTURE_UNSUPPORTED_ON_VIRTUAL_DISPLAY`）。
   滚动一律用坐标 `swipe`，从边缘空白起手，避免落在输入框上被吞。
2. **`type_text(submit=true)` 不可靠**：文本写进去了但没提交，界面不动。
   改为写入文本后点击发送按钮；发送后到消息列表里确认新消息真的出现。
3. **应用内跳页时任务可能被系统迁走**（本机 `DisplayInfo.canHostTasks=false`）。
   - 症状：`observe_screen` 报「该虚拟屏没有任务」「窗口未暴露」。
   - 处理：`virtual_display_list` 看 `task_count`，为 0 就 `virtual_display_launch(force=true)` 重拉；
     **不要**新建一块屏（丢进程状态、多等一次冷启动）。

## 坐标参考（1080×2400 @420dpi，Eta 聊天页实测）

- 内容区从 y≈137 开始（状态栏 0~137）。
- 底部输入栏工具行：y 2232~2358，中心 y≈2295。
- 上下文用量 (634,2294)、语音 (744,2294)、切换模型 (854,2294)、发送 (960,2295)。
- 换页面、换应用后这些都不成立 —— 重新取 bounds，别复用。

## 收尾

- 结束必须 `virtual_display_release`（`id=all` 清全部），否则屏与任务一直占资源。
- 释放会移除屏上任务：先确认里面没有需要保留的应用状态。
