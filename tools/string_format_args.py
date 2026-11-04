#!/usr/bin/env python3
"""占位符漏参检查：`stringResource(R.string.x)` 少传 formatArgs 时，界面会漏出 `%1$d`。

为什么需要它：`strings.xml` 里写 `其他会话执行中 %1$d`，代码里若写成
`stringResource(R.string.background_tasks_foreign_running)`，**编译通过、lint 通过、
单测通过**，只有真机截图上能看到标题变成「其他会话执行中 %1$d 1」。
2026-11-04 就是这样漏出来的：界面把模板字符串直接当标签拼了数字。

口径：
- 基准是 `values/strings.xml`（默认语言）里**带 `%N$d` / `%N$s` 占位符**的 string；
- 扫 `app/src/main/kotlin` 里 `stringResource(R.string.X)` 这种**单参数**调用；
- 命中且不在白名单 → 报红。白名单只放「取了模板再自己 format」这种合法用法。

注意：不能只扫 `strings.xml`，其他语言的同名资源可能不带占位符，用默认语言判定最准。

用法：
  python3 tools/string_format_args.py            检查
  python3 tools/string_format_args.py --list     只打印命中

退出码：0 = 干净；1 = 有漏参；2 = 读不到资源。
"""

import os
import re
import sys
import xml.etree.ElementTree as ET

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_STRINGS = os.path.join(REPO, "app", "src", "main", "res", "values", "strings.xml")
KOTLIN_DIR = os.path.join(REPO, "app", "src", "main", "kotlin")

# 允许：调用方拿到模板后自己 String.format（续跑提示语就是这么用的）。
ALLOW = {
    "background_tasks_resume_prompt",
}

PLACEHOLDER = re.compile(r"%\d+\$[ds]")
# 只匹配**没有**第二个参数的单参数调用；跨行调用也要覆盖，用 DOTALL 限制在同一次调用内。
SINGLE_ARG = re.compile(r"stringResource\(\s*R\.string\.(\w+)\s*\)")


def load_placeholder_keys():
    if not os.path.isfile(DEFAULT_STRINGS):
        print(f"读不到 {DEFAULT_STRINGS}")
        sys.exit(2)
    root = ET.parse(DEFAULT_STRINGS).getroot()
    keys = set()
    for child in root:
        if child.tag != "string":
            continue
        if PLACEHOLDER.search("".join(child.itertext())):
            keys.add(child.get("name"))
    return keys


def main():
    keys = load_placeholder_keys()
    hits = []
    for dirpath, _dirnames, filenames in os.walk(KOTLIN_DIR):
        for name in filenames:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as handle:
                text = handle.read()
            for match in SINGLE_ARG.finditer(text):
                key = match.group(1)
                if key in keys and key not in ALLOW:
                    line = text.count("\n", 0, match.start()) + 1
                    rel = os.path.relpath(path, REPO)
                    hits.append((rel, line, key))

    if "--list" in sys.argv or hits:
        print(f"占位符资源 {len(keys)} 条｜单参数调用命中 {len(hits)} 处")
        for rel, line, key in hits:
            print(f"  ✗ {rel}:{line}  {key} 需要 formatArgs，否则界面会漏出 %1$d")
    if not hits:
        print(f"  ✓ {len(keys)} 条带占位符的资源都传了参数（白名单 {len(ALLOW)} 条）")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
