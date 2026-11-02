# Agent Runtime

> **本文件不是正文，只是指针。**
>
> Runtime 的完整文档只有一份：[`docs/AGENT_RUNTIME.md`](docs/AGENT_RUNTIME.md)。

## 为什么改成指针

这里原本放着一份"与 `docs/` 保持同步"的副本，实际早已分叉：2026-11-02 核对时根目录这份 6,866 行、
`docs/` 那份 9,820 行，后续修改只落在 `docs/`。两份同名文件并存的结果是"按目录猜版本" ——
先读到旧结论的人会拿到与另一份互相矛盾的说法，而文件头还写着"两边是同一份内容"。

按项目既有约定（发布口径看 `docs/`），这里不再维护第二份；完整历史内容仍可从 git 历史取回
（`git log -- AGENT_RUNTIME.md`）。以后只改 `docs/AGENT_RUNTIME.md`。

## 同类情况（尚未处理，如实记录）

根目录还留着几份比 `docs/` 旧的快照，读之前请以 `docs/` 为准：

| 根目录 | 正式文档 |
| --- | --- |
| `TECHNICAL.md` | [`docs/TECHNICAL.md`](docs/TECHNICAL.md) |
| `TERMINAL_NATIVE.md` | [`docs/TERMINAL_NATIVE.md`](docs/TERMINAL_NATIVE.md) |
| `VOICE.md` | [`docs/VOICE.md`](docs/VOICE.md) |
| `ROOTLESS_SUPPORT.md` | [`docs/ROOTLESS_SUPPORT.md`](docs/ROOTLESS_SUPPORT.md) |
