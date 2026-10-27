#!/usr/bin/env python3
"""打印 APK 的 applicationId，用来在装机前确认「装的是哪个包」。

为什么需要它（2026-10-27，真机踩到，代价最大的一次）：
    Eta 的 canary 包名靠 gradle 属性 `-Peta.applicationIdSuffix=.canary` 生成，
    而环境变量 `ETA_APP_ID_SUFFIX` 只有 tools/gate.sh 会翻译成那个属性。
    手工 `./gradlew :app:assembleDebug` 时忘了属性 → APK 的包名回落成正式版
    `io.github.mangi.eta`。`pm install -r` 照样报 Success，
    结果：canary 一直是旧包（探针拿旧代码跑出新结论，报告格式都对不上），
    同时还把用户日常用的正式包覆盖了。装完才发现，损失不可逆。

做法：直接读 zip 里的 AndroidManifest.xml（二进制 AXML），在字符串池里找包名。
    AXML 的字符串可能是 UTF-16LE 也可能被压缩成 UTF-8，两种都找一遍；
    先找带后缀的，因为带后缀的串包含前缀串。

用法：
    python3 tools/apk-package.py app/build/outputs/apk/debug/Eta-v3.3.1-debug.apk
    python3 tools/apk-package.py --expect io.github.mangi.eta.canary <apk>   # 不符就退出码 3
"""
import sys
import zipfile


def apk_package(path: str) -> str:
    with zipfile.ZipFile(path) as zf:
        data = zf.read("AndroidManifest.xml")
    # 候选包名来自调用方关心的两种；更通用的做法是解 AXML 字符串池，
    # 但这里只需要判「是不是 canary」，多写一个解析器反而多一处出错。
    for candidate in ("io.github.mangi.eta.canary", "io.github.mangi.eta"):
        if candidate.encode("utf-16-le") in data or candidate.encode("utf-8") in data:
            return candidate
    return ""


def main() -> int:
    args = [a for a in sys.argv[1:]]
    expect = ""
    if "--expect" in args:
        i = args.index("--expect")
        expect = args[i + 1]
        del args[i:i + 2]
    if len(args) != 1:
        print(__doc__.strip().splitlines()[-2].strip(), file=sys.stderr)
        return 2
    package = apk_package(args[0])
    if not package:
        print("读不出包名（APK 损坏或不是标准 Android 包）: " + args[0], file=sys.stderr)
        return 2
    print(package)
    if expect and package != expect:
        print("包名不符：期望 %s，实际 %s —— 不要装！" % (expect, package), file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
