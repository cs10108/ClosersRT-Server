#!/usr/bin/env python3
"""Build Android Expansion Files (.obb) carrying the game content.

An APK cannot hold PakCache (3.7 GB): the archive would pass 4 GB and both
zipalign and apksigner reject the zip64 result.  Expansion files live outside
the APK, in

    /sdcard/Android/obb/com.naddicgames.projectm/main.<ver>.<pkg>.obb

so they have no such limit.  SaveFix.unpackObbDir() scans that directory and
unpacks every file it finds as a ZIP into getExternalFilesDir(null).

Everything here is STORED because the paks are already compressed (measured:
DEFLATE got them to 98% -- no point spending CPU on it).

Usage:
    python tools/build_obb.py                        # everything, split
    python tools/build_obb.py --only PakCache        # just PakCache
    python tools/build_obb.py --limit-mb 1500        # part size (default 1500)
"""
from __future__ import annotations

import argparse
import os
import shutil
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONTENT = os.path.join(ROOT, "SD card Android data", "com.naddicgames.projectm", "files")
OUTDIR = os.path.join(ROOT, "build", "obb")
PKG = "com.naddicgames.projectm"
VER = 59981
SKIP = {"SaveGames", "UE4Game", "xigncode", "CM"}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="")
    ap.add_argument("--limit-mb", type=int, default=1500)
    a = ap.parse_args()

    want = [d.strip() for d in a.only.split(",") if d.strip()] or None
    dirs = sorted(d for d in os.listdir(CONTENT)
                  if d not in SKIP
                  and os.path.isdir(os.path.join(CONTENT, d))
                  and (want is None or d in want))
    print("dirs:", dirs)

    if os.path.exists(OUTDIR):
        shutil.rmtree(OUTDIR)
    os.makedirs(OUTDIR, exist_ok=True)

    limit = a.limit_mb * 1024 * 1024
    parts = []

    def new_part():
        name = ("main.%d.%s.obb" % (VER, PKG)) if not parts \
                else ("patch.%d.%d.%s.obb" % (VER, len(parts), PKG))
        path = os.path.join(OUTDIR, name)
        parts.append((name, path, zipfile.ZipFile(
            path, "w", zipfile.ZIP_STORED, allowZip64=False)))
        print("  part", name)

    new_part()
    cur_size = 0
    for d in dirs:
        base = os.path.join(CONTENT, d)
        n = 0
        for root, _, files in os.walk(base):
            for f in files:
                p = os.path.join(root, f)
                sz = os.path.getsize(p)
                if cur_size + sz > limit:
                    parts[-1][2].close()
                    new_part()
                    cur_size = 0
                arc = os.path.relpath(p, CONTENT).replace("\\", "/")
                parts[-1][2].write(p, arc)
                cur_size += sz
                n += 1
        print(f"  {d}: {n} files")
    if parts:
        parts[-1][2].close()

    print("\nproduced:")
    for name, path, _ in parts:
        print(f"  {name}: {os.path.getsize(path)/1e6:.0f} MB")
    print(f"\ninstall with:")
    print(f"  adb shell mkdir -p /sdcard/Android/obb/{PKG}")
    for name, path, _ in parts:
        print(f"  adb push {path} /sdcard/Android/obb/{PKG}/{name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
