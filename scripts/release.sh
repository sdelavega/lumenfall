#!/usr/bin/env bash
# Copyright (C) 2026 Stephen de la Vega
# SPDX-License-Identifier: GPL-3.0-or-later
# Signs CI's Linux release build and (with --publish, after a typed confirmation)
# publishes it. Usage: scripts/release.sh X.Y.Z [--publish]
#
# The shipped APK is the unsigned release-apk artifact of the successful CI run for
# HEAD, signed locally by the Gradle task signPrebuiltRelease. (macOS and Linux NDK
# packages produce different native libraries, and F-Droid builds on Linux, so the
# Linux build is the canonical one.) Gradle reads the gitignored keystore
# configuration; this script never does, it only inspects the finished APK.
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

# 3. CI's Linux build for exactly this commit: it must have finished and succeeded.
command -v gh >/dev/null || fail "GitHub CLI (gh) is required"
head_sha=$(git rev-parse HEAD)
run_info=$(gh run list --workflow ci.yml --event push --commit "$head_sha" --limit 20 \
    --json databaseId,status,conclusion,headSha \
    --jq "[.[] | select(.headSha == \"$head_sha\")] | first | \"\(.databaseId) \(.status) \(.conclusion)\"")
[ -n "$run_info" ] && [ "$run_info" != "null null null" ] || fail "no CI run (ci.yml, push) found for $head_sha"
read -r run_id run_status run_conclusion <<<"$run_info"
[ "$run_status" = completed ] || fail "CI run $run_id for $head_sha is $run_status, not finished"
[ "$run_conclusion" = success ] || fail "CI run $run_id for $head_sha concluded $run_conclusion"
echo "using CI run $run_id"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
gh run download "$run_id" -n release-apk -D "$work"
unsigned="$work/app-release-unsigned.apk"
[ -f "$unsigned" ] || fail "release-apk artifact has no app-release-unsigned.apk"
unsigned_sha=$(shasum -a 256 "$unsigned" | cut -d' ' -f1)
if [ -f "$unsigned.sha256" ]; then
    [ "$unsigned_sha" = "$(awk 'NF { print tolower($1); exit }' "$unsigned.sha256")" ] \
        || fail "downloaded APK does not match CI's checksum file"
fi
echo "CI unsigned APK sha256: $unsigned_sha"

# 4. Sign it (Gradle reads the keystore; nothing is rebuilt).
mkdir -p dist
apk="dist/Lumenfall-$version.apk"
./gradlew :app:signPrebuiltRelease -PunsignedApk="$unsigned" -PsignedApk="$apk" --console=plain

# 5. Verify. The signing certificate must be the pinned release identity.
actual=$(scripts/apk-cert-sha256.sh "$apk")
expected=$(awk 'NF { print tolower($1); exit }' "$cert_file")
[ "$actual" = "$expected" ] || fail "APK is signed with $actual, expected $expected"

# v1 (JAR) signing must be off: its META-INF entries would change the contents.
if unzip -Z1 "$apk" | grep -Eq '^META-INF/.*\.(SF|RSA|DSA|EC)$'; then
    fail "signed APK carries v1 signature files"
fi
# v2/v3 signatures live outside the zip entries, so the entry listing and every
# entry's CRC must match CI's unsigned APK exactly.
entries() { unzip -v "$1" | awk 'NR > 3 && NF >= 8 && $1 ~ /^[0-9]+$/ { print $1, $2, $3, $7, $8 }'; }
[ -n "$(entries "$unsigned")" ] || fail "could not list entries of the unsigned APK"
[ "$(entries "$unsigned")" = "$(entries "$apk")" ] \
    || fail "signed APK contents differ from CI's unsigned APK"

# 6. Artifacts.
(cd dist && shasum -a 256 "Lumenfall-$version.apk" > "Lumenfall-$version.apk.sha256")
echo "built $apk (from CI run $run_id)"
cat "$apk.sha256"

[ "$publish" -eq 1 ] || { echo "not publishing (pass --publish to tag and release)"; exit 0; }

# 7. Publish, only after the exact version is typed.
echo "tag:       $tag"
echo "checksum:  $(cut -d' ' -f1 "$apk.sha256")"
echo "notes:     $notes"
printf 'Type %s to publish: ' "$version"
read -r answer
[ "$answer" = "$version" ] || fail "confirmation did not match; nothing published"

git tag -a "$tag" -m "Lumenfall $version"
git push origin "$tag"
gh release create "$tag" "$apk" "$apk.sha256" --title "Lumenfall $version" --notes-file "$notes"
