#!/usr/bin/env bash
# Copyright (C) 2026 Stephen de la Vega
# SPDX-License-Identifier: GPL-3.0-or-later
# Prints the signing certificate's SHA-256 (lowercase hex) of an APK, as
# reported by `apksigner verify --print-certs`. Reads only the APK; never any
# keystore. Usage: scripts/apk-cert-sha256.sh path/to/app.apk
set -euo pipefail

if [ $# -ne 1 ] || [ ! -f "$1" ]; then
    echo "usage: $0 APK" >&2
    exit 2
fi

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] && [ -f "$(dirname "$0")/../local.properties" ]; then
    sdk=$(sed -n 's/^sdk\.dir=//p' "$(dirname "$0")/../local.properties" | head -1)
fi
if [ -z "$sdk" ] || [ ! -d "$sdk/build-tools" ]; then
    echo "error: Android SDK not found (set ANDROID_HOME)" >&2
    exit 1
fi

# Highest installed build-tools version that has apksigner.
apksigner=
while IFS= read -r dir; do
    if [ -x "$dir/apksigner" ]; then
        apksigner="$dir/apksigner"
        break
    fi
done < <(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sed 's#/$##' | sort -V -r)
if [ -z "$apksigner" ]; then
    echo "error: apksigner not found under $sdk/build-tools" >&2
    exit 1
fi

out=$("$apksigner" verify --print-certs "$1")
digests=$(printf '%s\n' "$out" | sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p')
if [ "$(printf '%s\n' "$digests" | grep -c .)" -ne 1 ]; then
    echo "error: expected exactly one signer certificate in $1" >&2
    exit 1
fi
printf '%s\n' "$digests" | tr 'A-F' 'a-f'
