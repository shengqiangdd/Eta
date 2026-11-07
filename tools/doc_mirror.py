#!/usr/bin/env python3
"""检查根目录与 docs/ 下的同名文档是否一致。

为什么需要它：仓库里 TECHNICAL.md 和 docs/TECHNICAL.md 是同一份内容的两个副本，
文件开头自己写着「修改后请同步另一处 —— 曾经分叉过一次」。
靠人记这句话不可靠：这次我（Agent）改完 docs/TECHNICAL.md 就走了，
根目录那份落后 33 行，直到下一次读文档时才发现。

分叉不会让编译、lint、单测里任何一项变红 —— 它只是让两份文档慢慢说不同的话，
而读的人无法判断哪份是真的。所以放在「与 STAGES 无关、始终执行」的检查里，
代价是一次 read + 比较，秒级。

只检查确实存在的成对文件；某一份不存在时跳过（不虚构要求）。
"""

from __future__ import annotations

import sys
from pathlib import Path

# (根目录副本, docs/ 副本)
PAIRS = [
    ("TECHNICAL.md", "docs/TECHNICAL.md"),
]


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    failures: list[str] = []

    for top_name, doc_name in PAIRS:
        top = root / top_name
        doc = root / doc_name
        # 两份都在才比较成对关系；缺一份是另一种问题，不在这里判。
        if not top.is_file() or not doc.is_file():
            print(f"跳过 {top_name} / {doc_name}：不是完整的一对")
            continue

        top_lines = top.read_text(encoding="utf-8").splitlines()
        doc_lines = doc.read_text(encoding="utf-8").splitlines()

        if top_lines == doc_lines:
            print(f"一致 {top_name} / {doc_name}（{len(top_lines)} 行）")
            continue

        # 找到第一个分歧行，给出可操作的定位。
        limit = min(len(top_lines), len(doc_lines))
        index = next(
            (i for i in range(limit) if top_lines[i] != doc_lines[i]),
            limit,
        )
        failures.append(
            f"{top_name} 与 {doc_name} 内容不一致：\n"
            f"  第一处分歧在第 {index + 1} 行\n"
            f"    {top_name}: {len(top_lines)} 行\n"
            f"    {doc_name}: {len(doc_lines)} 行\n"
            f"  根目录那份的第 {index + 1} 行：{_preview(top_lines, index)}\n"
            f"  docs 那份的第 {index + 1} 行：{_preview(doc_lines, index)}\n"
            f"  修法：以较新的那份为准，整份复制过去（cp 后重跑本检查）。"
        )

    if failures:
        for message in failures:
            print(message, file=sys.stderr)
        return 1
    return 0


def _preview(lines: list[str], index: int) -> str:
    """取分歧处的行做预览，过长时截断，避免刷屏。"""
    if index >= len(lines):
        return "（文件已结束）"
    text = lines[index].strip()
    if not text:
        return "（空行）"
    return text if len(text) <= 100 else text[:100] + "…"


if __name__ == "__main__":
    sys.exit(main())
