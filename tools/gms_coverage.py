#!/usr/bin/env python3
"""GMS API coverage map: manifest component table of Google Play services vs this microG fork.

usage: gms_coverage.py <gmscore_manifest_components.csv> <fork merged AndroidManifest.xml> <out.md> [base=app.revanced]

Counts exported service actions, activity actions and provider authorities that apps reach in
com.google.android.gms.*, maps the fork's ${basePackageName} rename back, and lists what is missing.
"""
import csv, json, sys, re, collections
import xml.etree.ElementTree as ET

csv_path, manifest_path, out_path = sys.argv[1:4]
base = sys.argv[4] if len(sys.argv) > 4 else "app.revanced"
A = "{http://schemas.android.com/apk/res/android}"
G = "com.google.android.gms"


def norm(s):
    return s.replace(base + ".android.gms", G).replace("${basePackageName}.android.gms", G)


real = collections.defaultdict(set)   # kind -> names
owner = {}
for row in csv.DictReader(open(csv_path)):
    kind = row["kind"].replace("activity-alias", "activity")
    if row["exported"] != "true" and kind != "provider":
        continue
    if kind == "provider":
        for a in filter(None, row["authorities"].split(";")):
            if a.startswith(G):
                real["provider"].add(a); owner[("provider", a)] = row["name"]
        continue
    for f in json.loads(row["filters"] or "[]"):
        for act in f.get("actions", []):
            if act.startswith(G):
                real[kind].add(act); owner[(kind, act)] = row["name"]

fork = collections.defaultdict(set)
root = ET.parse(manifest_path).getroot()
for comp in root.iter():
    tag = comp.tag
    if tag in ("service", "activity", "activity-alias", "receiver"):
        kind = "activity" if tag.startswith("activity") else tag
        for act in comp.iter("action"):
            fork[kind].add(norm(act.get(A + "name", "")))
    elif tag == "provider":
        for a in (comp.get(A + "authorities") or "").split(";"):
            fork["provider"].add(norm(a))

# Generic module prefix: GMS client libraries bind <module>.START; group missing ones by module.
def module(act):
    m = re.match(re.escape(G) + r"\.([a-z0-9_]+)", act)
    return m.group(1) if m else "?"

lines = ["# GMS API coverage map", "",
         f"Reference: component table of Google Play services (`{csv_path.split('/')[-1]}`). Fork: merged manifest of `gmscore/` (`{base}.android.gms` mapped back to `{G}`).", "",
         "| Surface | Real GMS | Fork declares | Covered | Missing |", "|---|---|---|---|---|"]
missing_all = {}
for kind in ("service", "activity", "receiver", "provider"):
    r, f = real[kind], fork[kind]
    cov = r & f
    miss = sorted(r - f)
    missing_all[kind] = miss
    lines.append(f"| {kind} {'authorities' if kind == 'provider' else 'actions'} | {len(r)} | {len(f)} | {len(cov)} ({100 * len(cov) // max(1, len(r))}%) | {len(miss)} |")
lines.append("")
for kind, miss in missing_all.items():
    if not miss:
        continue
    lines += [f"## Missing {kind} {'authorities' if kind == 'provider' else 'actions'} ({len(miss)})", ""]
    by = collections.defaultdict(list)
    for m in miss:
        by[module(m)].append(m)
    for mod in sorted(by):
        lines.append(f"- **{mod}**: " + ", ".join(f"`{x[len(G) + 1:]}`" for x in by[mod]))
    lines.append("")
open(out_path, "w").write("\n".join(lines))
print("\n".join(lines[:10]))
