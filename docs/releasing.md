# Releasing Lumenfall

Publishing is confirmed separately for every release. A local build publishes
nothing until you pass `--publish` and type the exact version at the prompt.
There is no `--yes` switch and no release workflow in CI; CI only runs the gate.

## One-time setup

On the maintainer's machine:

1. Install rustup (the pinned toolchain in `rust-toolchain.toml` installs on
   first use), JDK 17, the Android SDK with NDK `27.3.13750724`, and
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

   The recipe builds the release APK and prints the certificate's SHA-256 as its
   last line. Put that single lowercase hex line in `docs/signing-cert.sha256`
   (Gradle's output is printed before it, so don't redirect the whole command).
   Review and commit `docs/signing-cert.sha256`. It is public information. The
   release script refuses to publish an APK signed with any other certificate.
   Use the same value for `AllowedAPKSigningKeys` in the F-Droid recipe.

## Releasing

Use a clean `main` equal to `origin/main`. The version is stamped with
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
builds the signed APK, compares its certificate with the committed fingerprint,
and writes `dist/Lumenfall-X.Y.Z.apk` and `dist/Lumenfall-X.Y.Z.apk.sha256`.

To publish:

```sh
just release X.Y.Z --publish
```

After the same checks it shows the tag, checksum and notes and asks you to type
the version. Only then does it create the annotated tag `vX.Y.Z`, push it, and
run `gh release create` with the APK, checksum and notes. The release asset is
named `Lumenfall-X.Y.Z.apk`, which the F-Droid recipe's `Binaries` URL expects.

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

Later releases: F-Droid's `UpdateCheckMode: Tags` notices the new `vX.Y.Z` tag,
and `AutoUpdateMode: Version` adds the build entry automatically. F-Droid builds
from source and, because the build is reproducible, verifies it against the
GitHub APK (`Binaries`) and publishes it with your signature. If a release stops
being reproducible (`just repro-check` fails), fix that before tagging; the
fallback is dropping `Binaries` and `AllowedAPKSigningKeys` so F-Droid signs
with its own key, which makes GitHub and F-Droid installs non-interchangeable
and must be documented in the README.
