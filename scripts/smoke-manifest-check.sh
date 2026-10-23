#!/usr/bin/env bash
# Copyright (C) 2026 Stephen de la Vega
# SPDX-License-Identifier: GPL-3.0-or-later
# Fails if the release merged manifest mentions the debug-only SmokeReceiver.
# Usage: scripts/smoke-manifest-check.sh [manifest...]  (default: the release
# merged manifest, so run it after assembleRelease)
set -euo pipefail
cd "$(dirname "$0")/.."

if [ "$#" -gt 0 ]; then
    manifests=("$@")
else
    manifests=()
    while IFS= read -r f; do manifests+=("$f"); done < <(find app/build/intermediates/merged_manifests/release -name AndroidManifest.xml 2>/dev/null)
fi
if [ "${#manifests[@]}" -eq 0 ]; then
    echo "error: no release merged manifest found; run assembleRelease first" >&2
    exit 2
fi
for m in "${manifests[@]}"; do
    if grep -q -e SmokeReceiver -e SMOKE_SET_ACTIVE "$m"; then
        echo "FAIL: $m contains the debug-only SmokeReceiver" >&2
        exit 1
    fi
    echo "ok: $m has no SmokeReceiver"
done
