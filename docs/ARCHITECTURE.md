# Architecture Notes

Lumenfall is split into these layers. The application id and Kotlin package are
`com.sdelavega.lumenfall` (paths below abbreviate it as `app/...`); the version
is 1.0.0, versionCode 10000. The bundle-facing bridge keeps ÆtherDesk's names
(`window.aetherDesk.*`, `livelyPropertyListener`, `onPropertyChange`) so existing
bundles run unchanged.

1. `core-rust`: normalized Lively models, parsing, validation, import
   screening, and portable policy, including the single network blocklist
   (`blocklist.rs`) that both the screener and the Kotlin network policy use.
2. `app/.../core`: JNI loading and narrow Kotlin facade over Rust. `CoreApi` is the
   interface the importer and policy code depend on, so JVM tests use a fake.
   A JVM test checks the `external fun` names against the Rust exports
   (`Java_com_sdelavega_lumenfall_core_LumenfallCore_*`) so the two cannot drift.
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
   `PropertySchema` is a typed sealed hierarchy (Slider, Color, Checkbox, Dropdown,
   FolderDropdown, Text, Button, Label) parsed once from Rust's envelope; no UI
   code does string-keyed JSON lookups. Parse errors surface as a message instead
   of an empty list, and the parsed schema is cached per bundle, invalidated on
   import, delete and bundled-asset upgrade. Override reads tolerate corrupt data.
   `PrefKeys` URL-encodes bundle ids into preference keys and runs a versioned
   one-time migration (v2 key encoding; v3 moves the retired `LivelyRain` to
   `Rainfall`). `AppSettingsStore` is the
   separate, app-level counterpart — global Performance preferences (see below)
   that apply to every wallpaper, not one bundle's overrides.
7. `app/.../importer`: `BundleImporter` — zip-slip-guarded extraction of a Lively
   `.zip`, then `LivelyInfo.json` (and `LivelyProperties.json` when present) parsed
   via the Rust core into bundle metadata + property schema.
8. `app/.../ui`: `MainActivity` — the bundle library/picker (SAF import, list,
   select-active which opens the live-wallpaper preview; a `RecyclerView` with
   `DiffUtil`, so a finished thumbnail updates one row) plus the global
   Performance + About panes; `BundleSettingsActivity` — per-bundle property
   controls (slider/color/dropdown/checkbox/...) and its live preview.
   `GlassUi` is the shared styling toolkit both screens draw every control from.
   "Now live" is reported only after the system chooser returns and our service
   is the active wallpaper; activation failures show a dialog with a re-import hint.
9. `app/.../render/*Bus`: process-local fan-out of live property changes
   (`PropertyBus`), performance settings, the active bundle and bundle deletion
   (`BundleDeletedBus`; the settings screen tears its preview down and finishes
   when its bundle is deleted). Every bus delivers listeners on the main thread
   through `MainThreadDispatcher`.

Bundle flow: import zip -> extract to `bundles/<id>/` with zip-slip protection
-> locate `LivelyInfo.json` (shallowest match wins) -> send the metadata base path plus a normalized file
inventory to the Rust validator -> persist `BundleMeta` only when Rust returns an
admissible web/video/image bundle -> picker sets active -> the engine loads the
active bundle. Web bundles load from
`https://appassets.androidplatform.net/bundles/<id>/<entry>` (served by
`InternalStoragePathHandler`); video/image read the file directly from
`bundles/<id>/<entry>`. Unsupported Lively types are rejected at import with a
clear reason. A selected bundle takes effect when the engine is (re)created (the
picker reopens the wallpaper preview to do this). When no bundle is active the
engine loads `assets/placeholder/index.html` ("Pick a wallpaper to begin").

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
calls Lively's `livelyPropertyListener` for compatibility. Android tears the
wallpaper engine down under a fullscreen settings Activity, so a push to the
*home-screen* engine only lands via injection on its next creation — but
`BundleSettingsActivity` (DONE, 2026-07-03) now runs its own embedded instance
of the bundle (via `BundleWebView`, the same construction the wallpaper host
uses) and registers it on `PropertyBus` too, so every control change is visible
live while adjusting it, regardless of the home-screen engine's state. The
Activity declares `configChanges` in the manifest and relays out its own view
tree on rotation instead of being destroyed, so this preview WebView survives
turning the device without reloading.

Practical limits (DONE, 2026-07-03 — ported from ÆtherDesk so bundles behave
identically on both hosts):

- **Import screening** (`core-rust/bundle_screening.rs`, heuristics from
  ÆtherDesk's WallpaperValidator): the importer concatenates a web bundle's
  .js/.html source (1 MB/file, 8 MB total caps) and Rust classifies it. Every
  skipped file (unreadable, over a cap) is logged, and any skip downgrades an
  `allowed` result to `limited`, since screening was incomplete. The screener
  matches the full blocked domains from the shared blocklist (no TLD stripping,
  which falsely rejected wallpapers such as `segment`-named scripts).
  Trackers/ad domains, crypto miners, ServiceWorker/SharedWorker,
  `window.open`, `while(true)`, and >10 network call sites reject the import
  with a reason; network use, eval-family, WebGL, and heavy-GPU patterns
  downgrade to "limited" (persisted as `screening.json`).
- **FPS cap** (web shim, `assets/bridge/aetherdesk-shim.js`): `requestAnimationFrame` is throttled to
  `aetherDesk.system.fpsCap` — default 30, clamped 15–60 — sleeping between
  frames via setTimeout, and drops frames entirely while hidden. Measured on
  device: a busy canvas holds ~30 fps on a 60–120 Hz panel.
- **Network policy** (`web/WallpaperNetworkPolicy`, enforced in
  `shouldInterceptRequest` since Android has no WKContentRuleList): the blocklist
  comes from Rust over JNI (`blocklistJson`), the same source the screener uses;
  `ipapi.co` and `api.bigdatacloud.net` are blocked for every bundle (see
  below), though they are not screener issues. Ad /
  analytics / miner domains and SSRF targets (raw IP literals, localhost,
  `*.local`, metadata hosts, nip.io/sslip.io) are always blocked; bundles
  screened "limited" (or imported before screening existed) also get a
  5-req/min budget on external requests. Main-frame navigation off the bundle
  origin is refused; `WebSocketGuard` validates WebSocket targets (sockets bypass
  shouldInterceptRequest): raw IPs are refused, and DNS validation runs on a
  bounded executor (8 in flight, extras rejected) with a 5 s DNS cache. That cache
  leaves a documented time-of-check window for DNS rebinding. Local asset-loader traffic is never filtered.

Settings UI (DONE, 2026-07-03):

- Responsive: side-by-side (settings left, preview right) in landscape,
  stacked (preview top, settings bottom) in portrait, via the Activity's own
  `onConfigurationChanged` relayout rather than a rebuild.
- Visually a facsimile of Liquid Glass — translucent rounded panels
  (`colors.xml`, `drawable/glass_*_bg.xml`), a Material `Slider` and
  `SwitchCompat` sharing one accent tint, a themed `Spinner`/`EditText`.
  Real backdrop blur of arbitrary content isn't available to a plain View
  hierarchy on Android without Compose + a blur library, so this leans on
  translucency, thin borders, and rounded-corner WebView clipping instead of
  live blur. Floating popups (the dropdown menu) are the one exception: a
  popup sits over arbitrary content and the anchor view keeps rendering
  underneath it, so the panel-level translucency there let the anchor's own
  text bleed through and read as illegible — popups get their own near-opaque
  fill (`glass_popup_bg`, `glass_popup_fill` in colors.xml) instead.
- `web/PerformanceGovernor` watches the embedded preview for a control change
  that's overloading the device. There's no public per-app GPU usage API, so
  it uses achieved-fps-vs-target from the bridge shim's frame counter (the
  one signal that reaches inside the WebView's sandboxed renderer process),
  backed by process CPU time and system memory pressure as coarser
  backstops. On sustained overload it reverts the property change that
  caused it (coalescing a whole slider drag into one revert target) or, when
  overload isn't attributable to a specific change, eases the live fps cap
  — a per-session easing, not persisted to the bundle's screening record.
  The CPU backstop (`Process.getElapsedCpuTime`) measures only the host
  process: the WebView's renderer runs in an isolated process whose CPU this
  app cannot read, as no public API exposes its pid. Frame pressure is the
  renderer-cost proxy (#25). The jank decision is pure and unit-tested
  (`web/GovernorMath`); a window with no frames or zero length is not jank, so
  a hidden or paused preview never ratchets the cap down. A frozen visible
  renderer is the watchdog's job, not the governor's. The on-device `/proc`
  readability probe is part of the release smoke run, to confirm that
  assumption on real hardware.

MainActivity (DONE, 2026-07-03): same responsive-split + glass-everywhere
treatment as the settings screen — wallpapers/import on one side (left in
landscape, top in portrait), a `Performance` + `About` pane on the other.
`ui/GlassUi` is the shared toolkit both Activities draw controls from
(panels, buttons, switches, spinners, a custom `confirmDialog` replacing
`AlertDialog`, a custom `toast` overlay replacing system `Toast`) — no stock
Android widget chrome appears anywhere in the app. The Performance pane
edits `storage/AppSettingsStore` (six toggles ported from ÆtherDesk's
Preferences > Performance tab: default FPS cap, respect Low Power Mode,
pause when not visible, pause on battery, block external network, allow
LAN), resolved once per render via `web/PerformancePolicy` — a snapshot at
load time, not a live-reacting daemon, consistent with how import
screening's limits already work. The UI says as much ("applies next time a
wallpaper loads") so this isn't a surprise.

Watchdog and failure recovery (DONE, 2026-07-03 — ported from ÆtherDesk's
WatchdogTimer):

- `web/WallpaperWatchdog` fires once if 30s pass with no activity, poked by
  the bridge's existing 2s tick telemetry and page-load events. The shim sends no
  tick while suspended, and the host ignores ticks while rendering is not
  allowed. Demote, don't
  retry: a bundle whose JS thread is genuinely wedged (e.g. a synchronous
  infinite loop) will likely wedge the same way again.
- `WallpaperWebViewHost` mutates its state on the main thread only (callbacks
  from WebView-internal threads, such as render-process-gone, hop through the
  main handler). Deep sleep is scheduled only after an actual `setVisible(false)`,
  and its snapshot bitmap is allocated inside the PixelCopy flow and recycled
  on every path.
- `WallpaperWebViewHost` owns three failure modes, all funneling into the
  same one-way demotion (a live wallpaper has no crash UI to fall back on):
  a watchdog timeout or a dead renderer process (`BundleWebView`'s
  `onRenderProcessGone`, which must return `true` — returning `false`, the
  default if unoverridden, kills this app's whole process, not just the one
  WebView) demotes immediately to a static fallback color (`#0B0F1A`,
  matching the glass UI's backdrop); a main-frame load failure gets one
  retry, demoting if the retry also fails — a retry that *stalls* (never
  errors, never finishes) is caught by the watchdog rather than a dedicated
  timer, since the watchdog already pauses correctly while the wallpaper is
  hidden. A fresh attempt only happens if the host itself is recreated
  (wallpaper reselected, or the engine torn down and rebuilt). Note:
  Android fires onPageFinished even for a failed navigation's internal
  error page; BundleWebView consumes one finish per main-frame error so
  error pages can't fake liveness to the watchdog or get captured as
  thumbnails.
- All three demotion paths (watchdog hang, render-process-gone, nav-retry-
  then-demote) are verified live on-device with isolated per-path repros:
  a JS busy-loop bundle for the watchdog; a bundle that grows its own JS
  heap in 64 MB chunks until the OS kills its renderer process for the
  render-process-gone path (`onRenderProcessGone` fires with
  `crashed=false` — an external kill, not a Chromium-internal crash, and
  cleanly reaped, unlike the busy-loop case below); and a bundle whose
  entry file is deleted from disk after import (simulating storage
  going missing under a bundle) for the nav-retry-then-demote tail. All
  three were confirmed to reach the correct `#0B0F1A` fallback pixel
  (sampled from a device screenshot), not just to log the right message.
- That per-path repro work found a second real bug: the fallback color
  never actually rendered, on any of the three paths — `markFailed()`'s
  `detach()` releases the `VirtualDisplay`, which disconnects its
  Surface's buffer producer *asynchronously*, and the immediately-
  following `holder.lockCanvas()` call to draw the fallback would return
  null (confirmed via targeted logging: reproducibly null on the very
  first attempt, no exception thrown) — silently leaving the surface
  showing stale black content forever. Fixed by retrying `lockCanvas`
  briefly (up to 5x 30ms) before giving up, bridging the async handoff.
  Re-verified all three paths after the fix, including a redo of the
  watchdog-hang path (whose original fallback-color check, earlier in
  this same session, predates this fix and should be considered to have
  been checking stale black, not actually confirming the color) — all
  three now land the exact `#0B0F1A` pixel, sampled from a device
  screenshot each time.
- Along the way, fixed two real bugs in `web/WebViewTimers` (the
  process-shared `pauseTimers`/`resumeTimers` latch, since that call is
  process-global and affects every WebView, not just the caller's) found
  while live-testing the watchdog's fallback path: the "needs timers" claim
  at WebView creation was conditional on `!pauseWhenHidden`, so a
  freshly-created engine could inherit an already-paused latch left by a
  just-torn-down prior engine and never resume; and rapid
  true/false/true visibility churn within ~20ms of a brand-new WebView's
  creation (observed on-device during engine handoff) could physically
  pause/resume Chromium's timers before the renderer had even attached,
  wedging the page's own bootstrap script. Fixed by claiming timers
  unconditionally at creation and debouncing the physical toggle by 250ms.
- **Platform limitation found while testing, not a Lumenfall bug**: a
  bundle whose JS busy-loops with zero yield points (`for(;;){}`) wedges the
  *native renderer process's* main thread so completely that it cannot
  respond to Chromium's own IPC-based teardown request. `WebView.destroy()`
  only tears down the Java-side proxy — the OS-level sandboxed renderer
  process survives indefinitely (confirmed via `adb shell ps -A`: one such
  process ran at 90%+ CPU for 3+ minutes across several subsequent
  demote/destroy cycles), and Android has no public API for an app to
  force-kill a specific isolated sandboxed process it doesn't own the UID
  for. The only reclaim path is `adb shell am force-stop <package>`, which
  tears down the whole app process and its bound isolated children together
  — a debugging/testing note, not something the app can (or needs to) defend
  against, since real-world hangs are far more likely to be a stalled fetch
  or a slow loop with actual yield points than a hand-authored `for(;;){}`.

Thumbnail auto-generation (DONE, 2026-07-03 — ported from ÆtherDesk; second
of the porting recommendations, after the watchdog):

- `web/BundleThumbnailer` backfills a picker preview for any bundle that
  didn't declare one, per kind: image bundles point `previewPath` at the
  entry itself; video bundles get a `MediaMetadataRetriever` frame from
  ~1s in; web bundles actually *run* offscreen for a few seconds and get
  snapshotted — a VirtualDisplay backed by an ImageReader surface (the
  wallpaper host's Presentation technique pointed at a capturable surface,
  since `View.draw()` can't see hardware-composited WebGL/canvas), using
  the same sandboxed `BundleWebView` construction as the real renderers,
  at the 15 fps floor. Capture fires 3s after page load; gives up at 12s,
  on renderer death, or on main-frame load failure, writing a marker file
  so a broken bundle isn't retried on every refresh.
- The generated JPEG lives in the bundle dir under a dot-prefixed name
  (`.lumenfall-thumb.jpg`) so it can't collide with bundle content.
  `MainActivity` drains missing thumbnails one at a time on each picker
  refresh through a single serial, de-duplicated queue (`web/ThumbnailQueue`,
  no `running` flag, no recursion), updating one list row as each lands.
- Verified on-device for all three kinds. The web capture doubled as a
  sandbox check: the probe bundle's HUD in the captured thumbnail shows
  the reduced fps cap applied and its tracker/metadata/raw-IP probes
  blocked, proving policy enforcement is active during thumbnailing too.

Contacted-domains transparency (DONE, 2026-07-03 — ported from ÆtherDesk;
last of the three porting recommendations):

- `web/ContactedDomains` is an in-memory, process-wide ledger of the
  external domains each web bundle has actually contacted, recorded in
  `BundleWebView.shouldInterceptRequest` for every request the network
  policy lets out. Allowed requests only — a blocked request never left
  the device, so listing it would overstate what the bundle did (blocks
  are already surfaced in the bridge log). Deduped, insertion-ordered,
  capped at 100 per bundle, and deliberately not persisted (matching
  ÆtherDesk): "since this app started" is the honest scope for a live
  readout, and the wallpaper engine, settings preview, and thumbnailer
  all run in one process, so everything lands in the same ledger.
- Surfaced at the bottom of `BundleSettingsActivity` ("Domains contacted:
  …" / "No external domains contacted yet."), refreshing every 2s while
  visible. Web bundles without configurable properties now get the
  Settings entry point too — the screen carries the live preview and this
  readout, so it's no longer property-gated. Deleting a bundle clears its
  ledger entry.
- Known gap, shared with ÆtherDesk: WebSocket connections to FQDNs bypass
  `shouldInterceptRequest` (Android offers no hook), so they don't land in
  the ledger; raw-IP sockets are refused by the shim outright.

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
- Kotlin calls `LumenfallCore.blocklistJson()` for the shared blocklist.
- Rust returns normalized JSON envelopes for now. That avoids duplicating the
  full model in Kotlin while the engine is still moving.

Geolocation (DONE, 2026-07-03 — web bundles only, per-bundle toggle,
screening-gated; 1.0 also declares and requests `ACCESS_COARSE_LOCATION`, so
the user may grant approximate location):

- Exposed to web wallpapers as `window.aetherDesk.geolocation.getCurrentPosition
  ({enableHighAccuracy, timeoutMs})`, a Promise-based API distinct from the raw
  `navigator.geolocation` the screener distrusts (a bundle using the raw API
  trips the screener's "Requests geolocation" warning → `limited` → no bridge;
  the audited, gated path is the bridge one). Resolves `{latitude, longitude,
  accuracy, altitude?, altitudeAccuracy?, bearing?, speed?, timestamp,
  elapsedRealtimeNanos, provider}` or rejects `{code, message}` with codes
  `disabled | denied | unavailable | timeout`.
- Three gates, all must pass for the native surface (`window.aetherDeskGeo`)
  to be registered at document-start: (1) the import screener classified this
  bundle `allowed` (not `limited` — ties location access to the trust decision
  already made at import, so a screened-out bundle can't reach location as a
  side channel); (2) a per-bundle toggle in `BundleSettingsActivity`, default
  off (even a clean bundle doesn't get location until the user opts this
  wallpaper in); (3) fine or coarse location granted. The toggle is only shown
  for web bundles with a clean screening result — a limited bundle gets an
  explainer instead, so the absence isn't a mystery.
- A live wallpaper runs in the background and can't host a permission dialog,
  so the per-bundle toggle in `BundleSettingsActivity` (an Activity) is the
  only entry point that prompts. Turning the toggle on with permission not
  yet granted launches `RequestPermission`; denied → the toggle reverts.
  Permission is also re-checked per bridge call inside `GeolocationProvider`,
  so a mid-session revoke surfaces as a clean JS `denied` rejection rather
  than a crash (the policy snapshot was taken at load, but revocation is the
  one condition that can change after).
- Fine or coarse: both permissions are declared and requested together (Android
  requires coarse alongside fine), so an approximate grant works; the provider
  returns whatever accuracy was granted and the bridge reports it.
- Reverse geocoding is skipped when `Geocoder.isPresent()` is false and
  otherwise runs asynchronously. `getCurrentPosition` resolves immediately; the
  place label comes from a 2-decimal coordinate cache (`web/LabelCache`) that
  the async lookup fills for the next request.
- Lumenfall never sets `window.aetherDesk.system.capabilities.ipGeolocation`,
  so WeatherAether's ipapi.co and BigDataCloud paths stay off (and the network
  policy blocks those hosts anyway); it uses device location or a typed city,
  with `api.open-meteo.com` and `geocoding-api.open-meteo.com` as its only
  services.
- Provider: the framework `LocationManager.getCurrentLocation` (API 30+, our
  minSdk is 33), not FusedLocationProvider. Lumenfall ships no Play Services
  dependency today (3.5 MB APK, runs on any Android 13+ device); the framework
  one-shot API needs no persistent client or service binding. Provider
  selection prefers GPS when enabled, else NETWORK — a WiFi-only tablet like
  the Pixel Tablet has no GPS_PROVIDER at all, so `isProviderEnabled` gates
  the choice and the NETWORK_PROVIDER (WiFi-based) fix is used instead.
- Snapshot semantics (matches Performance settings and screening): the bridge
  is wired — or not — at document-start, not retroactively for an already-
  running bundle. The settings UI says as much ("applies next time the
  wallpaper loads").
- Verified on-device (Pixel Tablet, WiFi-only, no GPS): (a) toggle off →
  `disabled`; (b) toggle on + permission granted + screening clean → real
  fix via `provider:"network"` with lat/long/accuracy/altitude; (c) toggle
  on + permission granted + screening `limited` → `disabled` (screening
  gate holds); (d) toggle on + permission revoked → `disabled` (permission
  gate holds). The mid-session-revoke `denied` path is a defensive re-check
  on top of the verified policy gate, covered by code inspection (the
  `checkSelfPermission` call is in `GeolocationProvider.current`).


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
- Toolchain note: `rust-toolchain.toml` pins Rust 1.99.1 with the
  `aarch64-linux-android` target, which only rustup's `cargo`/`rustc` honour — not
  the Homebrew `rust` formula. The Gradle task uses `$CARGO` or `cargo` from
  `PATH` (no hard-coded path); the `justfile` exports `CARGO`, `RUSTC` and
  `RUSTDOC` pointing at rustup's when it is present.
- Reproducible release: the Cargo release profile uses `lto`, one codegen unit,
  `strip` and `panic = "abort"`, builds use `--locked`, and `RUSTFLAGS` remaps the
  workspace, Cargo home and rustup home so no build-machine path reaches the `.so`
  (`just no-home-paths` scans tracked files, the `.so` and the APK). The APK
  omits dependency metadata. `just repro-check` builds twice from separate copies
  of the tree and compares the unsigned APKs bytewise.

Vendored wallpapers (DONE, 1.0): Weather Aether, Gradient Wave, Matrix Rain and
Neon City are exported from a local ÆtherDesk checkout at an exact commit by
`just sync-wallpapers <ref>` (`git archive`, committed files only), which also
writes `assets/wallpapers/wallpapers.lock` (repo URL, commit, SHA-256 per file).
A JVM test (`just wallpapers-lock`) fails on any hand edit, missing or extra
file, so fixes belong upstream. The other bundled wallpapers are Rainfall (an
original WebGL rain-on-glass wallpaper replacing Rain, with a Canvas 2D fallback)
and three MIT-licensed ones. `THIRD_PARTY.md` and the in-app licences screen are
generated by `just licenses`.

Release build (DONE, 2026-07-03):

- `buildTypes.release` enables R8 minification + resource shrinking.
  `app/proguard-rules.pro` keeps the two name-resolved surfaces: the
  `LumenfallCore` JNI class/methods (Rust exports
  `Java_com_sdelavega_lumenfall_core_LumenfallCore_*` symbols) and
  `@JavascriptInterface` bridge methods (called by name from bundle JS).
- Signing reads a gitignored `keystore.properties` + `release.jks` at the repo
  root; when absent (fresh checkout, CI) release falls back to the debug key so
  it still assembles. The keystore is the app's permanent identity — back it up
  privately; losing it means users can never update.
- Verified on the Pixel Tablet: import (both JNI calls), all three renderer
  kinds, settings UI, and override persistence work minified. APK is 3.5 MB.
  Visible CPU for the busy 60 fps web bundle: ~60% of one core (vs ~111%
  debug); 0.0% hidden. Debug↔release switches need a full uninstall (different
  signatures), which wipes imported bundles. Cloud backup excludes imported
  bundles and thumbnail caches (`data_extraction_rules.xml`).

Open questions:

- Can a WebView be hosted safely and efficiently for a live wallpaper, or do we
  need an offscreen WebView snapshot pipeline?
- Should video rendering use Media3 directly against the wallpaper surface, or a
  small GL layer for consistent scaling and crossfade behavior?
- Which Lively wallpaper types map cleanly to Android, and which need explicit
  "unsupported" reporting in the picker?
