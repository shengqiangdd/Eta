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

## 同类情况

根目录的其它文档现在都是**指针**，正文只在 `docs/` 下：

| 根目录 | 正式文档 | 说明 |
| --- | --- | --- |
| `TECHNICAL.md` | [`docs/TECHNICAL.md`](docs/TECHNICAL.md) | 两份内容一致，由 `tools/doc_mirror.py` 同步 |
| `TERMINAL_NATIVE.md` | [`docs/TERMINAL_NATIVE.md`](docs/TERMINAL_NATIVE.md) | 根目录版只剩指针 |
| `VOICE.md` | [`docs/VOICE.md`](docs/VOICE.md) | 根目录版只剩指针 |
| `ROOTLESS_SUPPORT.md` | [`docs/ROOTLESS_SUPPORT.md`](docs/ROOTLESS_SUPPORT.md) | 根目录版只剩指针 |
| `UI_COMPONENTS.md` | [`docs/UI_COMPONENTS.md`](docs/UI_COMPONENTS.md) | 根目录版只剩指针 |
| `THIRD_PARTY_NOTICES.md` | [`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md) | 根目录版只剩指针 |

改成指针的原因都一样：这些副本都标注「与 `docs/` 保持同步」，实际早已分叉，
而分叉处往往是结论性的内容（默认语音引擎、ABI 列表、第三方许可条目）。
两份同名文件并存时，读的人只能按目录猜哪份是准的。

`TECHNICAL.md` 是唯一仍然两份都保留正文的：它的镜像由工具维护，改一处会自动同步。
其余几份没有这个机制，所以改为指针、只留一份正文。
