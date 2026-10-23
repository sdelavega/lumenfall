# Contributing

Bug reports, ideas and patches are welcome. Open an
[issue](https://github.com/sdelavega/lumenfall/issues) first for anything larger
than a small fix, so we can agree on the approach.

## Setup

See "Build from source" in [README.md](README.md). Before opening a pull request,
run `just gate`; CI runs the same thing.

## Conventions

- Kotlin stays thin: Android lifecycle, views, permissions and JNI calls.
  Anything about understanding or vetting a wallpaper bundle belongs in the
  Rust core (`core-rust/`). See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
- Every owned `.kt`, `.kts`, `.rs` and `.sh` file starts with
  `Copyright (C) 2026 Stephen de la Vega` and
  `SPDX-License-Identifier: GPL-3.0-or-later` in that file's comment syntax;
  `just header` checks it. Contributions are accepted under the same licence.
- Tests are JVM unit tests (`app/src/test`) and Rust tests. Add or update one
  with each behaviour change.
- The bundle-facing bridge keeps its names (`window.aetherDesk.*`,
  `livelyPropertyListener`, `onPropertyChange`).
- Vendored wallpapers under `app/src/main/assets/wallpapers/` are synced from
  their upstream with `just sync-wallpapers <ref>`; do not edit them by hand
  (`just wallpapers-lock` fails if you do). Fix them upstream.
- No developer home paths in tracked files (`just no-home-paths`).
- Never commit signing keys or `keystore.properties`.
- Network policy changes must keep `ipapi.co` and `api.bigdatacloud.net` blocked.
- Keep third-party notices current with `just licenses` when dependencies or
  bundled wallpapers change.
