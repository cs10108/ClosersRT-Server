#!/usr/bin/env python3
"""
make_patch_cert.py — build a self-signed TLS identity for the patch server.

The retail client fetches its manifests over HTTPS from

    https://download.closersrt.com/Patch_Live/...

so the local proxy has to terminate TLS for that hostname.  Java's standard
library cannot mint a certificate, so we generate one here and package it as a
PKCS#12 keystore that the app loads from its assets.

Outputs (under proxy_app/assets/):
    patch.p12        PKCS#12 with the leaf key + certificate, password below
    patch.crt        the certificate in PEM, for reference / manual trust

Usage:
    python tools/make_patch_cert.py
"""
from __future__ import annotations

import datetime as dt
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "proxy_app", "assets")

# The app hardcodes this; keep them in step.
PASSWORD = "closersrt"
ALIAS = "patch"

# Subject common name chosen by tools/find_ca_collision.py so that this
# certificate's OpenSSL directory hash equals an existing (and long expired)
# entry in Android's system trust store.  See the subject comment in main().
COLLIDE_CN = "RT3226973"
COLLIDE_HASH = 0x4E18C148

HOSTS = [
    "download.closersrt.com",
    "closersrt.com",
    "*.closersrt.com",
    "gamepot.apigw.ntruss.com",
    "localhost",
    "127.0.0.1",
]


def main() -> int:
    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.hazmat.primitives.serialization import pkcs12
        from cryptography.x509.oid import NameOID
    except ImportError:
        print("[!] the 'cryptography' package is required (pip install cryptography)")
        return 2

    os.makedirs(ASSETS, exist_ok=True)

    print("=== generating a 2048-bit RSA key ===")
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)

    # The subject carries ONLY the colliding common name.
    #
    # OpenSSL locates a trusted CA by hashing the certificate's subject and
    # looking for <8 hex digits>.0, so for our CA to be usable on a read-only
    # /system it has to overwrite a file that already exists -- and that means
    # its subject hash must equal that file's name.
    #
    # tools/find_ca_collision.py searched for exactly that and produced
    #
    #     CN=RT3226973  ->  hash 4e18c148  ->  existing file 4e18c148.0
    #                                         (Deutsche Telekom Root CA 2,
    #                                          expired 2019-07-09)
    #
    # The hash is computed over the DER of the subject alone, so NOTHING else
    # may be added here: an extra attribute would change the DER and lose the
    # collision.  Host name matching is carried entirely by the SAN below.
    subject = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, COLLIDE_CN),
    ])

    def _name_hash(name) -> int:
        import hashlib
        md = hashlib.md5(name.public_bytes()).digest()
        return (md[0] | md[1] << 8 | md[2] << 16 | md[3] << 24) & 0x7FFFFFFF

    got = _name_hash(subject)
    print(f"=== subject hash check ===")
    print(f"    subject DER : {subject.public_bytes().hex()}")
    print(f"    hash        : {got:08x}")
    if got != COLLIDE_HASH:
        print(f"[!] hash {got:08x} != expected {COLLIDE_HASH:08x}; "
              f"the collision was lost")
        return 1
    print(f"    matches existing file {COLLIDE_HASH:08x}.0  OK")
    san = []
    for h in HOSTS:
        if h.replace(".", "").isdigit():
            import ipaddress
            san.append(x509.IPAddress(ipaddress.ip_address(h)))
        else:
            san.append(x509.DNSName(h))

    now = dt.datetime.utcnow()
    cert = (x509.CertificateBuilder()
            .subject_name(subject)
            .issuer_name(subject)                      # self-signed
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - dt.timedelta(days=1))
            .not_valid_after(now + dt.timedelta(days=3650))
            .add_extension(x509.SubjectAlternativeName(san), critical=False)
            .add_extension(x509.BasicConstraints(ca=True, path_length=None),
                           critical=True)
            .sign(key, hashes.SHA256()))

    print(f"=== subject: CN={HOSTS[0]} ===")
    print(f"    SAN: {', '.join(HOSTS)}")
    print(f"    not after: {cert.not_valid_after_utc if hasattr(cert, 'not_valid_after_utc') else cert.not_valid_after}")

    crt_path = os.path.join(ASSETS, "patch.crt")
    with open(crt_path, "wb") as fh:
        fh.write(cert.public_bytes(serialization.Encoding.PEM))
    print(f"    wrote {crt_path}")

    # PKCS#8 PEM for the key.
    #
    # We deliberately ship PEM rather than a PKCS#12 keystore: Android 12's
    # BouncyCastle rejects the PBES2/HMAC-SHA256 encoding that Python's
    # `cryptography` produces ("No installed provider supports this key:
    # ...PKCS12Key"), whereas PKCS#8 and X.509 PEM parse through the standard
    # KeyFactory/CertificateFactory on every Android version we care about.
    key_path = os.path.join(ASSETS, "patch.key")
    with open(key_path, "wb") as fh:
        fh.write(key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption()))
    print(f"    wrote {key_path}")

    # Keep the PKCS#12 too, for tools that want it.
    p12_path = os.path.join(ASSETS, "patch.p12")
    p12 = pkcs12.serialize_key_and_certificates(
        name=ALIAS.encode(),
        key=key,
        cert=cert,
        cas=None,
        encryption_algorithm=serialization.BestAvailableEncryption(
            PASSWORD.encode()))
    with open(p12_path, "wb") as fh:
        fh.write(p12)
    print(f"    wrote {p12_path} ({len(p12)} bytes, password '{PASSWORD}', "
          f"alias '{ALIAS}')")

    # sanity: reload the PEM pair the way Java will
    try:
        from cryptography.hazmat.primitives.serialization import load_pem_private_key
        with open(key_path, "rb") as fh:
            k2 = load_pem_private_key(fh.read(), password=None)
        with open(crt_path, "rb") as fh:
            c2 = x509.load_pem_x509_certificate(fh.read())
        print(f"    verified: reloaded key={type(k2).__name__} "
              f"cert={c2.subject.rfc4514_string()}")
    except Exception as e:
        print(f"[!] reload check failed: {e}")
        return 1

    print("\n[OK] patch certificate ready")
    return 0


if __name__ == "__main__":
    sys.exit(main())
