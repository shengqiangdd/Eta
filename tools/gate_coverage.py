#!/usr/bin/env python3
"""量一下「门禁到底跑了几成用例」。

门禁的 test 阶段会先删结果目录、再只跑 FAST_TEST_FILTERS 白名单，所以不能拿门禁跑完的结果
算覆盖率 —— 先全量跑一次：

    JAVA_HOME=/workspace/.tooling/jdk17 ./gradlew :app:testDebugUnitTest

再：

    python3 tools/gate_coverage.py

只读 tools/gate.sh 与 app/build/test-results/testDebugUnitTest/*.xml，不改任何文件。
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

    classes: set[str] = set()
    total = failed = skipped = 0
    in_gate = in_gate_failed = 0
    reasons: collections.Counter[str] = collections.Counter()

    for path in files:
        root = ET.parse(path).getroot()
        classes.add(root.get("name") or os.path.basename(path))
        for case in root.iter("testcase"):
            cls = case.get("classname") or ""
            bad = [c for c in case if c.tag in ("failure", "error")]
            skip = any(c.tag == "skipped" for c in case)
            total += 1
            if bad:
                failed += 1
                msg = (bad[0].get("message") or bad[0].get("type") or "无 message").strip()
                reasons[msg.splitlines()[0][:100]] += 1
            elif skip:
                skipped += 1
            hit = any(fnmatch.fnmatchcase(cls, p) for p in pats)
            if hit:
                in_gate += 1
                if bad:
                    in_gate_failed += 1

    passed = total - failed - skipped
    outside_passed = passed - (in_gate - in_gate_failed)
    pct = 100.0 * in_gate / total if total else 0.0

    print(f"过滤规则 {len(pats)} 条")
    print(f"全量：{len(classes)} 类 / {total} 用例（失败 {failed}、跳过 {skipped}、通过 {passed}）")
    print(f"门禁内：{in_gate} 例（其中失败 {in_gate_failed}）→ 覆盖 {pct:.1f}%")
    print(f"门禁外通过：{outside_passed} 例")
    print("失败原因 top5：")
    for msg, count in reasons.most_common(5):
        print(f"  {count:>4} | {msg}")
    return 1 if in_gate_failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
