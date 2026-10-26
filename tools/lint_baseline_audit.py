#!/usr/bin/env python3
"""baseline 账目核对：`lint-baseline.xml` 的条数是否等于 lint 实际过滤掉的条数。

为什么单独成文件：这段判断原来埋在 gate.sh 的 heredoc 里，只能靠「整跑一次 lint」来验，
而 lint 在这台机器上一次要十分钟。抽出来之后可以喂已知会失败的输入，确认它真的会红 ——
门禁最怕的不是不够严，而是「看起来在拦、其实永远放行」。

用法：
  python3 tools/lint_baseline_audit.py <lint-results-debug.xml> <lint-baseline.xml>

退出码：0 = 账目一致；1 = 报告里有未抑制的问题；2 = 读不到/解析不了/账目不平。
"""

import re
import sys
import time
import xml.etree.ElementTree as ET


def main() -> int:
    if len(sys.argv) != 3:
        print("用法: %s <lint-results-debug.xml> <lint-baseline.xml>" % sys.argv[0])
        return 2
    report_path, baseline_path = sys.argv[1], sys.argv[2]

    try:
        root = ET.parse(report_path).getroot()
    except Exception as error:  # noqa: BLE001 - 任何解析失败都按红处理
        print("  ✗ 读不到 lint 报告（%s），按失败处理" % error)
        return 2

    # LintBaseline 是「baseline 过滤了多少条」的提示，不是代码问题
    issues = [i for i in root.iter("issue") if i.get("id") != "LintBaseline"]
    stamp = time.strftime("%H:%M:%S", time.localtime(_mtime(report_path)))
    if issues:
        print("  ✗ lint 报告有 %d 条问题（报告时间 %s）：" % (len(issues), stamp))
        for issue in issues[:10]:
            location = issue.find("location")
            where = "?"
            if location is not None:
                name = (location.get("file") or "?").rsplit("/", 1)[-1]
                where = "%s:%s" % (name, location.get("line") or "?")
            print("    - [%s] %s" % (issue.get("id"), where))
        return 1
    print("  ✓ lint：报告里没有新增问题（报告时间 %s）" % stamp)

    # baseline 的陈旧条目不会让 lint 变红，只在 Gradle 日志里印一句
    # 「N errors/warnings were listed in the baseline file but not found」。这类条目只增不减时，
    # baseline 会退化成「谁也不知道还在拦什么」的墓地。所以在这里对一次账：
    # baseline 的 issue 条数，必须等于 lint 自己报告的「被 baseline 过滤掉的条数」。
    try:
        base_count = len(list(ET.parse(baseline_path).getroot()))
    except Exception as error:  # noqa: BLE001
        print("  ✗ 读不到 %s（%s）" % (baseline_path, error))
        return 2

    hint = None
    for issue in root.iter("issue"):
        if issue.get("id") == "LintBaseline":
            hint = issue.get("message") or ""
    match = re.search(r"(\d+) errors and (\d+) hints were filtered", hint or "")
    if hint is None:
        print("  ! lint 没有报告 baseline 过滤统计，跳过陈旧条目核对（baseline 可能是空的）")
    elif not match:
        print("  ✗ 无法解析 baseline 过滤统计（lint 消息格式可能变了）：%r" % hint)
        return 2
    else:
        filtered = int(match.group(1)) + int(match.group(2))
        if filtered != base_count:
            print(
                "  ✗ baseline 账目不平：lint 只过滤了 %d 条，baseline 却记着 %d 条（差 %d）"
                % (filtered, base_count, base_count - filtered)
            )
            print("    多半是某条 issue 已修好或被 @SuppressLint 覆盖，请从 %s 删掉对应 <issue>" % baseline_path)
            return 2
        print("  ✓ baseline 账目一致：%d 条全部命中" % base_count)
    return 0


def _mtime(path: str) -> float:
    try:
        import os

        return os.path.getmtime(path)
    except OSError:
        return time.time()


if __name__ == "__main__":
    sys.exit(main())
