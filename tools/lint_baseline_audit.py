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

# 依赖版本检查依赖「远端元数据 + 本次解析出的依赖图」，两者都不确定：
# 同一个 commit 在不同时间／不同网络下，这几类可能报也可能不报
# （2026-10-26 实测：同一份 baseline，一次全部命中，另两次各差 1 条，
#  而两条依赖的远端最新版都仍存在）。这种不确定性不该让整道门禁忽红忽绿，
# 所以只对它们降级为「提示」，其余类型的陈旧条目依旧按红处理。
#
# 2026-11-01 补：`AndroidGradlePluginVersion` 与 `NewerVersionAvailable` 是同一类
# （「有新版本可用」），本轮上游发版后它们和 GradleDependency 一起变成 11 条陈旧条目，
# 脚本只认识 GradleDependency，于是门禁红。三条都属于同一个不稳定来源。
NETWORK_DEPENDENT_TYPES = {"GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable"}


def _unmatched_types(issue) -> set:
    """从 LintBaselineFixed 的 message 里取出「哪些类型没复现」。"""
    message = issue.get("message") or ""
    match = re.search(r"Unmatched issue types:\s*(.+)$", message)
    if not match:
        return set()
    # lint 会在末尾追加 ` (N)`（该类型未复现的条数），不是类型名的一部分。
    return {
        re.sub(r"\s*\(\d+\)$", "", part.strip())
        for part in match.group(1).split(",")
        if part.strip()
    }


def _unmatched_count(issue) -> int:
    message = issue.get("message") or ""
    match = re.match(r"\s*(\d+)", message)
    return int(match.group(1)) if match else 1


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
    # 只降级「全是依赖版本检查」的陈旧条目，并把少抑制的条数记下来，供下面的对账使用。
    relaxed = 0
    kept = []
    for issue in issues:
        if issue.get("id") == "LintBaselineFixed":
            types = _unmatched_types(issue)
            if types and types.issubset(NETWORK_DEPENDENT_TYPES):
                relaxed += _unmatched_count(issue)
                print(
                    "  ! 提示：baseline 里有 %d 条 %s 本次没有复现（依赖更新检查依赖远端元数据，不稳定），不阻断门禁"
                    % (_unmatched_count(issue), "/".join(sorted(types)))
                )
                continue
        kept.append(issue)
    issues = kept
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
        if filtered != base_count - relaxed:
            print(
                "  ✗ baseline 账目不平：lint 只过滤了 %d 条，baseline 记着 %d 条（已放过 %d 条依赖更新提示，差 %d）"
                % (filtered, base_count, relaxed, base_count - relaxed - filtered)
            )
            print("    多半是某条 issue 已修好或被 @SuppressLint 覆盖，请从 %s 删掉对应 <issue>" % baseline_path)
            return 2
        print("  ✓ baseline 账目一致：%d 条命中（另有 %d 条依赖更新提示未复现，已放过）" % (filtered, relaxed))
    return 0


def _mtime(path: str) -> float:
    try:
        import os

        return os.path.getmtime(path)
    except OSError:
        return time.time()


if __name__ == "__main__":
    sys.exit(main())
