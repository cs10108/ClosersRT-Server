#!/usr/bin/env python3
"""build_langfix_apk.py - repack the game APK with a new classes.dex.

The game-side language switch lives in SaveFix.install(), which already runs as
the first statement of GameActivity.onCreate.  This script swaps ONLY
classes.dex (plus the versionName), so libUE4.so and every other entry stay
byte-identical to the source build.

Usage
    python tools/build_langfix_apk.py --src build/closersRT-narrative.apk \
        --dex work/classes_lang.dex --out build/closersRT-lang.apk
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORK = os.path.join(ROOT, "work")
sys.path.insert(0, os.path.join(ROOT, "tools"))
from build_goout_apk import patch_manifest_version  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--dex", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--version-name", default="59981-261107lang")
    ap.add_argument("--keystore",
                    default=os.path.join(ROOT, "build", "signing", "offline.keystore"))
    ap.add_argument("--ks-pass", default="android")
    ap.add_argument("--alias", default="offlinekey")
    a = ap.parse_args()

    dex = open(a.dex, "rb").read()
    unsigned = os.path.join(WORK, "lang-unsigned.apk")
    print("[1/3] repack  (%s)" % os.path.basename(a.src))
    with zipfile.ZipFile(a.src) as zin, \
            zipfile.ZipFile(unsigned, "w", zipfile.ZIP_STORED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == "classes.dex":
                data = dex
                print("    classes.dex -> %d bytes" % len(dex))
            elif item.filename == "AndroidManifest.xml" and a.version_name:
                data = patch_manifest_version(data, a.version_name)
            zout.writestr(item, data)

    bt = os.path.join(os.environ.get("LOCALAPPDATA", ""),
                      "Android", "Sdk", "build-tools", "34.0.0")
    aligned = os.path.join(WORK, "lang-aligned.apk")
    print("[2/3] zipalign")
    zipalign = os.path.join(bt, "zipalign.exe")
    subprocess.run([zipalign, "-f", "-p", "4", unsigned, aligned], check=True)

    print("[3/3] sign")
    apksigner = os.path.join(bt, "apksigner.bat")
    subprocess.run([apksigner, "sign", "--ks", a.keystore,
                    "--ks-pass", "pass:" + a.ks_pass,
                    "--ks-key-alias", a.alias, "--out", a.out, aligned],
                   check=True)
    print("[i] APK:", a.out, "%s bytes" % format(os.path.getsize(a.out), ","))
    return 0


if __name__ == "__main__":
    sys.exit(main())
