#!/usr/bin/env bash
# Copyright (C) 2026 Stephen de la Vega
# SPDX-License-Identifier: GPL-3.0-or-later
# Fails if tracked text files or the given binaries contain a developer home
# path. Usage: scripts/no-home-paths.sh [binary...]
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

for bin in "$@"; do
    if [ ! -f "$bin" ]; then
        echo "No such file: $bin" >&2
        fail=1
        continue
    fi
    if strings -a "$bin" | grep -n -e "/Users/" -e "/home/" >&2; then
        echo "$bin contains a home path" >&2
        fail=1
    fi
done

exit "$fail"
