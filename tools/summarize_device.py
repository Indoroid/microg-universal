#!/usr/bin/env python3
"""Summarize device_test.sh logcats: only lines from the app's own processes.
usage: summarize_device.py <out/device dir>"""
import pathlib
import re
import sys

PKG = {
    "testapp": "test.gmsclient", "keep": "com.google.android.keep",
    "gmail-preinstalled": "com.google.android.gm", "gmail": "com.google.android.gm.morphe",
    "maps": "com.google.android.apps.maps.morphe", "chrome": "com.android.chrome.morphe",
}
SIGNALS = {
    "complains: GMS missing": re.compile(r"requires Google Play services, but they are missing|Google Play services is missing"),
    "complains: GMS dialog": re.compile(r"Creating dialog for Google Play services availability|GooglePlayServicesNotAvailable|SERVICE_MISSING"),
    "complains: invalid signature": re.compile(r"signature is invalid|doesn't match that of Google Play services"),
    "bridge (MorpheMicroG)": re.compile(r"MorpheMicroG"),
    "talks to microG": re.compile(r"app\.revanced\.android\.gms"),
    "crash": re.compile(r"FATAL EXCEPTION"),
}

d = pathlib.Path(sys.argv[1])
rows = []
for log in sorted(d.glob("*.logcat.txt")):
    label = log.name.split(".")[0]
    key = label.split("-", 1)[1]
    pkg = PKG.get(key)
    lines = log.read_text(errors="replace").splitlines()
    pids = {m.group(1) for l in lines for m in [re.search(r"Start proc (\d+):%s(?:[:/]|\s)" % re.escape(pkg or "-"), l)] if m}
    own = [l for l in lines if len(l) > 24 and l[19:].split()[0] in pids] if pids else []
    crash_own = sum(1 for i, l in enumerate(lines) if "FATAL EXCEPTION" in l and i + 1 < len(lines) and pkg and pkg in lines[i + 1])
    counts = {k: (crash_own if k == "crash" else sum(1 for l in own if r.search(l))) for k, r in SIGNALS.items()}
    first_bridge = next((l[31:].strip() for l in own if "MorpheMicroG" in l), "")
    rows.append((label, pkg, len(pids), counts, first_bridge))

for label, pkg, npid, c, fb in rows:
    print(f"{label:22} {pkg or '?':38} procs={npid} " + " ".join(f"{short}={c[k]}" for short, k in zip(("missing", "dialog", "badsig", "bridge", "microg", "crash"), c)))
    if fb:
        print(f"{'':22} bridge: {fb[:150]}")
