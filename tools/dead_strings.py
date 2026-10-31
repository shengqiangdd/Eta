#!/usr/bin/env python3
"""死文案检查：`strings.xml` 里定义了、但全仓没人引用的 key（string 与 plurals）。

为什么需要它：删掉一段代码，引用就跟着消失，而 `strings.xml` 里的条目还在。**编译不会报、
单测不会红**，要等到下一次 `STAGES=lint`，`UnusedResources` 多出几条，`LintBaselineFixed`
再把整道门禁弄红 —— 那时已经很难想起是哪次改动造成的（§32.41 就踩过一次）。

这份脚本把「现在有多少条死文案」变成可比的数字：基线文件 `tools/dead_strings_allow.txt`
记录已知技术债，**只许减少**。新增一条就红，逼你在同一次改动里处理掉它。

口径（缺一不可）：
- 扫 `app/src/main`、`app/src/test`、`app/src/androidTest`、`assets` 里的 Kotlin / Java / XML / 文本；
- 认 `R.string.x` / `@string/x`，以及 `R.plurals.x` / `@plurals/x` 两种资源类型；
- **动态取名直接报红**（`getIdentifier` 之类），因为一旦有它，本脚本的「没人引用」结论就不成立。
- **基准取 `values/*.xml`（默认语言）**：`strings.xml` 之外还有 `root_capabilities.xml`、
  `execution_service.xml` 等，只扫 `strings.xml` 会漏（§32.44 实测：158 + 6 + 8 + 5 + 3 条）。
  实测交叉验证：全量 2152 条定义中，死项与 lint 的 `UnusedResources`（202 条 `R.string` +
  2 条 `R.plurals`）逐条对齐。`values-night` / `values-v*` 这类限定目录不参与基准 —— 它们
  是同一资源的另一份取值，不是新键。
  基线只许减少，不要往这里加。
- 基线条目带类型前缀（`string:name` / `plurals:name`），避免两类资源同名时互相「救活」。

用法：
  python3 tools/dead_strings.py            检查（与基线比对）
  python3 tools/dead_strings.py --list     只打印当前死文案
  python3 tools/dead_strings.py --update   把当前死文案写成新基线（会提示 diff 数量）

退出码：0 = 与基线一致或更少；1 = 出现基线外的新死文案 / 动态取名 / 基线过期未更新；2 = 读不到文件。
"""

import os
import re
import sys
import xml.etree.ElementTree as ET

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(REPO, "app", "src")
BASE_DIR = os.path.join(APP, "main", "res", "values")
ALLOW = os.path.join(REPO, "tools", "dead_strings_allow.txt")

SCAN_DIRS = [
    os.path.join(APP, "main"),
    os.path.join(APP, "test"),
    os.path.join(APP, "androidTest"),
]
TEXT_EXT = {".kt", ".java", ".xml", ".json", ".js", ".html", ".txt", ".md", ".pro"}

KT_REF = re.compile(r"\bR\.(string|plurals)\.(\w+)")
XML_REF = re.compile(r"@(string|plurals)/(\w+)")
# 一旦出现动态取名，「没有 R.string.x」就不能证明无人使用。
# 只算「取资源且不是 android 命名空间」：`getIdentifier("config_nightDisplayAvailable", "bool", "android")`
# 是读系统开关，与本项目 strings.xml 无关（§32.41 实测：全仓唯一一处就是它）。
DYNAMIC = re.compile(r"getIdentifier\s*\(\s*[^,]+,\s*\"(\w+)\"\s*,\s*\"([^\"]+)\"\s*\)")


def fail(msg, code=2):
    print(msg)
    sys.exit(code)


def load_names():
    if not os.path.isdir(BASE_DIR):
        fail(f"✗ 找不到基准语言资源目录：{BASE_DIR}")
    names = set()
    for fname in sorted(os.listdir(BASE_DIR)):
        if not fname.endswith(".xml"):
            continue
        root = ET.parse(os.path.join(BASE_DIR, fname)).getroot()
        for e in root:
            name = e.get("name")
            if not name:
                continue
            if e.tag == "string":
                names.add(("string", name))
            elif e.tag == "plurals":
                names.add(("plurals", name))
    if not names:
        fail(f"✗ 基准语言资源目录 {BASE_DIR} 里没解析出任何 name")
    return names


def collect():
    used = set()
    dynamic = []
    scanned = 0
    for base in SCAN_DIRS:
        for dirpath, dirnames, filenames in os.walk(base):
            for f in filenames:
                if os.path.splitext(f)[1].lower() not in TEXT_EXT:
                    continue
                path = os.path.join(dirpath, f)
                try:
                    text = open(path, encoding="utf-8", errors="replace").read()
                except OSError:
                    continue
                scanned += 1
                ext = os.path.splitext(f)[1].lower()
                if ext in (".kt", ".java"):
                    used.update(KT_REF.findall(text))
                    for m in DYNAMIC.finditer(text):
                        kind, pkg = m.group(1), m.group(2)
                        if kind not in ("string", "plurals") or pkg == "android":
                            continue
                        line = text[:m.start()].count("\n") + 1
                        dynamic.append((os.path.relpath(path, REPO), line))
                elif ext == ".xml":
                    used.update(XML_REF.findall(text))
    return used, dynamic, scanned


def read_allow():
    if not os.path.isfile(ALLOW):
        return None
    out = set()
    for line in open(ALLOW, encoding="utf-8"):
        s = line.strip()
        if s and not s.startswith("#"):
            out.add(s)
    return out


def write_allow(names):
    header = [
        "# 已知死文案（strings.xml 里定义了、全仓没人引用）。",
        "# 条目格式：string:<name> 或 plurals:<name>，由 tools/dead_strings.py --update 生成。",
        "# 只许减少 —— 新增一条，门禁就红。处理办法：三语一起删掉，或真的把功能接上。",
        "",
    ]
    with open(ALLOW, "w", encoding="utf-8") as f:
        f.write("\n".join(header))
        if names:
            f.write("\n".join(sorted(names)))
            f.write("\n")


def label(kind_name):
    return f"{kind_name[0]}:{kind_name[1]}"


def main():
    names = load_names()
    used, dynamic, scanned = collect()
    dead = sorted(label((kind, n)) for kind, n in names if (kind, n) not in used)
    typed = {k: sum(1 for d in dead if d.startswith(k + ":")) for k in ("string", "plurals")}

    mode = sys.argv[1] if len(sys.argv) > 1 else ""

    if mode == "--list":
        for d in dead:
            print(d)
        return 0

    if mode == "--update":
        old = read_allow() or set()
        write_allow(dead)
        print(f"✓ 已写入基线：{len(dead)} 条（原 {len(old)} 条，新增 {len(set(dead) - old)}，移除 {len(old - set(dead))}）")
        return 0

    print(
        f"资源总数 {len(names)}（string {sum(1 for k, _ in names if k == 'string')} / "
        f"plurals {sum(1 for k, _ in names if k == 'plurals')}）｜ 扫描 {scanned} 个文件 ｜ "
        f"死文案 {len(dead)} 条（string {typed['string']} / plurals {typed['plurals']}）"
    )

    if dynamic:
        print(f"  ✗ 发现 {len(dynamic)} 处动态取资源名，本脚本的结论不成立：")
        for path, line in dynamic[:5]:
            print(f"     {path}:{line}")
        return 1

    allow = read_allow()
    if allow is None:
        print(f"  ✗ 没有基线文件 {os.path.relpath(ALLOW, REPO)}；先跑 --update 生成")
        return 2

    added = sorted(set(dead) - allow)
    removed = sorted(allow - set(dead))

    if added:
        print(f"  ✗ 新增死文案 {len(added)} 条（基线 {len(allow)} 条）：")
        for n in added[:10]:
            print(f"     {n}")
        if len(added) > 10:
            print(f"     …（其余 {len(added) - 10} 条）")
        print("     处理：三语一起删掉，或把功能真的接上。不要往基线里加。")
    if removed:
        print(f"  ⚠ 基线里 {len(removed)} 条已经不在了（删得好，但基线要同步）：")
        for n in removed[:5]:
            print(f"     {n}")
        print("     处理：python3 tools/dead_strings.py --update")

    if added or removed:
        return 1

    print(f"  ✓ 与基线一致（{len(allow)} 条已知死文案，没有新增）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
