#!/usr/bin/env bash
# Copyright (C) 2026 Stephen de la Vega
# SPDX-License-Identifier: GPL-3.0-or-later
# Lists tracked *.kt *.kts *.rs *.sh files (outside app/src/main/assets/)
# that lack the copyright or SPDX header line. Exit 1 if any are listed.
set -u
cd "$(dirname "$0")/.." || exit 2
COPY='Copyright (C) 2026 Stephen de la Vega'
SPDX='SPDX-License-Identifier: GPL-3.0-or-later'
bad=0
while IFS= read -r f; do
  [ -f "$f" ] || continue
  if ! head -n 5 "$f" | grep -qF "$COPY" || ! head -n 5 "$f" | grep -qF "$SPDX"; then
    echo "$f"
    bad=1
  fi
done < <(git ls-files '*.kt' '*.kts' '*.rs' '*.sh' | grep -v '^app/src/main/assets/')
exit $bad
