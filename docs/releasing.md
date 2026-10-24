# Releasing Lumenfall

Publishing is confirmed separately for every release. A local build publishes
nothing until you pass `--publish` and type the exact version at the prompt.
There is no `--yes` switch and no release workflow in CI; CI only runs the gate
and builds the unsigned release APK.

## How a release is built

The shipped APK is **CI's Linux build, signed locally**. CI's `gate` job builds
the unsigned release APK and uploads it as the `release-apk` artifact (retained
90 days). `scripts/release.sh` downloads the artifact from the successful CI run
for the exact commit being released and signs it with `apksigner` through the
Gradle task `signPrebuiltRelease`, which reads `keystore.properties` itself. Nothing is
rebuilt on the Mac.

Why not build on the Mac: the macOS and Linux NDK packages produce different
native libraries, so a Mac-built APK differs from what F-Droid builds (on Linux)
and could never be reproduced. The Linux CI build is therefore canonical. Because
v2/v3 signatures live in a block outside the zip entries, signing leaves every
entry untouched, and F-Droid's build matches the GitHub APK entry for entry. The
script enforces this: the signed APK must have no v1 signature files, and its
entry listing and per-entry CRCs must equal the unsigned artifact's.

## One-time setup

On the maintainer's machine:

1. Install rustup (the pinned toolchain in `rust-toolchain.toml` installs on
   first use), JDK 17 or newer, the Android SDK with NDK `27.3.13750724`, and
   `cargo install cargo-ndk --locked --version 4.1.2`, `cargo-deny`, `just`.
   `node` is optional (bridge-shim syntax check). Install GitHub CLI and
   authenticate it for the repository. If Homebrew's Rust precedes rustup on
   `PATH`, run builds through `just`, which exports `CARGO`, `RUSTC` and
   `RUSTDOC`, or put `~/.cargo/bin` first.
2. The signing key stays private. Put `release.jks` and `keystore.properties`
   (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) at the repository
   root; both are git-ignored and must never be committed, pasted or logged.
   The keystore is the app's permanent identity: back it up privately, because
   losing it means installed copies can never update.
3. Record the certificate fingerprint once:

   ```sh
   just signing-fingerprint
   ```

   The recipe builds a release APK locally with the release key and prints the certificate's SHA-256 as its
   last line. Put that single lowercase hex line in `docs/signing-cert.sha256`
   (Gradle's output is printed before it, so don't redirect the whole command).
   Review and commit `docs/signing-cert.sha256`. It is public information. The
   release script refuses to publish an APK signed with any other certificate.
   Use the same value for `AllowedAPKSigningKeys` in the F-Droid recipe.

## Releasing

Use a clean `main` equal to `origin/main`, pushed, with its CI run finished and
green (the release script looks it up with `gh run list --commit`). The version is stamped with
`scripts/bundle-version.sh X.Y.Z`, which sets `versionName` in
`app/build.gradle.kts` and the crate version, and derives
`versionCode = major * 10000 + minor * 100 + patch` (each component under 100).
Commit these along with release notes in `docs/releases/X.Y.Z.md`, and add
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` for F-Droid.

Build and verify without publishing:

```sh
just release X.Y.Z
```

This checks the preflight conditions (branch, clean tree, `origin/main`,
versions, notes, tag not taken, `docs/signing-cert.sha256`), runs `just gate`,
finds the successful `ci.yml` push run whose head is this commit (failing if there
is none, it is unfinished or it failed), downloads its `release-apk` artifact,
records the unsigned APK's SHA-256, signs it, compares the certificate with the
committed fingerprint, checks the contents match the unsigned APK, and writes
`dist/Lumenfall-X.Y.Z.apk` and `dist/Lumenfall-X.Y.Z.apk.sha256`.

To publish:

```sh
just release X.Y.Z --publish
```

After the same checks it shows the tag, checksum and notes and asks you to type
the version. Only then does it create the annotated tag `vX.Y.Z`, push it, and
run `gh release create` with the APK, checksum and notes. The release asset is
named `Lumenfall-X.Y.Z.apk`, which the F-Droid recipe's `Binaries` URL expects.

## Building an unsigned release locally

`-Plumenfall.noKeystore` makes Gradle ignore `keystore.properties` even when it
exists, so `./gradlew :app:assembleRelease -Plumenfall.noKeystore` yields an
unsigned `app-release-unsigned.apk` (useful to compare with CI's artifact). With
or without the keystore, a release build is never signed with the debug key.

## Device smoke run

`just smoke [--soak-minutes N] [--skip-install] [--dry-run]` runs
`scripts/smoke.sh` against one attached device. It installs the **debug** build
(the release build has no `SmokeReceiver` and is signed with a different key, so
the script never installs it), screenshots every bundled wallpaper, prompts for
the manual checks, soaks, and scans logcat. Output goes to `smoke/<timestamp>/`.
`just smoke-manifest-check` (part of the gate) proves the release manifest has
no `SmokeReceiver`.

Final release-APK sanity check, by hand: uninstall Lumenfall, install the
release APK, set the wallpaper, and look at it.

## Obtainium

Obtainium users track `https://github.com/sdelavega/lumenfall` and pick up the
APK from each GitHub release. Nothing extra is needed beyond a normal release;
keep the asset name stable and never change the signing key.

## F-Droid

The draft recipe is `docs/fdroid/com.sdelavega.lumenfall.yml`.

First release: replace the `AllowedAPKSigningKeys` placeholder with the value
from `docs/signing-cert.sha256`, copy the recipe to `metadata/com.sdelavega.lumenfall.yml`
in a fork of [fdroiddata](https://gitlab.com/fdroid/fdroiddata), run
`fdroid lint com.sdelavega.lumenfall` and a test build, and open the merge
request from your own GitLab account.

The recipe builds `liblumenfall_core.so` itself with `cargo ndk` (same command and
`RUSTFLAGS` as the `cargoNdkBuild` Gradle task, since fdroidserver deletes
`gradlew` and Gradle's history before building) and passes
`gradleprops: [lumenfall.prebuiltJni]`. That `-Plumenfall.prebuiltJni` property
makes `cargoNdkBuild` skip itself, and fail with a clear message if
`app/build/rustJniLibs/arm64-v8a/liblumenfall_core.so` is missing. Normal builds
do not set it.

The recipe's `Binaries` URL points at the GitHub APK, which is CI's own Linux
build plus a signature, so F-Droid's Linux build reproduces it. There is no
macOS-versus-Linux hash comparison to do before tagging any more.

Later releases: F-Droid's `UpdateCheckMode: Tags` notices the new `vX.Y.Z` tag,
and `AutoUpdateMode: Version` adds the build entry automatically. F-Droid builds
from source and, because the build is reproducible, verifies it against the
GitHub APK (`Binaries`) and publishes it with your signature. If a release stops
being reproducible (`just repro-check` fails), fix that before tagging; the
fallback is dropping `Binaries` and `AllowedAPKSigningKeys` so F-Droid signs
with its own key, which makes GitHub and F-Droid installs non-interchangeable
and must be documented in the README.
