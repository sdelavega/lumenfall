#!/usr/bin/env bash
# Copyright (C) 2026 Stephen de la Vega
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Usage: scripts/bundle-version.sh X.Y.Z          set versionName/versionCode/crate version
#        scripts/bundle-version.sh --check X.Y.Z  exit 0 only if all three already match
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
gradle="$root/app/build.gradle.kts"
cargo_toml="$root/core-rust/Cargo.toml"

check=0
if [[ "${1:-}" == "--check" ]]; then
  check=1
  shift
fi

version="${1:-}"
if [[ $# -ne 1 || ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "usage: $0 [--check] X.Y.Z" >&2
  exit 2
fi

IFS=. read -r major minor patch <<<"$version"
code=$((major * 10000 + minor * 100 + patch))

if [[ $check -eq 1 ]]; then
  grep -q "^ *versionName = \"$version\"\$" "$gradle" &&
    grep -q "^ *versionCode = $code\$" "$gradle" &&
    grep -q "^version = \"$version\"\$" "$cargo_toml"
  exit $?
fi

sed -i.bak -E "s/^( *versionName = )\"[^\"]*\"\$/\1\"$version\"/; s/^( *versionCode = )[0-9]+\$/\1$code/" "$gradle"
sed -i.bak -E "/^\[package\]/,/^\[/ s/^version = \"[^\"]*\"\$/version = \"$version\"/" "$cargo_toml"
rm -f "$gradle.bak" "$cargo_toml.bak"

# Refresh the lock entry; if the lock already matches, --offline may fail harmlessly.
(cd "$root" && "${CARGO:-cargo}" update -p lumenfall-core --offline >/dev/null 2>&1) || true
grep -A1 '^name = "lumenfall-core"$' "$root/Cargo.lock" | grep -q "^version = \"$version\"\$" || {
  echo "Cargo.lock does not carry lumenfall-core $version" >&2
  exit 1
}
