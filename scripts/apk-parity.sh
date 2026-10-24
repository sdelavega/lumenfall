#!/usr/bin/env bash
# Copyright (C) 2026 Stephen de la Vega
# SPDX-License-Identifier: GPL-3.0-or-later
# Checks that a signed APK is exactly CI's unsigned APK plus a signature, the
# property F-Droid's reproducible-build verification needs. Reads only the two
# APKs; never any keystore. Usage: scripts/apk-parity.sh UNSIGNED.apk SIGNED.apk
#
# 1. Dependency-free guard: every zip entry has the same local-header offset in
#    both APKs (a re-aligned signed APK moves entries, which breaks check 2).
# 2. F-Droid's own check: apksigcopier copies the signature from the signed APK
#    onto the unsigned one and apksigner must verify the result.
# Exit 2: bad usage. Exit 1: a check failed (or a tool is missing).
set -euo pipefail

# Pinned so a release does not depend on whatever PyPI serves that day.
apksigcopier_version=1.1.1

fail() { echo "apk-parity: $*" >&2; exit 1; }

if [ $# -ne 2 ] || [ ! -f "$1" ] || [ ! -f "$2" ]; then
    echo "usage: $0 UNSIGNED.apk SIGNED.apk" >&2
    exit 2
fi
unsigned=$1
signed=$2

# apksigcopier shells out to `apksigner`; put the newest SDK build-tools on PATH.
if ! command -v apksigner >/dev/null; then
    sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -z "$sdk" ] && [ -f "$(dirname "$0")/../local.properties" ]; then
        sdk=$(sed -n 's/^sdk\.dir=//p' "$(dirname "$0")/../local.properties" | head -1)
    fi
    [ -n "$sdk" ] && [ -d "$sdk/build-tools" ] || fail "Android SDK not found (set ANDROID_HOME)"
    while IFS= read -r dir; do
        if [ -x "$dir/apksigner" ]; then
            PATH="$dir:$PATH"
            break
        fi
    done < <(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sed 's#/$##' | sort -V -r)
fi
command -v apksigner >/dev/null || fail "apksigner not found"
command -v pipx >/dev/null || fail "pipx is required (brew install pipx)"

# Offsets first: it is instant and names the likely cause.
python3 -I - "$unsigned" "$signed" <<'PY' || fail "zip entry offsets differ from the unsigned APK (was apksigner run without --alignment-preserved?)"
import sys, zipfile
a, b = (zipfile.ZipFile(p) for p in sys.argv[1:3])
ai = {i.filename: i.header_offset for i in a.infolist()}
bi = {i.filename: i.header_offset for i in b.infolist()}
if ai != bi:
    names = sorted(set(ai) ^ set(bi)) or sorted(n for n in ai if ai[n] != bi[n])
    print(f"{len(names)} of {len(ai)} entries differ (entries or offsets), e.g. {names[:3]}", file=sys.stderr)
    sys.exit(1)
PY
echo "entry offsets match"

# Exactly what F-Droid runs (signed APK first, unsigned second).
pipx run --spec "apksigcopier==$apksigcopier_version" apksigcopier compare --unsigned "$signed" "$unsigned" \
    || fail "apksigcopier compare --unsigned failed: F-Droid could not verify this APK against the unsigned build"
echo "apksigcopier $apksigcopier_version: the signature copies onto the unsigned APK and verifies"
