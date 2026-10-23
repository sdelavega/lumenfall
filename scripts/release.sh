#!/usr/bin/env bash
# Copyright (C) 2026 Stephen de la Vega
# SPDX-License-Identifier: GPL-3.0-or-later
# Builds, verifies and (with --publish, after a typed confirmation) publishes a
# release. Usage: scripts/release.sh X.Y.Z [--publish]
#
# Gradle does the signing from the gitignored keystore configuration; this
# script never reads it, only inspects the finished APK's certificate.
# Exit 2: bad usage. Exit 1: a preflight or verification failure.
set -euo pipefail
cd "$(dirname "$0")/.."

fail() { echo "release: $*" >&2; exit 1; }

if [ $# -lt 1 ] || ! [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "usage: $0 X.Y.Z [--publish]" >&2
    exit 2
fi
version=$1
shift
publish=0
for flag in "$@"; do
    case "$flag" in
        --publish) publish=1 ;;
        *) echo "release: unknown option: $flag" >&2; exit 2 ;;
    esac
done

tag="v$version"
notes="docs/releases/$version.md"
cert_file=docs/signing-cert.sha256

# 1. Preflight.
[ "$(git branch --show-current)" = main ] || fail "not on main"
[ -z "$(git status --porcelain)" ] || fail "working tree is not clean"
git fetch --quiet origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || fail "HEAD is not origin/main"
scripts/bundle-version.sh --check "$version" || fail "versionName/versionCode/crate version do not match $version"
[ -f "$notes" ] || fail "missing release notes $notes"
if git rev-parse --verify --quiet "refs/tags/$tag" >/dev/null; then
    fail "tag $tag already exists locally"
fi
if [ -n "$(git ls-remote --tags origin "refs/tags/$tag")" ]; then
    fail "tag $tag already exists on origin"
fi
[ -f "$cert_file" ] || fail "missing $cert_file (run: just signing-fingerprint)"

# 2. Gate.
just gate

# 3. Release build.
./gradlew :app:assembleRelease --console=plain
apks=(app/build/outputs/apk/release/*.apk)
[ ${#apks[@]} -eq 1 ] && [ -f "${apks[0]}" ] || fail "expected exactly one release APK"
built=${apks[0]}

# 4. Signing certificate must be the pinned release identity.
actual=$(scripts/apk-cert-sha256.sh "$built")
expected=$(awk 'NF { print tolower($1); exit }' "$cert_file")
[ "$actual" = "$expected" ] || fail "APK is signed with $actual, expected $expected"

# 5. Artifacts.
mkdir -p dist
apk="dist/Lumenfall-$version.apk"
cp "$built" "$apk"
(cd dist && shasum -a 256 "Lumenfall-$version.apk" > "Lumenfall-$version.apk.sha256")
echo "built $apk"
cat "$apk.sha256"

[ "$publish" -eq 1 ] || { echo "not publishing (pass --publish to tag and release)"; exit 0; }

# 6. Publish, only after the exact version is typed.
echo "tag:       $tag"
echo "checksum:  $(cut -d' ' -f1 "$apk.sha256")"
echo "notes:     $notes"
printf 'Type %s to publish: ' "$version"
read -r answer
[ "$answer" = "$version" ] || fail "confirmation did not match; nothing published"

git tag -a "$tag" -m "Lumenfall $version"
git push origin "$tag"
gh release create "$tag" "$apk" "$apk.sha256" --title "Lumenfall $version" --notes-file "$notes"
