#!/usr/bin/env python3
"""Fails when an APK contains Firebase or Google Play services code.

The `foss` flavor must not ship any com.google.firebase / com.google.android.gms class (F-Droid, de-Googled
devices). Every class referenced or defined in a dex file appears as a type descriptor in its string table
(e.g. "Lcom/google/firebase/FirebaseApp;"), so searching the raw dex bytes is enough. Run it on the
unminified debug APK, where R8 has not renamed anything:

    python3 scripts/check-foss-apk.py app/build/outputs/apk/foss/debug/app-foss-debug.apk
"""
import re
import sys
import zipfile

FORBIDDEN = re.compile(rb"Lcom/google/(firebase|android/gms)/[A-Za-z0-9_/$]+;")

if len(sys.argv) < 2:
    print(__doc__)
    sys.exit(2)

failed = False
for apk in sys.argv[1:]:
    with zipfile.ZipFile(apk) as zf:
        dex_names = [n for n in zf.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
        hits = set()
        for name in dex_names:
            hits.update(m.group(0).decode() for m in FORBIDDEN.finditer(zf.read(name)))
        firebase_files = [n for n in zf.namelist() if "firebase" in n.lower() or "play-services" in n.lower()]
    if hits or firebase_files:
        failed = True
        print(f"{apk}: {len(hits)} Google class descriptors, {len(firebase_files)} Firebase/Play files, e.g.")
        for item in sorted(hits)[:10] + firebase_files[:10]:
            print(f"  {item}")
    else:
        print(f"{apk}: OK ({len(dex_names)} dex files, no com.google.firebase / com.google.android.gms classes)")

sys.exit(1 if failed else 0)
