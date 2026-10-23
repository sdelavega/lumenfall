#!/usr/bin/env bash
# Copyright (C) 2026 Stephen de la Vega
# SPDX-License-Identifier: GPL-3.0-or-later
# Vendors the four ÆtherDesk wallpapers from a pinned upstream commit and
# rewrites wallpapers.lock. Usage: AETHERDESK_DIR=<checkout> scripts/sync-aetherdesk-wallpapers.sh <ref>
# AETHERDESK_DIR is only where this script reads from; it is never recorded.
set -euo pipefail

UPSTREAM_URL="https://github.com/sdelavega/AetherDesk"
NAMES=(WeatherAether GradientWave MatrixRain NeonCity)

if [ $# -ne 1 ]; then
    echo "usage: AETHERDESK_DIR=<AetherDesk checkout> $0 <ref>" >&2
    exit 2
fi
if [ -z "${AETHERDESK_DIR:-}" ]; then
    echo "error: AETHERDESK_DIR is not set (path to an AetherDesk git checkout)" >&2
    exit 2
fi
ref="$1"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dest="$root/app/src/main/assets/wallpapers"

commit="$(git -C "$AETHERDESK_DIR" rev-parse --verify "${ref}^{commit}")"
case "$commit" in
    *[!0-9a-f]* | "") echo "error: unexpected commit id '$commit'" >&2; exit 1 ;;
esac
[ "${#commit}" -eq 40 ] || { echo "error: unexpected commit id '$commit'" >&2; exit 1; }

if command -v shasum >/dev/null 2>&1; then
    sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }
elif command -v sha256sum >/dev/null 2>&1; then
    sha256() { sha256sum "$1" | cut -d' ' -f1; }
else
    echo "error: neither shasum nor sha256sum found" >&2
    exit 1
fi

tmp="$(mktemp -d "${TMPDIR:-/tmp}/aetherdesk-sync.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT

for name in "${NAMES[@]}"; do
    git -C "$AETHERDESK_DIR" archive "$commit" "Resources/Wallpapers/$name" | tar -x -C "$tmp"
    [ -d "$tmp/Resources/Wallpapers/$name" ] || { echo "error: $name missing at $commit" >&2; exit 1; }
done

for name in "${NAMES[@]}"; do
    rm -rf "${dest:?}/$name"
    mv "$tmp/Resources/Wallpapers/$name" "$dest/$name"
    printf 'Vendored from ÆtherDesk at %s. Edit upstream, then run `just sync-wallpapers <ref>`.\n' \
        "$commit" > "$dest/$name/UPSTREAM.md"
done

{
    echo "upstream $UPSTREAM_URL"
    echo "commit $commit"
    (
        cd "$dest"
        for name in "${NAMES[@]}"; do find "$name" -type f ! -name .DS_Store; done | LC_ALL=C sort |
            while IFS= read -r path; do
                printf '%s  %s\n' "$(sha256 "$path")" "$path"
            done
    )
} > "$dest/wallpapers.lock"

echo "Pinned ÆtherDesk commit $commit ($ref)"
