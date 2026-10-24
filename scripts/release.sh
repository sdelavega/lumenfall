#!/usr/bin/env bash
# Copyright (C) 2026 Stephen de la Vega
# SPDX-License-Identifier: GPL-3.0-or-later
# Signs CI's Linux release build and (with --publish, after a typed confirmation)
# publishes it.
#   scripts/release.sh X.Y.Z [--publish]            new release (tags and publishes)
#   scripts/release.sh --resign X.Y.Z [--publish]   re-sign an already published release
#
# The shipped APK is the unsigned release-apk artifact of the successful CI run for
# the released commit, signed locally by the Gradle task signPrebuiltRelease. (macOS
# and Linux NDK packages produce different native libraries, and F-Droid builds on
# Linux, so the Linux build is the canonical one.) Gradle reads the gitignored
# keystore configuration; this script never does, it only inspects the finished APK.
# Exit 2: bad usage. Exit 1: a preflight or verification failure.
set -euo pipefail
cd "$(dirname "$0")/.."

fail() { echo "release: $*" >&2; exit 1; }
usage() { echo "usage: $0 [--resign] X.Y.Z [--publish]" >&2; exit 2; }

resign=0
if [ "${1:-}" = --resign ]; then
    resign=1
    shift
fi
if [ $# -lt 1 ] || ! [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    usage
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
command -v gh >/dev/null || fail "GitHub CLI (gh) is required"
[ -f "$cert_file" ] || fail "missing $cert_file (run: just signing-fingerprint)"

# The successful, finished ci.yml push run whose head is exactly $1: prints its id.
ci_run_for() {
    local sha=$1 info id status conclusion
    info=$(gh run list --workflow ci.yml --event push --commit "$sha" --limit 20 \
        --json databaseId,status,conclusion,headSha \
        --jq "[.[] | select(.headSha == \"$sha\")] | first | \"\(.databaseId) \(.status) \(.conclusion)\"")
    [ -n "$info" ] && [ "$info" != "null null null" ] || fail "no CI run (ci.yml, push) found for $sha"
    read -r id status conclusion <<<"$info"
    [ "$status" = completed ] || fail "CI run $id for $sha is $status, not finished"
    [ "$conclusion" = success ] || fail "CI run $id for $sha concluded $conclusion"
    echo "$id"
}

if [ "$resign" -eq 0 ]; then
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

    # 2. Gate.
    just gate

    # 3. CI's Linux build for exactly this commit: it must have finished and succeeded.
    source_sha=$(git rev-parse HEAD)
else
    # Re-sign: the tag and the GitHub release must already exist; nothing is tagged,
    # and no gate runs (this signs the CI build of an already released commit).
    tag_refs=$(git ls-remote --tags origin "refs/tags/$tag" "refs/tags/$tag^{}")
    [ -n "$tag_refs" ] || fail "tag $tag does not exist on origin"
    # An annotated tag lists its commit on the peeled (^{}) line; a lightweight tag has one line.
    source_sha=$(printf '%s\n' "$tag_refs" | awk '/\^\{\}$/ { print $1; found = 1 } END { exit !found }') \
        || source_sha=$(printf '%s\n' "$tag_refs" | awk 'NR == 1 { print $1 }')
    [[ "$source_sha" =~ ^[0-9a-f]{40}$ ]] || fail "could not resolve the commit of $tag"
    gh release view "$tag" >/dev/null 2>&1 || fail "no GitHub release $tag (use: just release $version)"
    echo "$tag is $source_sha"
fi

run_id=$(ci_run_for "$source_sha")
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

# 4. Sign it (Gradle reads the keystore; nothing is rebuilt). signPrebuiltRelease
# passes --alignment-preserved, so every zip entry stays where CI put it.
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
# F-Droid parity: the signature must copy onto CI's unsigned APK and verify
# (apksigcopier compare --unsigned, as F-Droid runs it), and every zip entry must
# sit at the same offset. This subsumes the old entry-listing/CRC comparison: a
# verifying copy means the signed APK's bytes outside the signing block are
# identical to the unsigned APK's, so the entries and their CRCs are too.
scripts/apk-parity.sh "$unsigned" "$apk" || fail "signed APK does not match CI's unsigned APK; F-Droid could not verify it"

# 6. Artifacts.
(cd dist && shasum -a 256 "Lumenfall-$version.apk" > "Lumenfall-$version.apk.sha256")
echo "built $apk (from CI run $run_id)"
cat "$apk.sha256"

[ "$publish" -eq 1 ] || { echo "not publishing (pass --publish)"; exit 0; }

# 7. Publish, only after the exact version is typed.
if [ "$resign" -eq 1 ]; then
    old_sha=$(gh release download "$tag" -p "Lumenfall-$version.apk" -O - | shasum -a 256 | cut -d' ' -f1)
    new_sha=$(cut -d' ' -f1 "$apk.sha256")
    echo "release:    $(gh release view "$tag" --json url --jq .url)"
    echo "old sha256: $old_sha"
    echo "new sha256: $new_sha"
    echo "This replaces the published APK and checksum assets of $tag (no tag change)."
    printf 'Type %s to replace them: ' "$version"
    read -r answer
    [ "$answer" = "$version" ] || fail "confirmation did not match; nothing uploaded"
    gh release upload "$tag" "$apk" "$apk.sha256" --clobber
    exit 0
fi

echo "tag:       $tag"
echo "checksum:  $(cut -d' ' -f1 "$apk.sha256")"
echo "notes:     $notes"
printf 'Type %s to publish: ' "$version"
read -r answer
[ "$answer" = "$version" ] || fail "confirmation did not match; nothing published"

git tag -a "$tag" -m "Lumenfall $version"
git push origin "$tag"
gh release create "$tag" "$apk" "$apk.sha256" --title "Lumenfall $version" --notes-file "$notes"
