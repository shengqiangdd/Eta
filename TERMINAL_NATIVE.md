# 终端原生组件

> **本文件不是正文，只是指针。**
>
> 完整文档只有一份：[`docs/TERMINAL_NATIVE.md`](docs/TERMINAL_NATIVE.md)。

## 为什么改成指针

这里原本放着一份标注「与 `docs/` 保持同步」的副本，实际早已分叉：
原生库的 ABI 列表两处不一致，根目录那份写的是更早的构建矩阵。

ABI 列表直接决定「这个 APK 能不能在我的设备上跑」，写错的代价是用户装了打不开、
或者以为 32 位设备也能用。这类信息不能有两份。

完整历史内容仍可从 git 历史取回（`git log -- TERMINAL_NATIVE.md`）。

以后只改 `docs/TERMINAL_NATIVE.md`。
