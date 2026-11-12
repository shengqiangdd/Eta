#!/usr/bin/env python3
"""量一下「门禁到底跑了几成用例」。

门禁的 test 阶段会先删结果目录、再只跑 FAST_TEST_FILTERS 白名单，所以不能拿门禁跑完的结果
算覆盖率 —— 先全量跑一次：

    JAVA_HOME=/workspace/.tooling/jdk17 ./gradlew :app:testDebugUnitTest

再：

    python3 tools/gate_coverage.py
    python3 tools/gate_coverage.py --write-wide   # 顺便刷新 tools/wide-tests.txt

只读 tools/gate.sh 与 app/build/test-results/testDebugUnitTest/*.xml；只有加 --write-wide
时才写 tools/wide-tests.txt（`STAGES=test-wide` 的名单）。
逐个 testcase 判定（不是按类），所以「同一类里部分用例失败」不会被算成整类没跑。
"""
from __future__ import annotations

import collections
import fnmatch
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = os.path.join(ROOT, "app/build/test-results/testDebugUnitTest/TEST-*.xml")


def filters() -> list[str]:
    path = os.path.join(ROOT, "tools/gate.sh")
    src = open(path, encoding="utf-8").read()
    match = re.search(r"FAST_TEST_FILTERS='(.*?)'\n", src, re.S)
    if not match:
        sys.exit("✗ tools/gate.sh 里找不到 FAST_TEST_FILTERS")
    return re.findall(r"--tests\s+(\S+)", match.group(1))


def main() -> int:
    pats = filters()
    files = glob.glob(RESULTS)
    if not files:
        sys.exit("✗ 没有测试结果文件：先全量跑一次 ./gradlew :app:testDebugUnitTest")

    per_class: dict[str, dict[str, int]] = {}
    total = failed = skipped = 0
    in_gate = in_gate_failed = 0
    reasons: collections.Counter[str] = collections.Counter()

    for path in files:
        root = ET.parse(path).getroot()
        for case in root.iter("testcase"):
            cls = case.get("classname") or ""
            item = per_class.setdefault(cls, {"tests": 0, "failed": 0, "skipped": 0})
            item["tests"] += 1
            bad = [c for c in case if c.tag in ("failure", "error")]
            skip = any(c.tag == "skipped" for c in case)
            total += 1
            if bad:
                failed += 1
                item["failed"] += 1
                msg = (bad[0].get("message") or bad[0].get("type") or "无 message").strip()
                reasons[msg.splitlines()[0][:100]] += 1
            elif skip:
                skipped += 1
                item["skipped"] += 1
            hit = any(fnmatch.fnmatchcase(cls, p) for p in pats)
            if hit:
                in_gate += 1
                if bad:
                    in_gate_failed += 1

    passed = total - failed - skipped
    outside_passed = passed - (in_gate - in_gate_failed)
    pct = 100.0 * in_gate / total if total else 0.0

    print(f"过滤规则 {len(pats)} 条")
    print(f"全量：{len(per_class)} 类 / {total} 用例（失败 {failed}、跳过 {skipped}、通过 {passed}）")
    print(f"门禁内：{in_gate} 例（其中失败 {in_gate_failed}）→ 覆盖 {pct:.1f}%")
    print(f"门禁外通过：{outside_passed} 例")
    print("失败原因 top5：")
    for msg, count in reasons.most_common(5):
        print(f"  {count:>4} | {msg}")

    if "--write-wide" in sys.argv[1:]:
        wide = sorted(n for n, v in per_class.items() if v["failed"] == 0 and v["skipped"] == 0)
        target = os.path.join(ROOT, "tools/wide-tests.txt")
        # 说明头必须跟着写回去：wide-tests.txt 会被反复重刷，
        # 如果每刷一次就抹掉注释，下一个人就不知道这份名单从哪来、怎么维护。
        header = [
            "# 本机（Linux/aarch64）能跑通的全部测试类，门禁 test-wide 阶段按这份名单执行。",
            "#",
            "# 为什么需要它：aarch64 没有 Robolectric 的 native runtime，93 个带",
            "# @RunWith(RobolectricTestRunner) 的类会被 app/build.gradle.kts 自动排除；",
            "# 剩下的纯 JVM 类也不是全部都能跑（个别依赖本机 setuid/进程能力），所以这里",
            "# 只收录「在本机实际跑通过」的类。",
            "#",
            "# 刷新（会覆盖本文件，但保留本说明）：",
            "#   1. ./gradlew :app:testDebugUnitTest --rerun   跑一轮全量",
            "#   2. python3 tools/gate_coverage.py --write-wide",
            "#",
            "# 名单漏登记不会报错，只会让门禁少跑一批用例——所以改了测试或换了机器后要重刷。",
        ]
        with open(target, "w", encoding="utf-8") as fh:
            fh.write("\n".join(header + wide) + "\n")
        print(f"已写入 {target}：{len(wide)} 个类（本机一次跑通；跨版本/跨机器需重跑确认）")
    return 1 if in_gate_failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
