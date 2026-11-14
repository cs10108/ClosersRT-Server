#!/usr/bin/env python3
"""build_patched_so_apk.py — repack the game APK with a patched libUE4.so.

WHY THIS EXISTS
---------------
Android 15 turns a JNI misuse into a hard abort: `GetMethodID(nullptr, ...)`
now kills the process instead of returning null.  Closers RT hits exactly that
while binding the GamePot login SDK:

    UCMGameInstance::Init()
      -> BindingGamePotInterface()                      @0x07afe85c
        -> GetSharedGamePotSdk()   (FGamePotSDKPluginModule)
          -> FAndroidJavaGamePotSdk ctor -> FindClass returns null
            -> GetMethodID(null) -> ART abort

The offline server does not need GamePot at all, so this build patches one
instruction to skip the binding:

    0x07afe8a8  tbz  w0, #0, 0x07afee94    ; skipping already exists
    becomes
    0x07afe8a8  b    0x07afee94            ; always skip

The branch target is the function's own epilogue (restores x19..x21 and
returns), so no state is left half-initialised -- this is the same path the
code takes when its precondition fails.

Usage
    python tools/build_patched_so_apk.py
    python tools/build_patched_so_apk.py --src build/closersRT-autounpack.apk
"""
from __future__ import annotations

import argparse
import os
import shutil
import struct
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD = os.path.join(ROOT, "build")
WORK = os.path.join(ROOT, "work")

DEFAULT_SRC = os.path.join(BUILD, "closersRT-autounpack.apk")
SO_IN_APK = "lib/arm64-v8a/libUE4.so"

# virtual address of the tbz and its skip target (see the docstring)
TBZ_VA = 0x07afe8a8
SKIP_VA = 0x07afee94

# FGamePotSDKPluginModule::GetSharedGamePotSdk
GSGS_VA = 0x071f306c

# UCMGameInstance::Init()+652: `bl StorageCoreSDK::initSDK`.
# Android 15 aborts in that SDK's own FindClass the same way it did in
# GamePot's, so the call is neutralised.  Its return value is discarded
# by the caller (the next instruction reads the stack, not w0), and the
# stack arguments are released right afterwards anyway.
CALL3_VA = 0x07afe730
TARGET3_VA = 0x072aa334


def find_adb():
    for c in (r"E:\LDPlayer\dnplayer-tw\adb.exe", "adb"):
        try:
            subprocess.run([c, "version"], capture_output=True, timeout=10)
            return c
        except Exception:
            continue
    return None


def so_offset(data: bytes, va: int) -> int:
    """Map a virtual address to a file offset using the PT_LOAD headers."""
    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
    e_phnum = struct.unpack_from("<H", data, 0x38)[0]
    for i in range(e_phnum):
        o = e_phoff + i * e_phentsize
        p_type, p_flags, p_offset, p_vaddr, _pa, p_filesz, _ms, _al = \
            struct.unpack_from("<IIQQQQQQ", data, o)
        if p_type == 1 and p_vaddr <= va < p_vaddr + p_filesz:
            return p_offset + (va - p_vaddr)
    raise SystemExit(f"VA {va:#x} is not inside any PT_LOAD segment")


def patch_so(src_so: str, dst_so: str) -> bool:
    """Apply both GamePot patches.  See the module docstring."""
    d = open(src_so, "rb").read()
    buf = bytearray(d)

    # --- patch 1: skip the first binding attempt ------------------------
    off1 = so_offset(d, TBZ_VA)
    want1 = 0x14000000 | (((SKIP_VA - TBZ_VA) // 4) & 0x3FFFFFF)
    if struct.unpack_from("<I", d, off1)[0] != want1:
        if struct.unpack_from("<I", d, off1)[0] != 0x36002F60:
            print(f"    UNEXPECTED instruction at {TBZ_VA:#x}; refusing")
            return False
        struct.pack_into("<I", buf, off1, want1)
        print(f"    patch 1: tbz -> b at {TBZ_VA:#x}")
    else:
        print("    patch 1: already applied")

    # --- patch 2: make the SDK getter always return null ----------------
    # Every caller checks `cbz x0` before dereferencing, so returning null
    # takes the "SDK unavailable" path instead of constructing the SDK.
    off2 = so_offset(d, GSGS_VA)
    cur2 = struct.unpack_from("<I", d, off2)[0]
    if cur2 == 0x52800000:
        print("    patch 2: already applied")
    elif cur2 == 0xA9BE4FF4:
        struct.pack_into("<I", buf, off2, 0x52800000)      # mov w0, #0
        struct.pack_into("<I", buf, off2 + 4, 0xD65F03C0)  # ret
        print(f"    patch 2: GetSharedGamePotSdk -> return null at {GSGS_VA:#x}")
    else:
        print(f"    UNEXPECTED prologue {cur2:#010x} at {GSGS_VA:#x}; refusing")
        return False

    # --- patch 3: skip StorageCoreSDK::initSDK --------------------------
    off3 = so_offset(d, CALL3_VA)
    cur3 = struct.unpack_from("<I", d, off3)[0]
    if cur3 == 0x52800000:
        print("    patch 3: already applied")
    elif (cur3 & 0xFC000000) == 0x94000000:
        imm = cur3 & 0x3FFFFFF
        if imm & 0x2000000: imm -= 0x4000000
        if CALL3_VA + imm * 4 != TARGET3_VA:
            print(f"    UNEXPECTED call target {CALL3_VA + imm*4:#x}; refusing")
            return False
        struct.pack_into("<I", buf, off3, 0x52800000)   # mov w0, #0
        print(f"    patch 3: skip initSDK at {CALL3_VA:#x}")
    else:
        print(f"    UNEXPECTED instruction {cur3:#010x} at {CALL3_VA:#x}; refusing")
        return False

    # --- patch 4: Memory of Dimension (차원의 기억) stage lock -----------
    # UUINarrativeStageListItem exposes two Blueprint getters that the UMG
    # asset uses to decide whether a stage card is playable:
    #   execGetIsFavorOpen  0x0882f1dc  ldrb w8,[x0,#917]
    #   execGetIsTodayClear 0x0882f1f8  ldrb w8,[x0,#916]
    # Both just publish a bitfield the widget owns, so forcing the return
    # value to 1 makes every slot read as "favor requirement met and previous
    # stage cleared".  The operator already has favor Lv.9 on all 27
    # characters, so the clear flag is the only remaining gate.
    for va in (0x0882F1DC, 0x0882F1F8):
        off4 = so_offset(d, va)
        cur4 = struct.unpack_from("<I", d, off4)[0]
        if cur4 == 0x52800028:
            print(f"    patch 4: already applied at {va:#x}")
        elif cur4 in (0x394E5408, 0x394E5008):
            struct.pack_into("<I", buf, off4, 0x52800028)   # mov w8,#1
            print(f"    patch 4: force true at {va:#x}")
        else:
            print(f"    UNEXPECTED {cur4:#010x} at {va:#x}; refusing")
            return False

    open(dst_so, "wb").write(buf)
    return True

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=DEFAULT_SRC)
    ap.add_argument("--out", default=os.path.join(BUILD, "closersRT-android15.apk"))
    ap.add_argument("--keystore", default=os.path.join(BUILD, "signing", "offline.keystore"))
    ap.add_argument("--ks-pass", default="android")
    ap.add_argument("--alias", default="offlinekey")
    a = ap.parse_args()

    if not os.path.isfile(a.src):
        print(f"  source APK missing: {a.src}")
        return 2
    os.makedirs(WORK, exist_ok=True)

    print("[1/4] extract " + SO_IN_APK)
    z = zipfile.ZipFile(a.src)
    if SO_IN_APK not in z.namelist():
        print("    not found in APK")
        return 2
    raw_so = os.path.join(WORK, "libUE4_orig.so")
    with z.open(SO_IN_APK) as fin, open(raw_so, "wb") as fout:
        shutil.copyfileobj(fin, fout, 1 << 20)
    print(f"    {os.path.getsize(raw_so):,} bytes")

    print("[2/4] patch")
    patched_so = os.path.join(WORK, "libUE4_patched.so")
    if not patch_so(raw_so, patched_so):
        return 2

    print("[3/4] repack")
    unsigned = os.path.join(WORK, "so-unsigned.apk")
    with zipfile.ZipFile(a.src) as zin, \
            zipfile.ZipFile(unsigned, "w", zipfile.ZIP_STORED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == SO_IN_APK:
                data = open(patched_so, "rb").read()
                print(f"    replaced {SO_IN_APK}")
            zout.writestr(item, data)

    aligned = os.path.join(WORK, "so-aligned.apk")
    bt = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "build-tools", "34.0.0")
    zipalign = os.path.join(bt, "zipalign.exe")
    apksigner = os.path.join(bt, "apksigner.bat")
    if os.path.isfile(zipalign):
        subprocess.run([zipalign, "-f", "-p", "4", unsigned, aligned], check=True)
    else:
        shutil.copyfile(unsigned, aligned)

    print("[4/4] sign")
    if os.path.isfile(apksigner):
        # NOTE: the input APK must be the LAST argument to apksigner.
        subprocess.run([apksigner, "sign", "--ks", a.keystore,
                        "--ks-pass", "pass:" + a.ks_pass,
                        "--key-pass", "pass:" + a.ks_pass,
                        "--ks-key-alias", a.alias,
                        "--out", a.out, aligned], check=True)
    else:
        shutil.copyfile(aligned, a.out)
        print("    apksigner missing; left unsigned")

    print(f"\n[i] APK: {a.out} ({os.path.getsize(a.out):,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())