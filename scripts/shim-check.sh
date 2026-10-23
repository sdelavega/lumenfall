#!/usr/bin/env bash
# Copyright (C) 2026 Stephen de la Vega
# SPDX-License-Identifier: GPL-3.0-or-later
# Syntax-checks the bridge shim asset with node when node is available.
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v node >/dev/null 2>&1; then
  echo "node not found, skipped"
  exit 0
fi
node --check app/src/main/assets/bridge/aetherdesk-shim.js
echo "shim OK"
