#!/usr/bin/env python3
"""
build_proxy_apk.py — build the Closers RT local-proxy APK WITHOUT Gradle.

The proxy is a small Java Android app, so the whole toolchain is just the SDK
build-tools we already have:

    javac  ->  d8  ->  aapt2 link  ->  zipalign  ->  apksigner

No NDK, no Gradle, no Android Studio.  (kotlinc is avoided by writing plain
Java.)

Layout expected under proxy_app/:
    AndroidManifest.xml
    res/...                     (values/strings.xml, xml/..., mipmap/...)
    src/**/*.java
    assets/**                   (optional web UI, profiles, ...)

Usage:
    python tools/build_proxy_apk.py
    python tools/build_proxy_apk.py --out build/ClosersRT-Server.apk
"""
from __future__ import annotations

import argparse
import glob
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "proxy_app")
BUILD = os.path.join(ROOT, "build", "proxy")

SDK = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
BUILD_TOOLS = os.path.join(SDK, "build-tools", "34.0.0")
PLATFORM = os.path.join(SDK, "platforms", "android-34")
ANDROID_JAR = os.path.join(PLATFORM, "android.jar")

JAVA_HOME = None
for c in (r"C:\Program Files\Eclipse Adoptium",):
    if os.path.isdir(c):
        for d in sorted(os.listdir(c), reverse=True):
            if d.lower().startswith("jdk"):
                JAVA_HOME = os.path.join(c, d)
                break


def tool(name):
    p = os.path.join(BUILD_TOOLS, name)
    return p if os.path.isfile(p) else name


def java_tool(name):
    if JAVA_HOME:
        p = os.path.join(JAVA_HOME, "bin", name)
        if os.path.isfile(p):
            return p
    return name


def run(cmd, cwd=None, label=None):
    if label:
        print(f"  [{label}] " + " ".join(os.path.basename(str(c)) for c in cmd[:4]) + " ...")
    env = os.environ.copy()
    if JAVA_HOME:
        env["JAVA_HOME"] = JAVA_HOME
        env["PATH"] = os.path.join(JAVA_HOME, "bin") + os.pathsep + env.get("PATH", "")
    p = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if p.returncode != 0:
        print(f"  [!] exit {p.returncode}")
        for stream, txt in (("stdout", p.stdout), ("stderr", p.stderr)):
            if txt and txt.strip():
                print(f"  --- {stream} ---")
                print("\n".join("      " + l for l in txt.strip().splitlines()[-40:]))
    return p


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(ROOT, "build", "ClosersRT-Server.apk"))
    ap.add_argument("--keystore",
                    default=os.path.join(ROOT, "build", "signing", "offline.keystore"))
    ap.add_argument("--ks-pass", default="android")
    ap.add_argument("--alias", default="offlinekey")
    ap.add_argument("--clean", action="store_true")
    a = ap.parse_args()

    for p, what in ((ANDROID_JAR, "android.jar (platforms;android-34)"),
                    (tool("aapt2.exe"), "aapt2"),
                    (tool("d8.bat"), "d8"),
                    (tool("zipalign.exe"), "zipalign"),
                    (tool("apksigner.bat"), "apksigner")):
        if not os.path.isfile(p):
            print(f"[!] missing {what}: {p}")
            return 2
    print(f"[i] JAVA_HOME = {JAVA_HOME}")
    print(f"[i] android.jar = {ANDROID_JAR}")

    if a.clean and os.path.isdir(BUILD):
        shutil.rmtree(BUILD, ignore_errors=True)
    gen = os.path.join(BUILD, "gen")
    cls = os.path.join(BUILD, "classes")
    dex = os.path.join(BUILD, "dex")
    for d in (gen, cls, dex):
        os.makedirs(d, exist_ok=True)

    manifest = os.path.join(APP, "AndroidManifest.xml")
    if not os.path.isfile(manifest):
        print(f"[!] missing {manifest}")
        return 2

    # ---------------------------------------------------- 1. compile resources
    print("\n[1/6] aapt2 compile (resources)")
    flat = os.path.join(BUILD, "res.zip")
    resdir = os.path.join(APP, "res")
    if os.path.isdir(resdir):
        p = run([tool("aapt2.exe"), "compile", "--dir", resdir, "-o", flat])
        if p.returncode != 0:
            return 1
    else:
        print("  (no res/ directory)")

    # ---------------------------------------------------- 2. compile java
    print("\n[2/6] javac")
    sources = []
    for pat in ("src/**/*.java",):
        sources += glob.glob(os.path.join(APP, pat), recursive=True)
    if not sources:
        print("  [!] no .java sources under proxy_app/src")
        return 1
    print(f"  {len(sources)} source file(s)")
    argfile = os.path.join(BUILD, "javac.args")
    with open(argfile, "w", encoding="utf-8") as fh:
        # Android's classic javac invocation: target 8 so d8 is happy, and
        # -bootclasspath to override the JDK's java.* with android.jar.
        # (-bootclasspath is rejected together with --release/-target 17.)
        fh.write("-source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8\n")
        # every path must be quoted: the workspace contains spaces
        fh.write('-bootclasspath "' + ANDROID_JAR.replace("\\", "/") + '"\n')
        fh.write('-d "' + cls.replace("\\", "/") + '"\n')
        for s in sources:
            fh.write('"' + s.replace("\\", "/") + '"\n')
    p = run([java_tool("javac.exe"), "@" + argfile])
    if p.returncode != 0:
        return 1

    # ---------------------------------------------------- 3. dex
    print("\n[3/6] d8 (dex)")
    # Two d8 quirks found by bisecting this build:
    #
    #  1. d8 does NOT pull nested classes in from a directory on its own, so
    #     they must be listed explicitly or the app dies at runtime with
    #     NoClassDefFoundError: Foo$Bar.
    #  2. Handing it javac's synthetic `Foo$1` marker classes makes R8/D8 8.2
    #     abort with an internal NullPointerException ("Cannot invoke
    #     String.length() because <parameter1> is null").
    #
    # So: list every class file except the numeric synthetic ones.
    SYNTHETIC = re.compile(r"\$\d+\.class$")
    classes = []
    skipped = []
    for dp, _dn, fns in os.walk(cls):
        for f in fns:
            if not f.endswith(".class"):
                continue
            if SYNTHETIC.search(f):
                skipped.append(f)
                continue
            classes.append(os.path.join(dp, f))
    classes.sort()
    print(f"  {len(classes)} class file(s), "
          f"{len(skipped)} synthetic skipped ({', '.join(sorted(skipped)) or 'none'})")
    p = run([tool("d8.bat"), "--min-api", "21", "--output", dex,
             "--lib", ANDROID_JAR] + classes)
    if p.returncode != 0:
        return 1

    # ---------------------------------------------------- 4. link apk
    print("\n[4/6] aapt2 link")
    unaligned = os.path.join(BUILD, "unaligned.apk")
    cmd = [tool("aapt2.exe"), "link", "-o", unaligned,
           "-I", ANDROID_JAR, "--manifest", manifest,
           "--min-sdk-version", "21", "--target-sdk-version", "34",
           "--version-code", "1", "--version-name", "1.0",
           "--auto-add-overlay"]
    if os.path.isfile(flat):
        cmd += ["-R", flat]
    assets = os.path.join(APP, "assets")
    if os.path.isdir(assets):
        cmd += ["-A", assets]
    p = run(cmd)
    if p.returncode != 0:
        return 1

    # ---------------------------------------------------- 5. add classes.dex
    print("\n[5/6] adding classes.dex and zipalign")
    dexfile = os.path.join(dex, "classes.dex")
    if not os.path.isfile(dexfile):
        print(f"  [!] {dexfile} not produced")
        return 1
    withzip = os.path.join(BUILD, "withclasses.apk")
    import zipfile
    shutil.copyfile(unaligned, withzip)
    with zipfile.ZipFile(withzip, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dexfile, "classes.dex")
    aligned = os.path.join(BUILD, "aligned.apk")
    p = run([tool("zipalign.exe"), "-f", "-p", "4", withzip, aligned])
    if p.returncode != 0:
        return 1

    # ---------------------------------------------------- 6. sign
    print("\n[6/6] apksigner")
    ks = a.keystore
    if not os.path.isfile(ks):
        os.makedirs(os.path.dirname(ks), exist_ok=True)
        run([java_tool("keytool.exe"), "-genkeypair", "-keystore", ks,
             "-storepass", a.ks_pass, "-keypass", a.ks_pass, "-alias", a.alias,
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-dname", "CN=ClosersRT Proxy, O=offline, C=TW"])
    p = run([tool("apksigner.bat"), "sign", "--ks", ks,
             "--ks-pass", f"pass:{a.ks_pass}", "--ks-key-alias", a.alias,
             "--key-pass", f"pass:{a.ks_pass}",
             "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
             "--out", a.out, aligned])
    if p.returncode != 0:
        return 1

    print(f"\n[i] APK: {a.out} ({os.path.getsize(a.out)/1e6:.1f} MB)")
    run([tool("apksigner.bat"), "verify", "--print-certs", a.out])
    return 0


if __name__ == "__main__":
    sys.exit(main())
