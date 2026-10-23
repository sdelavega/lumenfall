# Developer entry points. This file is an index: each recipe is one command or
# a call to a script in scripts/, so a recipe and a CI step cannot drift apart
# without one of them visibly changing.
#
# rust-toolchain.toml pins the compiler and the Android target, which only
# rustup's cargo/rustc honour; a Homebrew toolchain ahead of them on PATH
# ignores both (and cargo then finds Homebrew's rustc on PATH too). So export
# CARGO and RUSTC: the caller's value if set, else rustup's shim if present,
# else plain `cargo`/`rustc` from PATH. Gradle reads the same variables, and
# PATH itself is not modified.

rustup_bin := env_var('HOME') / ".cargo/bin"
export CARGO := env_var_or_default('CARGO', if path_exists(rustup_bin / "cargo") == "true" { rustup_bin / "cargo" } else { "cargo" })
export RUSTC := env_var_or_default('RUSTC', if path_exists(rustup_bin / "rustc") == "true" { rustup_bin / "rustc" } else { "rustc" })

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

# Release APK (signed with the release key if keystore.properties exists, else the debug key).
assemble-release:
    ./gradlew :app:assembleRelease --console=plain

# Reject developer home paths in tracked files and in the shipped native library.
no-home-paths:
    scripts/no-home-paths.sh app/build/rustJniLibs/arm64-v8a/liblumenfall_core.so

# Every owned source file carries the copyright and SPDX header.
header:
    scripts/check-headers.sh

# Syntax-check the bridge shim asset (skipped without node).
shim-check:
    scripts/shim-check.sh

# Build the unsigned repro APK from two copies of the tree and compare them bytewise.
repro-check:
    scripts/repro-check.sh
