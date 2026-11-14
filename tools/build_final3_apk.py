#!/usr/bin/env python3
"""Rebuild the Android-15 APK with the KNOWN-GOOD patched libUE4.so baked in.

Why: build/closersRT-android15.apk carries an early libUE4 that skipped the
GamePot binding (0x07afe8a8 tbz->b, 0x071f306c mov w0,#0).  With that build the
login popup opens and closes at once, so no GUEST button ever appears.  The
working build is work/libUE4_final3.so: original lib + StorageCore skip
(0x07afe730) + X509_verify_cert returns 1 (0x0be6aea8).  GamePot stays intact.

Baking final3 into the APK means a reinstall can never regress the login again.

    python tools/build_final3_apk.py
"""
import os, shutil, subprocess, sys, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD = os.path.join(ROOT, "build")
WORK = os.path.join(ROOT, "work")
SRC = os.path.join(BUILD, "closersRT-android15.apk")
OUT = os.path.join(BUILD, "closersRT-final3.apk")
SO = "lib/arm64-v8a/libUE4.so"
NEW_SO = os.path.join(WORK, "libUE4_final3.so")
KS = os.path.join(BUILD, "signing", "offline.keystore")
BUILD_TOOLS = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk",
                           "build-tools", "34.0.0")


def run(cmd):
    print("  $", " ".join(os.path.basename(c) if i == 0 else c for i, c in enumerate(cmd)))
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        print(p.stdout[-3000:]); print(p.stderr[-3000:])
        raise SystemExit(f"failed: {cmd[0]}")


def main():
    raw = os.path.join(WORK, "f3-unsigned.apk")
    aligned = os.path.join(WORK, "f3-aligned.apk")
    print("[1/3] repack")
    with zipfile.ZipFile(SRC) as zin, zipfile.ZipFile(raw, "w", zipfile.ZIP_STORED) as zout:
        for item in zin.infolist():
            if item.filename == SO:
                item.compress_type = zipfile.ZIP_STORED
                zout.writestr(item, open(NEW_SO, "rb").read())
                print("    replaced", SO, os.path.getsize(NEW_SO), "bytes (STORED)")
            else:
                zout.writestr(item, zin.read(item.filename))
    print("[2/3] zipalign")
    run([os.path.join(BUILD_TOOLS, "zipalign.exe"), "-f", "-p", "4", raw, aligned])
    print("[3/3] sign")
    if os.path.exists(OUT):
        os.remove(OUT)
    run([os.path.join(BUILD_TOOLS, "apksigner.bat"), "sign", "--ks", KS,
         "--ks-pass", os.environ.get("KS_PASS", "pass:android"), "--ks-key-alias", "offlinekey",
         "--key-pass", os.environ.get("KS_PASS", "pass:android"), "--v1-signing-enabled", "true",
         "--v2-signing-enabled", "true", "--out", OUT, aligned])
    print("APK:", OUT, os.path.getsize(OUT))


if __name__ == "__main__":
    sys.exit(main())
