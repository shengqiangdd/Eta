# Rootless 支持

> **本文件不是正文，只是指针。**
>
> 完整文档只有一份：[`docs/ROOTLESS_SUPPORT.md`](docs/ROOTLESS_SUPPORT.md)。

## 为什么改成指针

这里原本放着一份标注「与 `docs/` 保持同步」的副本，实际早已分叉。
两处结论不同，且根目录那份更旧：

- 根目录只写「支持 Android 13 及以上」；
- `docs/` 补了「ARM64（arm64-v8a）」这一必须条件——APK 只含 ARM64 原生库，
  不提供 32 位 ARM、x86 或 x86_64 构建；
- `docs/` 还多出后台虚拟屏执行与主屏移交的能力矩阵。

「支持 Android 13+」漏掉架构限制，会让 32 位设备用户以为能装。
完整历史内容仍可从 git 历史取回（`git log -- ROOTLESS_SUPPORT.md`）。

以后只改 `docs/ROOTLESS_SUPPORT.md`。
