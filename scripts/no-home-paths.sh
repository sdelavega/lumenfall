#!/usr/bin/env bash
# Copyright (C) 2026 Stephen de la Vega
# SPDX-License-Identifier: GPL-3.0-or-later
# Fails if tracked text files or the given binaries contain a developer home
# path. An .apk is unpacked and every file inside it is scanned (native
# libraries and dex are compressed in the archive, so the raw APK is useless
# to `strings`). Usage: scripts/no-home-paths.sh [binary-or-apk...]
set -euo pipefail
cd "$(dirname "$0")/.."

fail=0

status=0
hits=$(git grep -nI "/Users/" -- . ':(exclude)docs/superpowers/' ':(exclude)docs/CODE-REVIEW-*' ':(exclude)scripts/no-home-paths.sh') || status=$?
if [ "$status" -eq 0 ]; then
    echo "Tracked files mention /Users/:" >&2
    echo "$hits" >&2
    fail=1
elif [ "$status" -ne 1 ]; then
    echo "git grep failed (status $status)" >&2
    exit "$status"
fi

# Scans stdin (as `strings` would) for a home path; $1 names it in messages.
scan() {
    if strings -a - | grep -n -e "/Users/" -e "/home/" >&2; then
        echo "$1 contains a home path" >&2
        fail=1
    fi
}

for bin in "$@"; do
    if [ ! -f "$bin" ]; then
        echo "No such file: $bin" >&2
        fail=1
        continue
    fi
    case "$bin" in
        *.apk)
            # Stream each entry rather than extracting: nothing touches disk,
            # and entries whose names differ only in case cannot collide.
            while IFS= read -r entry; do
                scan "$bin!$entry" < <(unzip -p "$bin" "$entry")
            done < <(unzip -Z1 "$bin")
            ;;
        *) scan "$bin" < "$bin" ;;
    esac
done

exit "$fail"
