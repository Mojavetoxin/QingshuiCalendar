#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""清水日历交付打包器

把工程打成「解压 → 整个文件夹上传 GitHub → Actions 自动出 APK」的 zip。

设计要点（借鉴已验证的 webzip 范式）：
  1. **白名单**打包 —— 只收下面 ALLOW_TOP 里列的东西，其余一律不收（避免 build/、.gradle/、local.properties 混进去）
  2. **硬检查** —— 打完回读 zip，逐项确认关键文件真的在里面；缺一个就报错退出
  3. **防回退检查** —— 确认 workflow 里没有已知会失败的那一步
  4. **字节级回读自检** —— 每个文件从 zip 读出来跟源文件比 SHA-256，防止压缩环节出问题
  5. **可执行位保留** —— gradlew 必须是 0755，否则 Linux 上跑不起来

用法：
    python tools/pack.py                 # 默认输出到 D:\\ai天团\\normal_down\\QingshuiCalendar-web.zip
    python tools/pack.py -o 别的路径.zip
"""
import argparse
import hashlib
import io
import sys
import zipfile
from pathlib import Path

# ── 配置 ────────────────────────────────────────────────────────────
ZIP_ROOT = "QingshuiCalendar"          # zip 里那层文件夹名
ALLOW_TOP = {
    ".github", ".gitignore", "README.md",
    "app", "build.gradle.kts", "gradle", "gradle.properties",
    "gradlew", "gradlew.bat", "settings.gradle.kts",
}
EXCLUDE_DIRS = {"build", ".gradle", ".idea", "kotlin-js-store", "_vector_preview"}
EXCLUDE_FILES = {"local.properties", ".DS_Store", "Thumbs.db"}
EXCLUDE_SUFFIX = {".iml", ".hprof", ".apk", ".aab", ".keystore", ".jks"}
EXECUTABLE = {"gradlew"}

# 必须在包里的关键文件（相对 ZIP_ROOT）
REQUIRED = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties",
    "gradle/libs.versions.toml",
    ".gitignore",
    "README.md",
    ".github/workflows/build-apk.yml",
    "app/build.gradle.kts",
    "app/proguard-rules.pro",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/qingshui/calendar/MainActivity.kt",
    "app/src/main/java/com/qingshui/calendar/QingshuiApp.kt",
    "app/src/main/java/com/qingshui/calendar/ui/nav/AppRoot.kt",
    "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
    "app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml",
    "app/src/main/res/drawable/ic_launcher_foreground.xml",
    "app/src/main/res/drawable/ic_launcher_background.xml",
    "app/src/main/res/drawable/ic_launcher_monochrome.xml",
    "app/src/main/java/com/qingshui/calendar/ui/components/Feedback.kt",
    "app/src/main/res/values/strings.xml",
]

# 包内不允许出现的字串（防回退）
FORBIDDEN = [
    ("setup-android", "workflow 里又出现了 setup-android（实测会在 7 秒内红叉）"),
]


def sha256(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def collect(root: Path):
    """按白名单收集文件，返回 [(相对路径, 绝对路径)]"""
    out = []
    for name in sorted(ALLOW_TOP):
        p = root / name
        if not p.exists():
            print(f"  ⚠ 白名单项缺失（跳过）：{name}")
            continue
        if p.is_file():
            out.append((name, p))
            continue
        for f in sorted(p.rglob("*")):
            if not f.is_file():
                continue
            rel_parts = f.relative_to(root).parts
            if any(part in EXCLUDE_DIRS for part in rel_parts[:-1]):
                continue
            if f.name in EXCLUDE_FILES or f.suffix in EXCLUDE_SUFFIX:
                continue
            out.append((f.relative_to(root).as_posix(), f))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--out",
                    default=r"D:\ai天团\normal_down\QingshuiCalendar-web.zip")
    ap.add_argument("-r", "--root", default=None, help="工程根（默认脚本所在目录的上一级）")
    a = ap.parse_args()

    root = Path(a.root) if a.root else Path(__file__).resolve().parent.parent
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)

    print(f"工程根：{root}")
    files = collect(root)
    if not files:
        print("没有收集到任何文件，检查白名单", file=sys.stderr)
        return 1
    print(f"收集到 {len(files)} 个文件，开始打包…")

    src_digest = {}
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for rel, abs_p in files:
            data = abs_p.read_bytes()
            src_digest[rel] = sha256(data)
            zi = zipfile.ZipInfo(f"{ZIP_ROOT}/{rel}")
            zi.date_time = (2026, 1, 1, 0, 0, 0)          # 固定时间戳，保证可复现
            zi.compress_type = zipfile.ZIP_DEFLATED
            mode = 0o755 if abs_p.name in EXECUTABLE else 0o644
            zi.external_attr = (mode | 0o100000) << 16    # 保留可执行位
            z.writestr(zi, data)

    # ── 回读自检 ────────────────────────────────────────────────
    print("\n回读自检…")
    problems = []
    with zipfile.ZipFile(out) as z:
        names = set(z.namelist())

        for req in REQUIRED:
            if f"{ZIP_ROOT}/{req}" not in names:
                problems.append(f"缺少关键文件：{req}")

        # 逐文件字节比对
        mismatch = []
        for rel, digest in src_digest.items():
            if sha256(z.read(f"{ZIP_ROOT}/{rel}")) != digest:
                mismatch.append(rel)
        if mismatch:
            problems.append(f"{len(mismatch)} 个文件内容与源不一致：{mismatch[:5]}")

        # 防回退
        wf = f"{ZIP_ROOT}/.github/workflows/build-apk.yml"
        if wf in names:
            text = z.read(wf).decode("utf-8", errors="ignore")
            for bad, why in FORBIDDEN:
                if bad in text:
                    problems.append(f"workflow 含禁用内容 {bad!r}：{why}")

        # gradlew 可执行位
        info = z.getinfo(f"{ZIP_ROOT}/gradlew")
        mode = (info.external_attr >> 16) & 0o777
        if mode != 0o755:
            problems.append(f"gradlew 权限位是 {oct(mode)}，应为 0o755")

        # 不该出现的东西
        leaks = [n for n in names if "local.properties" in n or "/build/" in n
                 or "/.gradle/" in n or n.endswith(".iml")]
        if leaks:
            problems.append(f"混入了不该打包的文件：{leaks[:5]}")

        counts = {}
        for n in names:
            top = n.split("/")[1] if "/" in n else "(root)"
            counts[top] = counts.get(top, 0) + 1

    data = out.read_bytes()
    print(f"\n  包内文件数：{len(names)}")
    for k in sorted(counts):
        print(f"    {k:<24} {counts[k]}")
    print(f"  顶层结构：{sorted({n.split('/')[1] for n in names if '/' in n})}")

    if problems:
        print("\n❌ 自检未通过：", file=sys.stderr)
        for p in problems:
            print("   -", p, file=sys.stderr)
        return 1

    print(f"\n✅ 打包完成：{out}")
    print(f"   大小：{len(data) / 1024:.1f} KiB")
    print(f"   SHA-256：{sha256(data)}")
    print(f"\n用法：解压后把里面的「{ZIP_ROOT}」文件夹整个上传到 GitHub 仓库，Actions 会自动打出 APK。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
