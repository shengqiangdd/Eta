#!/usr/bin/env python3
"""UI 动作接线检查：`sealed interface XxxAction` 里的每个动作都必须真的有人发出来。

为什么需要它：动作**定义**得再多也不会报错 —— `when` 只要穷尽就行。于是「定义了、
在 `when` 里写了分支、但界面上没有任何地方发出」这种半成品可以无限期存活，而且
看起来完全健康（编译过、单测绿、界面不崩）。§32.39.16 一次性清掉 8 个这种动作，
其中一个（`RetryConversationLoad`）的分支甚至是 `-> Unit`：它既是死动作，又是空承诺。

判据（避免把 `when` 分支误当成发出点）：
- `is Iface.Member` / `Iface.Member ->` / `Iface.Member, ... ->` 都算**分支声明**；
- 注释行（`//`、`*`、`/*` 开头）不算；
- 其余出现即算发出。

不做的事：不判断「发出了但处理为空」（`-> Unit`）。那要看具体语义 —— 有意的上报类
动作就该是空的，交给 review，不交给门禁。

用法：
  python3 tools/ui_action_wiring.py

退出码：0 = 全部接线；1 = 存在未接线动作；2 = 找不到/解析不了动作定义。
"""

import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_ROOT = os.path.join(REPO, "app", "src", "main", "kotlin")
ACTIONS = os.path.join(
    SRC_ROOT, "io", "github", "mangi", "eta", "ui", "model", "AgentUiActions.kt"
)

# 允许「只定义、不发」的动作：留空表示一个都不许。
# 真需要加时，必须在这行写清**为什么**它只能被定义（例如纯协议占位、反射派发），
# 否则下一个读代码的人分不清「有意」和「忘了接线」。
ALLOWLIST: set[tuple[str, str]] = set()

MEMBER_DECL = re.compile(r"^\s*(?:data\s+object|data\s+class|object|class)\s+(\w+)")
IFACE_DECL = re.compile(r"^\s*sealed\s+interface\s+(\w+)")
TOKEN = re.compile(r"\b([A-Za-z_]\w*)\.([A-Za-z_]\w*)\b")


def read_actions():
    if not os.path.isfile(ACTIONS):
        print(f"✗ 找不到动作定义：{ACTIONS}")
        sys.exit(2)
    ifaces = {}
    cur = None
    for line in open(ACTIONS, encoding="utf-8"):
        m = IFACE_DECL.match(line)
        if m:
            cur = m.group(1)
            ifaces[cur] = []
            continue
        if cur is None:
            continue
        m = MEMBER_DECL.match(line)
        if m:
            ifaces[cur].append(m.group(1))
        if line.startswith("}"):
            cur = None
    if not ifaces:
        print(f"✗ 从 {ACTIONS} 里解析不出任何 sealed interface")
        sys.exit(2)
    return ifaces


def is_branch_declaration(line, start, end):
    before = line[:start]
    after = line[end:]
    if re.search(r"\bis\s*$", before):
        return True
    if re.match(r"\s*->", after):
        return True
    # 多分支：`A, B -> x`
    if re.match(r"\s*,", after) and "->" in after:
        return True
    return False


def main():
    ifaces = read_actions()
    members = {(i, m) for i, ms in ifaces.items() for m in ms}

    emitted = set()
    for dirpath, _, filenames in os.walk(SRC_ROOT):
        for name in filenames:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            if os.path.abspath(path) == os.path.abspath(ACTIONS):
                continue
            for line in open(path, encoding="utf-8", errors="replace"):
                stripped = line.lstrip()
                if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
                    continue
                for m in TOKEN.finditer(line):
                    key = (m.group(1), m.group(2))
                    if key not in members:
                        continue
                    if is_branch_declaration(line, m.start(), m.end()):
                        continue
                    emitted.add(key)

    total = len(members)
    unused = sorted(k for k in members - emitted if k not in ALLOWLIST)
    print(f"UI 动作接线检查：{len(ifaces)} 个动作接口 / {total} 个动作")

    if not unused:
        print("  ✓ 每个动作都有发出点")
        return 0

    for iface, member in unused:
        print(f"  ✗ {iface}.{member} 没有任何发出点（定义了却没人用）")
    print(f"\n未接线 {len(unused)} 个。删掉它，或在界面上真的接上 —— 不要留着当摆设。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
