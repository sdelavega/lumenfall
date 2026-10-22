# Lumenfall code review — 2026-07-04

Reviewer: Hermes (Matanzas / macOS, MiniMax-M3 via OpenRouter). Cross-checked against source on 2026-07-04.

## Context

- Repository: `~/src/Lumenfall`
- Codebase age: ~1 week of heavy iteration, including sessions with other LLM collaborators (Fable + others)
- Reviewed by reading: all of `core-rust/src/*`, all of `app/src/main/java/com/aetherdesk/lumenfall/**`, both docs files, both test files, all eight bundled `LivelyInfo.json` manifests, `AndroidManifest.xml`, `app/build.gradle.kts`, root `Cargo.toml`, `proguard-rules.pro`
- Architecture reference: `docs/ARCHITECTURE.md`
- Test run: `./gradlew :app:assembleRelease` succeeded; APK 9.8 MB, signed with the gitignored release keystore

Findings are organized: real bugs first (verified), then architectural smells (judgment calls), then dead/cosmetic. Each item has file + line and, where possible, a reproducer.

---

## Real bugs

### 1. `MainActivity` toasts "Now live" before the user confirms the wallpaper change
**File:** `app/src/main/java/com/aetherdesk/lumenfall/ui/MainActivity.kt:481-495`

```kotlin
val ourComponent = ComponentName(this, LumenfallWallpaperService::class.java)
if (WallpaperManager.getInstance(this).wallpaperInfo?.component == ourComponent) {
    ActiveBundleBus.notifyChanged()
    toast("Now live: ${meta.title}")
    return
}
val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
    putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ourComponent)
}
```

The "Now live" toast fires whenever `wallpaperInfo.component == ourComponent` — but this is true *before* the user actually confirms in the system preview, *after* they re-select, and any time our service is just configured. If they hit back on the system preview instead of "Set wallpaper", they get the toast implying it changed when it didn't.

**Fix:** Only toast after the intent's onActivityResult confirms; or remove the toast in this branch and rely on the system's own "Wallpaper set" feedback.

---

### 2. `BundleImporter.collectScriptSource` swallows per-file exceptions silently
**File:** `app/src/main/java/com/aetherdesk/lumenfall/importer/BundleImporter.kt:104-113`

`runCatching { ... }` with no logging means a script file that fails to decode (bad UTF-8, IO error mid-read, symlink loop) silently disappears from the screener's input. The bundle screens `allowed` against an incomplete view of its JS — exactly when screening matters most.

**Fix:** Log skipped files; if more than ~1% skip, treat as `limited` rather than `allowed`.

---

### 3. `BundleThumbnailer` re-entrancy race
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/BundleThumbnailer.kt:95-105`

```kotlin
fun generateMissing(context: Context, store: BundleStore, onProgress: () -> Unit) {
    if (running) return
    ...
    running = true
    generate(app, store, next) { updated ->
        running = false                  // set false BEFORE onProgress
        if (updated != null) onProgress() // onProgress -> refresh -> generateMissing again
        generateMissing(app, store, onProgress)
    }
}
```

`running` flips to false before `onProgress` runs; `onProgress` calls `refresh()` which can re-enter `generateMissing` immediately; original generation's queued handlers fire on the half-torn-down state. The recursive call at the end is unbounded on rapid re-entry.

**Fix:** Set `running = false` *after* `onProgress` *and* the recursive call, or use a single-threaded `Executor` to serialize.

---

### 4. `PerformanceSettingsBus.notifyChanged` runs listeners on the caller's thread; `WebView.evaluateJavascript` requires main
**File:** `app/src/main/java/com/aetherdesk/lumenfall/render/PerformanceSettingsBus.kt:27-30`

```kotlin
fun notifyChanged(settings: PerformanceSettings) {
    val snapshot = synchronized(this) { listeners.values.toList() }
    snapshot.forEach { it.onSettings(settings) }
}
```

`LumenfallWallpaperService.applyPerformance` (called from this listener) ends in `webView.evaluateJavascript(...)`, which must run on the main thread. Today only the main thread calls `notifyChanged`, but any future worker-thread caller crashes with "Wrong thread for evaluateJavascript."

**Fix:** Post listener calls to main, or document `notifyChanged` as main-thread-only.

---

### 5. `WallpaperWatchdog` gets poked by JS tick even while hidden
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/BundleWebView.kt:438-441`

```javascript
setInterval(function () {
    if (native) native.postMessage('{"tick":true,"frames":' + _frames + '}');
}, 2000);
```

`pauseTimers()` is process-global with a 250ms debounce. Brief visibility flickers (notification pull-down, quick settings peek) may never settle into paused. Even if it does pause, the `setInterval` body can still queue a tick message depending on Chromium internals — so the watchdog gets poked while the bundle's actual rAF chain isn't rendering. Falsely reassuring that the bundle is alive during 0–60s of hidden-but-not-deep-sleep'd time.

**Fix:** Track `suspended` in the shim and skip the tick when true; or skip pokes received while engine is hidden.

---

### 6. `BundleStore.delete()` doesn't tear down `BundleSettingsActivity` preview
**File:** `app/src/main/java/com/aetherdesk/lumenfall/storage/BundleStore.kt:80-86`

If the deleted bundle was the live preview's bundle in `BundleSettingsActivity`, the preview WebView stays alive and rendering. `ContactedDomains.clear` is called but no preview teardown.

**Repro:** Open Settings for a bundle. Long-press the bundle in Main, delete it. Return to Settings. Preview keeps running.

**Fix:** Push bundle deletion through a new `BundleDeletedBus`, or have `BundleSettingsActivity` check `store.get(id) == null` in `onResume` and `finish()`.

---

### 7. `WebSocketGuard.validate()` can pin a thread for ~30s per slow DNS resolution
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/WebSocketGuard.kt:34-44`

Each WebSocket connection spawns a fresh `Thread` that calls `InetAddress.getAllByName(host)` synchronously. A bundle opening 10 concurrent WebSockets against slow-resolution hosts pins 10 threads for up to ~30s each. Thread pool starvation.

**Fix:** Concurrent-in-flight cap (same pattern as `NativeFetchBridge.MAX_CONCURRENT = 8`), or a TTL-bound DNS cache.

---

### 8. `BundleStore.overrides()` for bundled wallpapers can crash on corrupted prefs
**File:** `app/src/main/java/com/aetherdesk/lumenfall/storage/BundleStore.kt:131-133`

```kotlin
if (BundledWallpapers.isBundled(id)) {
    prefs.getString("overrides_$id", null)?.let { JSONObject(it) } ?: JSONObject()
}
```

`JSONObject(it)` throws on malformed JSON. The imported-bundle branch (line 135) wraps in `runCatching`; the bundled branch does not. A corrupted prefs entry crashes the Settings Activity for any bundled wallpaper.

**Fix:** Wrap in `runCatching { JSONObject(it) }.getOrNull() ?: JSONObject()`.

---

### 9. `BundleStore.properties()` re-runs the Rust `parseLivelyProperties` JNI call on every read
**File:** `app/src/main/java/com/aetherdesk/lumenfall/storage/BundleStore.kt:116-127`

The bundled envelope is constant for the install lifetime. `MainActivity.refresh()` calls `store.properties(meta.id)` for every bundle on every refresh; `BundleSettingsActivity.onCreate` calls it once per property; `effectiveValues` calls it twice. Comment on line 118 says "cached per call" — meaning the author knew caching was needed and didn't implement it.

**Fix:** `private val cachedProps: MutableMap<String, List<BundleProperty>>` keyed by id, invalidated on import/delete.

---

### 10. `WallpaperWebViewHost` mutates state from non-main threads
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/WallpaperWebViewHost.kt:206-213`

`markFailed()` is invoked from the `WallpaperPresentation.onIrrecoverable` callback, which is invoked from `BundleWebView.onRenderProcessGone`, which Android delivers on a *WebView-internal thread*. `failed`/`virtualDisplay`/`presentation` are read/written from there and from the main-thread `onSurface`. Currently safe by convention; one future change to the WebViewClient wiring breaks it.

**Fix:** `@Synchronized` on the fields, or hop to main via `mainHandler.post` before any mutation.

---

### 11. `MainActivity.refresh()` rebuilds the whole list and loses scroll position
**File:** `app/src/main/java/com/aetherdesk/lumenfall/ui/MainActivity.kt:208-236`

Called from: `onResume`, after import, after delete, every `BundleThumbnailer.generateMissing` callback, after `setActiveAndPreview`. Importing a bundle without a preview causes a full list rebuild ~8 times in a row (once per thumbnail that lands). Visible flicker on slower devices.

**Fix:** `RecyclerView` with `DiffUtil`.

---

### 12. `WallpaperWebViewHost.enterDeepSleep()` allocates a ~16MB bitmap outside a `try/finally`
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/WallpaperWebViewHost.kt:284-303`

For a 1600×2560 tablet: 16MB per call, allocated before `PixelCopy`. If `PixelCopy` callback throws or never fires (low-memory OOM during GC), bitmap leaks until the lambda runs. No `recycle()` on the failure path.

**Fix:** Allocate inside the lambda; `recycle()` in `finally` after draw.

---

## Architectural smells / robustness gaps

### 13. `BundleProperty.raw` is an untyped `JSONObject` — every consumer does stringly-typed lookups
**File:** `app/src/main/java/com/aetherdesk/lumenfall/storage/BundleProperty.kt:14-18`

UI does `p.raw.optString("folder")`, `optJSONArray("items")`, `optString("filter")` — typo'd keys silently return empty. Replace with a typed `PropertySchema` sealed hierarchy: `Slider(min, max, step)`, `Dropdown(items)`, `FolderDropdown(folder, filter, extensions)`.

### 14. `BundleWebView.buildShim` is 850 lines of embedded JS in a Kotlin string
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/BundleWebView.kt:332-852`

Not testable from JUnit, not linted, no syntax highlighting beyond what IntelliJ does for multiline strings. The shim is *the* bridge contract — a single typo and every bundle breaks.

**Fix:** Move to `app/src/main/assets/aetherdesk-shim.js`, load via `assets.open(...).bufferedReader().use { it.readText() }`. Now lintable, formattable, tree-sitterable.

### 15. No tests for the Kotlin layer that orchestrates the Rust core
**File:** `app/src/test/java/com/aetherdesk/lumenfall/importer/BundleFilesTest.kt` (only one)

The orchestrating logic in `BundleImporter`, `BundleStore`, `WallpaperWebViewHost`, `BundleThumbnailer` has zero unit tests. `BundleImporter` is the highest-risk surface — file IO + JSON parsing + transactional cleanup + JNI — and one bug there loses user data.

**Fix:** `BundleImporterTest.kt`: (a) zip-slip throws + dir cleaned, (b) invalid LivelyInfo throws + dir cleaned, (c) screening rejection + dir cleaned, (d) valid + screening ok + meta + screening.json written.

### 16. `GeolocationProvider.bestEffortLabel` blocks up to ~5s on devices without a geocoder
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/GeolocationProvider.kt:158-170`

`Geocoder.getFromLocation` blocks when no system geocoder is installed (e.g., Pixel Tablet without Play Services). `runCatching` catches the timeout but the 5 seconds is still spent. A bundle calling `getCurrentPosition` in a tight loop wedges the UI thread.

**Fix:** Use `Geocoder.isPresent` to skip; make the lookup async if present.

### 17. `WallpaperWebViewHost.applyPerformance` schedules deep-sleep at engine creation even when not hidden
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/WallpaperWebViewHost.kt:121-124`

At creation, `contentVisible` defaults to `false`; `applyPerformance` is called → deep-sleep schedules 60s out regardless of actual visibility.

**Fix:** Don't schedule deep-sleep until `setVisible(false)` actually fires.

### 18. `BundleStore.effectiveValues` re-parses properties every call
**File:** `app/src/main/java/com/aetherdesk/lumenfall/storage/BundleStore.kt:151-164`

Called in `LumenfallWallpaperService.webRenderer` on every engine creation, in `BundleSettingsActivity.buildPreviewContainer` on every Settings open, in `BundleThumbnailer.WebCapture` on every thumbnail. Multiplied across bundles on a thumbnail sweep → dozens of JNI round-trips.

**Fix:** Same as #9 — memoize the parsed property list.

### 19. The `WallpaperNetworkPolicy` blocklist is hardcoded and not synced with ÆtherDesk
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/WallpaperNetworkPolicy.kt:105-131`

Doc comment claims "ported from ÆtherDesk's ContentRuleListManager rule lists." They will drift over time. No version pin, no test asserting canonical entries (`googletagmanager`, `doubleclick`, etc.) are present.

**Fix:** Move the list to `core-rust` so Rust screener and Kotlin network policy share one source. Add a test.

### 20. `MainActivity.rowFor` builds a `LinearLayout` programmatically for every row, on every refresh
**File:** `app/src/main/java/com/aetherdesk/lumenfall/ui/MainActivity.kt:238-293`

Combined with #11: 8 bundled + N imported bundles × 4 nested layouts each + MaterialButton = ~50–200 view allocations per refresh.

**Fix:** `RecyclerView` with a single ViewHolder pattern.

### 21. `SplitPaneLayout.handleDividerTouch` never calls `performClick()` on `ACTION_DOWN`
**File:** `app/src/main/java/com/aetherdesk/lumenfall/ui/SplitPaneLayout.kt:111-135`

Lint warning: `OnTouchListener.onTouch` that handles `ACTION_DOWN` and returns `true` without `performClick()` for screen-reader accessibility.

**Fix:** Call `performClick()` on `ACTION_DOWN`, or use `View.AccessibilityDelegate`.

### 22. `BundleFiles.findLivelyInfo` uses separator-count tiebreaker, but only files literally named `LivelyInfo.json` are candidates — so the count is moot
**File:** `app/src/main/java/com/aetherdesk/lumenfall/importer/BundleFiles.kt:36-39`

Not a bug, but the count-based logic looks misleading. A clarifying comment would help future readers.

### 23. `LumenfallCore.parseLivelyProperties` errors are silently swallowed
**File:** `core-rust/src/lib.rs:114-124`, `app/src/main/java/com/aetherdesk/lumenfall/storage/BundleProperty.kt:22-25`

Malformed `LivelyProperties.json` returns `{"ok": false, "error": "invalid JSON: ..."}`. `parseEnvelope` checks `ok` and returns empty list; the error string never surfaces. Settings UI shows "This bundle has no configurable properties" instead of "Couldn't parse properties."

**Fix:** Plumb the error message up.

### 24. `BundleStore.delete` clears `activeId` but doesn't notify the preview
Same root cause as #6.

### 25. `PerformanceGovernor.cpuPercent` ignores the WebView's sandboxed renderer process
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/PerformanceGovernor.kt:108-114`

`Process.getElapsedCpuTime()` measures only the host process. The renderer's CPU — where the expensive work happens — is invisible. The CPU signal is mostly blind to actual cost.

**Fix:** Document as known limitation, or read `/proc/<renderer-pid>/stat` periodically.

---

## Dead code / cosmetic

### 26. `web/WebWallpaperRenderer.kt` — defined, never referenced
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/WebWallpaperRenderer.kt`

30 lines, no callers. Spike leftover. Safe to delete.

### 27. `assets/spike/` (canvas + webgl probes) shipped as the "no bundle selected" default
**File:** `app/src/main/assets/spike/`

~5 KB of APK. Functional, but a "Pick a bundle to begin" placeholder would be more honest on first launch.

### 28. `WallpaperNetworkPolicy.isAlwaysBlocked` checks `metadata.azure.com`
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/WallpaperNetworkPolicy.kt:84-85`

Azure metadata is at `169.254.169.254` (covered by `isRawIpLiteral`). The hostname entry is dead code, harmless. `WebSocketGuard` has the same dead entry.

### 29. `MainActivity.activationProblem` returns a string message but the caller only toasts it
**File:** `app/src/main/java/com/aetherdesk/lumenfall/ui/MainActivity.kt:568-575`

Activation failure is shown as a 2.6s toast. For a problem that requires re-import, `messageDialog` would be more appropriate.

### 30. `BundleStore.kt` string-concat pref keys can theoretically collide if `id` ever contains `_`
**File:** `app/src/main/java/com/aetherdesk/lumenfall/storage/BundleStore.kt:211, 216`

Not exploitable today (imported ids are `b<timestamp>`, bundled ids are `bundled/<folder>`), but fragile if bundle id formats ever change.

**Fix:** Prefix with `__` or store as `Map<String, Any>` JSON-encoded.

### 31. `R.string.wallpaper_service_name` was flagged as potentially undefined
Verified: `app/src/main/res/values/strings.xml:3` defines it as `"Lumenfall Live Wallpaper"`. Not an issue.

---

## Suggested triage order

If a model is picking what to attack first, by user-impact × likely-to-bite:

1. **#11 + #20** — `RecyclerView` migration. Visible flicker on every thumbnail sweep; scales poorly past ~50 bundles. One dependency change, one Activity rewrite.
2. **#3** — Thumbnailer race. Real correctness bug, hard to reproduce but produces corrupted state under load.
3. **#8** — Unwrapped `JSONObject` on bundled overrides prefs. Trivial fix, real crash potential.
4. **#15** — Add `BundleImporterTest.kt`. The whole importer is untested and is the highest-risk surface in the codebase.

---

## Cross-cutting note for follow-up reviewers

The architecture is sound — these are integration bugs at the seams between modules that are individually well-designed. Three modules deserve special attention when reviewing future PRs:

- `WallpaperWebViewHost` (lifecycle of the renderer — most fragile, most fixes have lived here)
- `BundleWebView` (bridge contract — every addition needs to also touch the JS shim)
- `BundleImporter` (file IO + JNI + transactional cleanup — zero test coverage today)
