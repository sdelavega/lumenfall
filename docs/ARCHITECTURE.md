# Architecture Notes

Lumenfall is split into these layers:

1. `core-rust`: normalized Lively models, parsing, validation, and portable
   policy.
2. `app/.../core`: JNI loading and narrow Kotlin facade over Rust.
3. `app/.../service`: Android `WallpaperService.Engine` lifecycle.
4. `app/.../bridge`: JavaScript bridge exposed to web wallpapers.
5. `app/.../render`, `web`, `video`, `image`: the `WallpaperRenderer` interface
   and its per-kind implementations — web (VirtualDisplay + Presentation + WebView,
   asset loader, document-start bridge injection), video (ExoPlayer on the
   surface, looping + muted), image (Canvas center-crop fill). The Engine forwards
   its surface/visibility lifecycle to whichever renderer matches the active kind.
6. `app/.../storage`: `BundleStore` — on-disk bundle dir (`filesDir/bundles/<id>/`,
   each with `bundle.json` metadata, `properties.json` (parsed LivelyProperties
   schema), and `overrides.json` (user-set values)) plus the active-bundle pointer.
   `BundleProperty` models one configurable property.
7. `app/.../importer`: `BundleImporter` — zip-slip-guarded extraction of a Lively
   `.zip`, then `LivelyInfo.json` (and `LivelyProperties.json` when present) parsed
   via the Rust core into bundle metadata + property schema.
8. `app/.../ui`: `MainActivity` — the bundle library/picker (SAF import, list,
   select-active which opens the live-wallpaper preview); `BundleSettingsActivity`
   — per-bundle property controls (slider/color/dropdown/checkbox/...).
9. `app/.../render/PropertyBus`: process-local fan-out of live property changes
   from the settings UI to any concurrently-running renderer.

Bundle flow: import zip -> extract to `bundles/<id>/` with zip-slip protection
-> locate `LivelyInfo.json` -> send the metadata base path plus a normalized file
inventory to the Rust validator -> persist `BundleMeta` only when Rust returns an
admissible web/video/image bundle -> picker sets active -> the engine loads the
active bundle. Web bundles load from
`https://appassets.androidplatform.net/bundles/<id>/<entry>` (served by
`InternalStoragePathHandler`); video/image read the file directly from
`bundles/<id>/<entry>`. Unsupported Lively types are rejected at import with a
clear reason. A selected bundle takes effect when the engine is (re)created (the
picker reopens the wallpaper preview to do this); live in-place swap is a later
refinement.

Validation lives in Rust (`bundle_validation.rs`) so it is testable without
Android. The current corpus covers declared web entries, nested bundles with
missing `FileName`, video, GIF/WebP image entries, path traversal attempts,
unsupported Lively enum values, and missing entries. Current limits are 2,000
files, 512 MB total bundle size, and 256 MB per file; Kotlin still does the
transactional extraction and supplies file paths/sizes to Rust.

Properties / the bridge contract: a web bundle's effective values (schema defaults
overlaid with overrides) are baked into `window.aetherDesk.properties` at
document-start, so the bundle reads its config before its first script. The
settings UI persists each change as an override and calls `PropertyBus.notify`;
the web renderer, if registered, pushes it into the running bundle via
`_setProperty` — which updates `properties`, fires `onPropertyChange`, and also
calls Lively's `livelyPropertyListener` for compatibility. NOTE: Android tears the
wallpaper engine down under a fullscreen settings Activity, so in the usual
adjust-then-return flow the change actually lands via injection on the next engine
creation; the live push only has an audience when an engine is concurrently
visible (split-screen, etc.).

Practical limits (DONE, 2026-07-03 — ported from ÆtherDesk so bundles behave
identically on both hosts):

- **Import screening** (`core-rust/bundle_screening.rs`, heuristics from
  ÆtherDesk's WallpaperValidator): the importer concatenates a web bundle's
  .js/.html source (1 MB/file, 8 MB total caps) and Rust classifies it.
  Trackers/ad domains, crypto miners, ServiceWorker/SharedWorker,
  `window.open`, `while(true)`, and >10 network call sites reject the import
  with a reason; network use, eval-family, WebGL, and heavy-GPU patterns
  downgrade to "limited" (persisted as `screening.json`).
- **FPS cap** (web shim): `requestAnimationFrame` is throttled to
  `aetherDesk.system.fpsCap` — default 30, clamped 15–60 — sleeping between
  frames via setTimeout, and drops frames entirely while hidden. Measured on
  device: a busy canvas holds ~30 fps on a 60–120 Hz panel.
- **Network policy** (`web/WallpaperNetworkPolicy`, enforced in
  `shouldInterceptRequest` since Android has no WKContentRuleList): ad /
  analytics / miner domains and SSRF targets (raw IP literals, localhost,
  `*.local`, metadata hosts, nip.io/sslip.io) are always blocked; bundles
  screened "limited" (or imported before screening existed) also get a
  5-req/min budget on external requests. Main-frame navigation off the bundle
  origin is refused; the shim rejects WebSockets to raw IPs (sockets bypass
  shouldInterceptRequest). Local asset-loader traffic is never filtered.

The Android runtime should not try to share Swift code with ÆtherDesk. The
portable contract is the data shape: Lively metadata, property definitions,
property overrides, validation rules, and the JavaScript bridge semantics.

Kotlin should stay boring. If code is mostly about Android framework lifecycle,
permissions, or views, it belongs in Kotlin. If code is about understanding a
wallpaper bundle or deciding whether it is safe and supported, it belongs in
Rust.

Current JNI shape:

- Kotlin calls `LumenfallCore.parseLivelyInfo(json)`.
- Kotlin calls `LumenfallCore.parseLivelyProperties(json)`.
- Kotlin calls `LumenfallCore.validateBundle(infoJson, basePath, filesJson)`.
- Kotlin calls `LumenfallCore.screenBundle(scriptSource)`.
- Rust returns normalized JSON envelopes for now. That avoids duplicating the
  full model in Kotlin while the engine is still moving.

Native build (DONE, 2026-06-29):

- `cargo-ndk` cross-compiles `lumenfall_core` to `liblumenfall_core.so`. It is
  wired into Gradle as the `cargoNdkBuild` task that `preBuild` depends on, with
  output to `app/build/rustJniLibs/<abi>/` registered as a jniLibs source dir
  (no checked-in binaries). See `app/build.gradle.kts`.
- ABI: `arm64-v8a` only. `minSdk` is 33 (Android 13) — every 2023+ device is
  64-bit ARM, and Apple-Silicon emulators use arm64 too. Native min-API matches
  `minSdk` via cargo-ndk `--platform 33`.
- NDK pinned to r27.3 (`ndkVersion` + `ndkPin` in the build file); the task
  fails with an install hint if it is missing.
- `core/LumenfallCore.kt` loads the lib unconditionally (no `runCatching`); a
  load failure is a packaging bug we want loud. `ui/MainActivity.kt` runs an
  on-device JNI smoke test, verified passing on a Pixel Tablet (Android 17).
- Toolchain note: `cargo` must resolve to the rustup shim (`~/.cargo/bin`), which
  owns the `aarch64-linux-android` target — NOT the Homebrew `rust` formula. The
  Gradle task prepends `~/.cargo/bin` to PATH to guarantee this.

Release build (DONE, 2026-07-03):

- `buildTypes.release` enables R8 minification + resource shrinking.
  `app/proguard-rules.pro` keeps the two name-resolved surfaces: the
  `LumenfallCore` JNI class/methods (Rust exports
  `Java_com_aetherdesk_lumenfall_core_LumenfallCore_*` symbols) and
  `@JavascriptInterface` bridge methods (called by name from bundle JS).
- Signing reads a gitignored `keystore.properties` + `release.jks` at the repo
  root; when absent (fresh checkout, CI) release falls back to the debug key so
  it still assembles. The keystore is the app's permanent identity — back it up
  privately; losing it means users can never update.
- Verified on the Pixel Tablet: import (both JNI calls), all three renderer
  kinds, settings UI, and override persistence work minified. APK is 3.5 MB.
  Visible CPU for the busy 60 fps web bundle: ~60% of one core (vs ~111%
  debug); 0.0% hidden. Debug↔release switches need a full uninstall (different
  signatures), which wipes imported bundles.

Open questions:

- Can a WebView be hosted safely and efficiently for a live wallpaper, or do we
  need an offscreen WebView snapshot pipeline?
- Should video rendering use Media3 directly against the wallpaper surface, or a
  small GL layer for consistent scaling and crossfade behavior?
- Which Lively wallpaper types map cleanly to Android, and which need explicit
  "unsupported" reporting in the picker?
