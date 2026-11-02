#!/usr/bin/env python3
"""门禁的「测试子集分类」检查。

为什么需要：
`tools/gate.sh` 只跑 FAST_TEST_FILTERS 里的类（`--tests ...`），没进列表的测试类**一例都不会跑**，
而门禁照样全绿。这个坑在仓库里已经踩过六次（注释里逐条记着），共同表现都是「用例数不变」。
靠"记得加"是不可靠的：写代码的人以为门禁会跑，看绿的人以为覆盖到了。

所以把「哪些类不跑」变成一份显式清单：

* 能本机跑 → 加进 `tools/gate.sh` 的 FAST_TEST_FILTERS；
* 本机跑不了（Robolectric native / 需要真机）或暂不纳入 → 在
  `tools/test_subset_baseline.txt` 里占一行，**写清楚原因**。

检查规则（棘轮，只许收紧）：

1. 新增的测试类既不在过滤列表、也不在基线里 → 红（必须显式分类）；
2. 基线里的类已经不存在、或已经被过滤列表覆盖 → 红（删掉那行，别让清单腐化）；
3. 过滤列表里写死的类名已经不存在 → 红（重命名后忘了改，等于静默少跑）。

用法：
    python3 tools/check_test_subset.py            # 检查（门禁用这个）
    python3 tools/check_test_subset.py --write-baseline   # 重新生成基线（维护用）
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GATE = ROOT / "tools" / "gate.sh"
BASELINE = ROOT / "tools" / "test_subset_baseline.txt"
TEST_ROOT = ROOT / "app" / "src" / "test" / "kotlin"

PACKAGE_RE = re.compile(r"^package\s+([\w.]+)", re.MULTILINE)
FILTERS_RE = re.compile(r"FAST_TEST_FILTERS='(.*?)'\n", re.DOTALL)
ENTRY_RE = re.compile(r"--tests\s+(\S+)")


def read_filters() -> list[str]:
    text = GATE.read_text(encoding="utf-8")
    match = FILTERS_RE.search(text)
    if not match:
        raise SystemExit("找不到 FAST_TEST_FILTERS 定义（tools/gate.sh 改过结构？）")
    return [e for e in ENTRY_RE.findall(match.group(1)) if not e.startswith("-")]


def read_classes() -> dict[str, Path]:
    classes: dict[str, Path] = {}
    for path in sorted(TEST_ROOT.rglob("*.kt")):
        text = path.read_text(encoding="utf-8", errors="ignore")
        match = PACKAGE_RE.search(text)
        package = match.group(1) if match else ""
        name = path.stem
        classes[f"{package}.{name}" if package else name] = path
    return classes


def covers(entry: str, class_name: str) -> bool:
    if entry.endswith(".*"):
        return class_name.startswith(entry[:-2] + ".")
    return class_name == entry


def is_covered(class_name: str, filters: list[str]) -> bool:
    return any(covers(f, class_name) for f in filters)


def read_baseline() -> list[str]:
    if not BASELINE.exists():
        return []
    result: list[str] = []
    for raw in BASELINE.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if line:
            result.append(line)
    return result


BASELINE_HEADER = """\
# 未纳入门禁快速子集的测试类（自动生成，只许减少）。
#
# 为什么有这份清单：门禁只跑 tools/gate.sh 的 FAST_TEST_FILTERS，
# 没进列表的测试类一例都不会跑，而门禁照样全绿 —— 这个坑已经踩过六次。
#
# 新增测试类时的正确做法：
#   1. 能在本机 JVM 跑的 → 加进 tools/gate.sh 的 FAST_TEST_FILTERS（推荐）；
#   2. 本机跑不了（Robolectric native runtime / 需要真机）或本轮暂不纳入
#      → 在下面加一行，**在行尾用 # 写明原因**。
#
# 这份清单只许减少：类被删除或已被过滤列表覆盖时，对应行必须删掉（脚本会报红）。
"""


def write_baseline(class_names: list[str], existing: dict[str, str]) -> None:
    lines = [BASELINE_HEADER]
    for name in class_names:
        reason = existing.get(name)
        lines.append(f"{name}  # {reason}" if reason else f"{name}")
    BASELINE.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"已写入 {BASELINE.relative_to(ROOT)}：{len(class_names)} 条")


def parse_existing_reasons() -> dict[str, str]:
    reasons: dict[str, str] = {}
    if not BASELINE.exists():
        return reasons
    for raw in BASELINE.read_text(encoding="utf-8").splitlines():
        if "#" in raw:
            name, reason = raw.split("#", 1)
            if name.strip():
                reasons[name.strip()] = reason.strip()
    return reasons


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write-baseline", action="store_true")
    args = parser.parse_args()

    filters = read_filters()
    classes = read_classes()
    uncovered = sorted(c for c in classes if not is_covered(c, filters))

    if args.write_baseline:
        write_baseline(uncovered, parse_existing_reasons())
        return 0

    baseline = read_baseline()
    baseline_set = set(baseline)
    uncovered_set = set(uncovered)

    new_classes = sorted(uncovered_set - baseline_set)
    stale = sorted(baseline_set - uncovered_set)
    dead_filters = sorted(
        f for f in filters if not f.endswith(".*") and f not in classes
    )

    print(
        f"测试子集检查：{len(classes)} 个测试类，快速子集覆盖 "
        f"{len(classes) - len(uncovered)}，基线未覆盖 {len(baseline)}"
    )

    failed = False
    if new_classes:
        failed = True
        print(f"  ✗ 新增了 {len(new_classes)} 个测试类，既不在 FAST_TEST_FILTERS 也不在基线里：")
        for name in new_classes[:20]:
            print(f"      {name}")
        if len(new_classes) > 20:
            print(f"      ……另有 {len(new_classes) - 20} 个")
        print("    能本机跑就加进 tools/gate.sh；跑不了才在 tools/test_subset_baseline.txt 加一行并写原因。")
    if stale:
        failed = True
        print(f"  ✗ 基线里有 {len(stale)} 条已经失效（类被删/改名，或已被过滤列表覆盖）：")
        for name in stale[:20]:
            print(f"      {name}")
        print("    删掉这些行（棘轮只许收紧）。")
    if dead_filters:
        failed = True
        print(f"  ✗ FAST_TEST_FILTERS 里有 {len(dead_filters)} 个类名已经不存在：")
        for name in dead_filters[:20]:
            print(f"      {name}")
        print("    改名/删除测试类时同步更新过滤列表，否则等于静默少跑。")

    if failed:
        return 1
    print("  ✓ 每个测试类都有归属（快速子集或基线），没有静默不跑的")
    return 0


if __name__ == "__main__":
    sys.exit(main())
