#!/usr/bin/env python3
"""检查 Kotlin 源码里「import 了但没用到」的符号。

为什么需要它：合并上游时最典型的漏 merge 特征就是「import 还在，调用被删了」。
2026-11 合并 3.4.2 时，ChatImagePreviewHost 的 import 留在 AgentAppShell.kt 里，
但包裹整个界面的那一层被去掉了 —— 于是图片点开全屏这个功能整条链路都没了，
而编译、单测、lint、静态检查全绿。编译器对未使用 import 只给警告，
Kotlin 默认不把警告当错误，所以它能一路活到用户手里。

检测方式：对每个 import 取最后一段名字，在去掉 import 行后的正文里找独立标识符。
只做保守判断 —— 宁可漏报，不要误报：

- 通配符 import 跳过
- 名字在字符串/注释里出现也算「用到」（避免误报）
- operator / 中缀函数的 import 可能只以符号形式出现，这类名字太短，跳过
- 常见平台类型（String/Int 等）不在 import 里，无需处理

用法：
  python3 tools/unused_imports.py            检查，有未使用则退出码 1
  python3 tools/unused_imports.py --list     只打印清单
  python3 tools/unused_imports.py --write-baseline  把当前清单写入基线
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC_DIRS = [ROOT / "app/src/main/kotlin", ROOT / "app/src/test/kotlin"]
BASELINE = ROOT / "tools/unused_imports_baseline.txt"

IMPORT_RE = re.compile(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$")

# 这些名字常以符号或隐式方式使用，静态判断容易误报，直接放过。
SKIP_NAMES = {
    "getValue", "setValue", "provideDelegate",
    "invoke", "plus", "minus", "times", "div", "rem", "unaryMinus", "not",
    "contains", "get", "set", "iterator", "hasNext", "next", "compareTo",
    "plusAssign", "minusAssign", "timesAssign", "divAssign", "remAssign",
    "component1", "component2", "component3", "component4", "component5",
}


def analyse(path: pathlib.Path) -> list[tuple[int, str, str]]:
    """返回 [(行号, 原始 import 行, 名字)]，都是看起来没用到的。"""
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return []

    lines = text.split("\n")
    imports: list[tuple[int, str, str]] = []
    for idx, line in enumerate(lines):
        stripped = line.strip()
        if not stripped.startswith("import "):
            continue
        m = IMPORT_RE.match(stripped)
        if not m:
            continue
        fqcn, alias = m.group(1), m.group(2)
        if fqcn.endswith("*"):
            continue
        name = alias or fqcn.rsplit(".", 1)[-1]
        if name in SKIP_NAMES or len(name) < 3:
            continue
        imports.append((idx + 1, stripped, name))

    if not imports:
        return []

    body = "\n".join(l for l in lines if not l.strip().startswith("import "))
    unused = []
    for lineno, raw, name in imports:
        # 不能用 (?<![\w.]) ：扩展函数（asStateFlow / withLock / toUri）调用时
        # 一律写成 `.name`，把前导点排除掉会把它们全误报成未使用。
        # 所以只要求后边不是标识符字符，前边不贴另一个标识符的后半段。
        if not re.search(r"(?<![\w])[\w]*" + re.escape(name) + r"(?![\w])", body):
            unused.append((lineno, raw, name))
    return unused


def collect() -> dict[str, list[tuple[int, str, str]]]:
    found: dict[str, list[tuple[int, str, str]]] = {}
    for root in SRC_DIRS:
        if not root.exists():
            continue
        for path in sorted(root.rglob("*.kt")):
            unused = analyse(path)
            if unused:
                found[str(path.relative_to(ROOT))] = unused
    return found


def read_baseline() -> set[str]:
    if not BASELINE.exists():
        return set()
    out = set()
    for line in BASELINE.read_text(encoding="utf-8").split("\n"):
        line = line.split("#", 1)[0].strip()
        if line:
            out.add(line)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--write-baseline", action="store_true")
    args = ap.parse_args()

    found = collect()
    total = sum(len(v) for v in found.values())

    if args.write_baseline:
        rows = [f"{path}:{lineno}:{name}" for path, items in found.items() for lineno, _, name in items]
        BASELINE.write_text(
            "# 未使用 import 的已知存量。只许减少：修掉一条就删一行。\n"
            + "\n".join(sorted(rows))
            + "\n",
            encoding="utf-8",
        )
        print(f"已写入基线：{len(rows)} 条 -> {BASELINE.relative_to(ROOT)}")
        return 0

    baseline = read_baseline()
    new = []
    for path, items in found.items():
        for lineno, raw, name in items:
            if f"{path}:{lineno}:{name}" not in baseline:
                new.append((path, lineno, raw))

    if args.list or new:
        for path, lineno, raw in new:
            print(f"{path}:{lineno}: {raw}")
    if new:
        print(f"\n未使用 import 检查：{len(new)} 条新增（基线内 {total - len(new)} 条）")
        print("提示：未使用 import 往往是「调用被删、import 忘了删」的漏 merge 特征，")
        print("      先确认对应功能是否还在，再决定删 import 还是补回调用。")
        return 1

    print(f"未使用 import 检查：无新增（基线内 {total} 条）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
