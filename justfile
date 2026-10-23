# Developer entry points. This file is an index: each recipe is one command or
# a call to a script in scripts/, so a recipe and a CI step cannot drift apart
# without one of them visibly changing.
#
# rust-toolchain.toml pins the compiler and the Android target, which only
# rustup's cargo/rustc honour; a Homebrew toolchain ahead of them on PATH
# ignores both (and cargo then finds Homebrew's rustc on PATH too). So export
# CARGO, RUSTC and RUSTDOC (cargo test runs doctests with the last): the caller's value if set, else rustup's shim if present,
# else plain `cargo`/`rustc`/`rustdoc` from PATH. Gradle reads the same variables, and
# PATH itself is not modified.

rustup_bin := env_var('HOME') / ".cargo/bin"
export CARGO := env_var_or_default('CARGO', if path_exists(rustup_bin / "cargo") == "true" { rustup_bin / "cargo" } else { "cargo" })
export RUSTC := env_var_or_default('RUSTC', if path_exists(rustup_bin / "rustc") == "true" { rustup_bin / "rustc" } else { "rustc" })
export RUSTDOC := env_var_or_default('RUSTDOC', if path_exists(rustup_bin / "rustdoc") == "true" { rustup_bin / "rustdoc" } else { "rustdoc" })

set shell := ["bash", "-euo", "pipefail", "-c"]

# List the recipes.
default:
    @just --list

# Rust core unit tests.
rust-test:
    "$CARGO" test --locked

# Kotlin JVM unit tests.
kotlin-test:
    ./gradlew :app:testDebugUnitTest --console=plain

# Release APK (signed with the release key if keystore.properties exists, else the debug key),
# then reject developer home paths in the freshly built native library and anywhere inside
# the APK (native library and dex included). The build must have produced the library.
assemble-release:
    ./gradlew :app:assembleRelease --console=plain
    scripts/no-home-paths.sh app/build/rustJniLibs/arm64-v8a/liblumenfall_core.so app/build/outputs/apk/release/*.apk

# The debug-only SmokeReceiver must not be in the release merged manifest (run after assemble-release).
smoke-manifest-check:
    scripts/smoke-manifest-check.sh

# Device smoke run against the debug build: just smoke [--soak-minutes N] [--skip-install] [--dry-run]
smoke *flags:
    scripts/smoke.sh {{flags}}

# Reject developer home paths in tracked files. Build output is scanned by assemble-release,
# so stale artifacts from an earlier build cannot fail this check.
no-home-paths:
    scripts/no-home-paths.sh

# Every owned source file carries the copyright and SPDX header.
header:
    scripts/check-headers.sh

# Syntax-check the bridge shim asset (skipped without node).
shim-check:
    scripts/shim-check.sh

# Build the unsigned repro APK from two copies of the tree and compare them bytewise.
repro-check:
    scripts/repro-check.sh

# Re-vendor the ÆtherDesk wallpapers at <ref> (needs AETHERDESK_DIR, an AetherDesk checkout).
sync-wallpapers ref:
    @if [ -z "${AETHERDESK_DIR:-}" ]; then echo "error: set AETHERDESK_DIR to an AetherDesk git checkout" >&2; exit 2; fi
    scripts/sync-aetherdesk-wallpapers.sh "{{ref}}"

# Check the vendored wallpapers against wallpapers.lock.
wallpapers-lock:
    ./gradlew :app:testDebugUnitTest --tests '*VendoredWallpapersLockTest*' --console=plain

# Regenerate THIRD_PARTY.md and the in-app licences asset from wallpapers + Cargo.lock.
licenses:
    scripts/gen-licenses.sh

# Android lint on both variants; warnings are errors (see app/build.gradle.kts).
lint:
    ./gradlew :app:lintDebug :app:lintRelease --console=plain

# Licence allow-list (deny.toml) and RustSec advisories for the Rust dependencies.
deny:
    "$CARGO" deny check licenses advisories

# Everything CI and a release require, in order.
gate: no-home-paths header rust-test deny kotlin-test lint shim-check wallpapers-lock assemble-release smoke-manifest-check repro-check

# Build the release APK and print its signing certificate's SHA-256, for docs/signing-cert.sha256.
signing-fingerprint:
    ./gradlew :app:assembleRelease --console=plain
    scripts/apk-cert-sha256.sh app/build/outputs/apk/release/*.apk

# Build, verify and optionally publish a release: just release 1.0.0 [--publish]
release version *flags:
    scripts/release.sh {{version}} {{flags}}
