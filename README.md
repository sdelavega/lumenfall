# Lumenfall

Lumenfall is an Android live wallpaper host for Lively and ÆtherDesk wallpaper
bundles. A thin Kotlin shell runs on Android's native `WallpaperService`; a Rust
core parses, validates and screens the bundles. It is free software, with no
analytics and no accounts.

Eight wallpapers are included. You can import more from a folder or a zip, and
each one has its own settings screen with a live preview.

Screenshots are coming; they will live under
`fastlane/metadata/android/en-US/images/phoneScreenshots/`.

## Install

Requires Android 13 (API 33) or newer on an arm64 device.

**GitHub:** download `Lumenfall-X.Y.Z.apk` and `Lumenfall-X.Y.Z.apk.sha256` from
the [latest release](https://github.com/sdelavega/lumenfall/releases/latest),
check the download, then open the APK and allow installation when Android asks:

```sh
shasum -a 256 -c Lumenfall-X.Y.Z.apk.sha256
```

The release is signed with the maintainer's key. Its certificate SHA-256 is in
[`docs/signing-cert.sha256`](docs/signing-cert.sha256) so you can compare it with
what Android or `apksigner verify --print-certs` reports.

**Obtainium:** add `https://github.com/sdelavega/lumenfall` as a source. It
follows GitHub releases and installs updates for you. Lumenfall has no in-app
update check.

**F-Droid:** a recipe is prepared in
[`docs/fdroid/`](docs/fdroid/com.sdelavega.lumenfall.yml); once the app is
listed it will be available from F-Droid. Builds are reproducible, so F-Droid
can ship the same APK, with the developer's signature, as GitHub. If that ever
stops being possible, F-Droid and GitHub installs will use different keys and
will not update each other.

Then open Lumenfall and choose **Set wallpaper**.

## Wallpaper bundles

Lumenfall reads Lively bundles (`LivelyInfo.json`, `LivelyProperties.json`) and
ÆtherDesk bundles. Web wallpapers see the same JavaScript bridge as on
ÆtherDesk: `window.aetherDesk.*`, `livelyPropertyListener` and
`onPropertyChange`, so wallpapers written for either run unchanged. Web, video
and image wallpapers are supported; other Lively types are rejected at import
with a reason.

Four bundled wallpapers (Weather Aether, Gradient Wave, Matrix Rain, Neon City)
are vendored from ÆtherDesk at a pinned commit; see
[`THIRD_PARTY.md`](THIRD_PARTY.md) for every bundled wallpaper's author and
licence.

## Privacy

- No analytics, no telemetry, no accounts, no ads. Lumenfall itself makes no
  network requests.
- The only network traffic comes from wallpapers you choose to run. The
  external domains a wallpaper has actually contacted are listed in its
  settings screen ("Domains contacted").
- Known ad, analytics and crypto-miner hosts, raw IP addresses, local addresses
  (unless you allow them) and the location services `ipapi.co` and
  `api.bigdatacloud.net` are blocked for every wallpaper.
- Imported bundles are screened when you import them; ones that use trackers,
  miners or other unsafe patterns are rejected.
- Weather Aether contacts only Open-Meteo: `api.open-meteo.com` (forecast) and
  `geocoding-api.open-meteo.com` (city search). Its ipapi.co (IP location) and
  BigDataCloud (place name) calls run only when the host sets the
  `ipGeolocation` capability. Lumenfall never sets it, and its network policy
  blocks both hosts as defence in depth. Location is
  optional, per wallpaper, and either approximate or precise; you can type a
  city instead.
- Cloud backup skips imported bundles and thumbnails.

Report vulnerabilities as described in [`SECURITY.md`](SECURITY.md).

## Build from source

You need rustup (it installs the toolchain pinned in `rust-toolchain.toml`),
the Android SDK with NDK `27.3.13750724`, JDK 17, `cargo-ndk` 4.1.2,
`cargo-deny` and `just`. `node` is optional and enables the bridge-shim
syntax check.

```sh
just gate
```

runs everything CI runs: path and header checks, Rust and Kotlin tests, the
licence/advisory check, lint, the shim check, the vendored-wallpaper lock
check, the release build and a reproducibility check (two clean builds compared
byte for byte). If Homebrew's Rust comes before rustup on your `PATH`, run
builds through `just`, which points Cargo, rustc and rustdoc at rustup's, or put
`~/.cargo/bin` first. A single debug build is `./gradlew :app:assembleDebug`.
Without a `keystore.properties`, release builds are signed with the debug key.

See [`CONTRIBUTING.md`](CONTRIBUTING.md) and [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
Maintainers: [`docs/releasing.md`](docs/releasing.md).

## License

GNU General Public License, version 3 or later. See [LICENSE](LICENSE). Bundled
wallpapers and libraries keep their own licences; see
[THIRD_PARTY.md](THIRD_PARTY.md), also shown in the app under About → Open-source licences.

## Support

Donations are optional, through [GitHub Sponsors](https://github.com/sponsors/sdelavega),
also linked from About in the app.
