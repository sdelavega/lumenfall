#!/usr/bin/env bash
# Copyright (C) 2026 Stephen de la Vega
# SPDX-License-Identifier: GPL-3.0-or-later
# Builds the unsigned `repro` APK from two copies of the tracked tree in
# different directories and fails unless the APKs are byte-identical.
# The keystore is never copied, so signing material is never touched.
set -euo pipefail
cd "$(dirname "$0")/.."
root=$(pwd)

work=$(mktemp -d "${TMPDIR:-/tmp}/lumenfall-repro.XXXXXX")
trap 'rm -rf "$work"' EXIT

build_copy() {
    local dir=$1
    mkdir -p "$dir"
    git ls-files -z | rsync -a --from0 --files-from=- "$root/" "$dir/"
    # Gitignored, holds only sdk.dir.
    if [ -f "$root/local.properties" ]; then
        cp "$root/local.properties" "$dir/local.properties"
    fi
    (cd "$dir" && ./gradlew :app:assembleRepro --console=plain \
        --project-cache-dir "$dir/.gradle-project-cache" --no-build-cache)
}

build_copy "$work/a/lumenfall"
build_copy "$work/b/lumenfall"

apk_a=("$work"/a/lumenfall/app/build/outputs/apk/repro/*.apk)
apk_b=("$work"/b/lumenfall/app/build/outputs/apk/repro/*.apk)
if [ ! -f "${apk_a[0]}" ] || [ ! -f "${apk_b[0]}" ]; then
    echo "repro-check: no APK produced" >&2
    exit 1
fi

if cmp "${apk_a[0]}" "${apk_b[0]}"; then
    sha=$(shasum -a 256 "${apk_a[0]}" | cut -d' ' -f1)
    echo "repro-check: identical ($sha)"
    # In CI, surface the hash on the run page so it can be compared with a local
    # (macOS) build's before tagging: cross-host reproducibility is not otherwise checked.
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
        echo "repro APK sha256: \`$sha\`" >> "$GITHUB_STEP_SUMMARY"
    fi
    exit 0
fi

echo "repro-check: APKs differ; first differing entry:" >&2
mkdir -p "$work/ua" "$work/ub"
unzip -q "${apk_a[0]}" -d "$work/ua"
unzip -q "${apk_b[0]}" -d "$work/ub"
diff -rq "$work/ua" "$work/ub" | head -1 | sed "s#$work/##g" >&2 || true
exit 1
