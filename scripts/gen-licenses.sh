#!/usr/bin/env bash
# Copyright (C) 2026 Stephen de la Vega
# SPDX-License-Identifier: GPL-3.0-or-later
# Regenerates the open-source licence notices from the bundled wallpapers, the
# Rust crate graph (Cargo.lock) and a fixed list of Android libraries. Output is
# sorted and deterministic, so a regenerate-and-diff shows any drift. The MIT and
# Apache-2.0 texts (scripts/license-texts/) are appended once each, and every
# component is listed with its copyright holder where one is known (crate
# `authors` from cargo metadata, fixed lists for Android libraries and wallpapers).
#   app/src/main/assets/licenses/third_party.txt   shown by About > Open-source licences
#   THIRD_PARTY.md                                  the same content for the repository
set -euo pipefail
cd "$(dirname "$0")/.."

CARGO_BIN="${CARGO:-cargo}"
TXT=app/src/main/assets/licenses/third_party.txt
MD=THIRD_PARTY.md
mkdir -p "$(dirname "$TXT")"

CARGO_JSON="$("$CARGO_BIN" metadata --format-version 1 --locked)"

CARGO_JSON="$CARGO_JSON" python3 -I - "$TXT" "$MD" <<'PY'
import json, os, re, sys

txt_path, md_path = sys.argv[1], sys.argv[2]
wall_root = "app/src/main/assets/wallpapers"
AETHERDESK = "https://github.com/sdelavega/AetherDesk"
VENDORED = {"WeatherAether", "GradientWave", "MatrixRain", "NeonCity"}
# Third-party components bundled inside a vendored wallpaper: folder -> (name, licence).
# The licence text is found by walking the folder for a licence-named file.
NESTED = {"WeatherAether": ("three.js", "MIT"), "NeonCity": ("three.js", "MIT")}

# (heading, [(name, version, licence, source)])
sections = []

# Neon City's LivelyInfo.json credits the original Lively author; the copyright holder
# named in its LICENSE.txt (MIT, Stephen de la Vega) is listed as the author here.
AUTHOR_OVERRIDE = {"NeonCity": "Stephen de la Vega"}
LICENSE_TEXTS = [
    ("MIT", "scripts/license-texts/MIT.txt"),
    ("Apache-2.0", "scripts/license-texts/Apache-2.0.txt"),
]

def read_text(path):
    with open(path, encoding="utf-8") as f:
        return f.read().rstrip("\n")

# --- Wallpapers ---------------------------------------------------------
walls = []
for folder in sorted(os.listdir(wall_root)):
    d = os.path.join(wall_root, folder)
    if not os.path.isdir(d):
        continue
    with open(os.path.join(d, "LivelyInfo.json"), encoding="utf-8") as f:
        info = json.load(f)
    title = info.get("Title") or folder
    author = AUTHOR_OVERRIDE.get(folder) or info.get("Author") or "unknown"
    lic = info.get("License")
    source = info.get("Contact") or ""
    if folder in VENDORED:
        # Vendored from AetherDesk; entries without a License field are GPL-3.0-only.
        lic = lic or "GPL-3.0-only"
        source = AETHERDESK
    elif folder == "Rainfall":
        source = "https://github.com/sdelavega/lumenfall"
    lic = lic or "unspecified"
    files = sorted(n for n in os.listdir(d) if n.lower() in ("license", "license.txt", "license.md"))
    nested = ""
    if folder in NESTED:
        nested_files = sorted(
            os.path.relpath(os.path.join(r, n), d).replace(os.sep, "/")
            for r, _, ns in os.walk(d) for n in ns
            if "license" in n.lower() and os.path.relpath(r, d) != ".")
        nested = (NESTED[folder][0], NESTED[folder][1], nested_files[0] if nested_files else "")
    walls.append((folder, title, author, lic, source, files[0] if files else "", nested))

# --- Crates ---------------------------------------------------------------
meta = json.loads(os.environ["CARGO_JSON"])
members = set(meta["workspace_members"])
crates = sorted(
    ((p["name"], p["version"], p.get("license") or ("see " + p["license_file"] if p.get("license_file") else "unspecified"),
      p.get("repository") or "",
      ", ".join(re.sub(r"\s*<[^>]*>", "", a).strip() for a in p.get("authors") or [] if a.strip()))
     for p in meta["packages"] if p["id"] not in members))

# --- Android libraries (fixed list) ----------------------------------------
AOSP = "Copyright The Android Open Source Project"
android = [
    ("androidx.core:core-ktx", "Apache-2.0", AOSP),
    ("androidx.activity:activity-ktx", "Apache-2.0", AOSP),
    ("androidx.appcompat:appcompat", "Apache-2.0", AOSP),
    ("androidx.webkit:webkit", "Apache-2.0", AOSP),
    ("androidx.recyclerview:recyclerview", "Apache-2.0", AOSP),
    ("androidx.media3:media3-exoplayer", "Apache-2.0", AOSP),
    ("androidx.media3:media3-ui", "Apache-2.0", AOSP),
    ("com.google.android.material:material", "Apache-2.0", "Copyright Google LLC"),
    ("org.jetbrains.kotlin:kotlin-stdlib", "Apache-2.0", "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors"),
]
android.sort()

def txt():
    out = ["Lumenfall is licensed GPL-3.0-or-later. It bundles and links the following third-party work.", ""]
    out += ["BUNDLED WALLPAPERS", "=================="]
    for folder, title, author, lic, source, lf, nested in walls:
        out.append(f"{title} [{folder}]")
        out.append(f"  Author: {author}")
        out.append(f"  License: {lic}" + (f" (see {folder}/{lf})" if lf else ""))
        if source:
            out.append(f"  Source: {source}")
        if nested:
            out.append(f"  Includes: {nested[0]} - {nested[1]}" + (f" (see {folder}/{nested[2]})" if nested[2] else ""))
        out.append("")
    out += ["RUST CRATES", "==========="]
    for n, v, l, r, a in crates:
        out.append(f"{n} {v} - {l}" + (f" - {r}" if r else ""))
        if a:
            out.append(f"  Copyright: {a}")
    out += ["", "ANDROID LIBRARIES", "================="]
    for n, l, c in android:
        out.append(f"{n} - {l}")
        out.append(f"  {c}")
    out += ["", "LICENCE TEXTS", "============="]
    for name, path in LICENSE_TEXTS:
        out += ["", f"--- {name} ---", "", read_text(path)]
    return "\n".join(out) + "\n"

def md():
    out = ["# Third-party notices", "",
           "Generated by `scripts/gen-licenses.sh` (`just licenses`); do not edit by hand.", "",
           "Lumenfall is licensed GPL-3.0-or-later. It bundles and links the following third-party work.", "",
           "## Bundled wallpapers", "",
           "| Wallpaper | Folder | Author | Licence | Licence file | Source |", "|---|---|---|---|---|---|"]
    for folder, title, author, lic, source, lf, nested in walls:
        if nested:
            lic = f"{lic}; includes {nested[0]} ({nested[1]})"
            lf = lf or nested[2]
        out.append(f"| {title} | `{folder}` | {author} | {lic} | " + (f"`{folder}/{lf}`" if lf else "") + f" | {source} |")
    out += ["", "## Rust crates", "", "| Crate | Version | Licence | Copyright | Repository |", "|---|---|---|---|---|"]
    for n, v, l, r, a in crates:
        out.append(f"| {n} | {v} | {l} | {a} | {r} |")
    out += ["", "## Android libraries", "", "| Library | Licence | Copyright |", "|---|---|---|"]
    for n, l, c in android:
        out.append(f"| {n} | {l} | {c} |")
    out += ["", "## Licence texts"]
    for name, path in LICENSE_TEXTS:
        out += ["", f"### {name}", "", "```text", read_text(path), "```"]
    return "\n".join(out) + "\n"

open(txt_path, "w", encoding="utf-8").write(txt())
open(md_path, "w", encoding="utf-8").write(md())
PY
echo "wrote $TXT and $MD"
