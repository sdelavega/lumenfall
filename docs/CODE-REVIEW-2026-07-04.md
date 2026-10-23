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

**Resolution:** Resolved in Task 15 (b41ba54..ce99678). "Now live" is shown only after the system chooser returns and `WallpaperManager` reports our component active; the already-active branch says "Switched to <title>" after the in-place swap succeeds.

---

### 2. `BundleImporter.collectScriptSource` swallows per-file exceptions silently
**File:** `app/src/main/java/com/aetherdesk/lumenfall/importer/BundleImporter.kt:104-113`

`runCatching { ... }` with no logging means a script file that fails to decode (bad UTF-8, IO error mid-read, symlink loop) silently disappears from the screener's input. The bundle screens `allowed` against an incomplete view of its JS — exactly when screening matters most.

**Fix:** Log skipped files; if more than ~1% skip, treat as `limited` rather than `allowed`.

**Resolution:** Resolved in Task 12 (2b4d665..0f61725, with Rulings R11/R12). The importer logs every skipped script file; any skip (including files dropped by the total cap) downgrades an `allowed` screening result to `limited` with a reason. Covered by `BundleImporterTest`.

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

**Resolution:** Resolved in Task 8 (37ddcf5..8602bdd). Thumbnail generation runs through a single serial, de-duplicated queue; the `running` flag and recursion are gone, and the target is cleared when the queue idles.

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

**Resolution:** Resolved in Task 5 (47893ca..8608e35). All buses deliver listeners on the main thread through `MainThreadDispatcher`; unit-tested with an injectable dispatcher.

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

**Resolution:** Resolved in Task 7 (6164813..bed0696) and Task 6 (8608e35..37ddcf5). The shim sends no tick while suspended, and the host ignores ticks while rendering is not allowed (`renderAllowed`; Ruling R6 and the Task 6 reopen).

---

### 6. `BundleStore.delete()` doesn't tear down `BundleSettingsActivity` preview
**File:** `app/src/main/java/com/aetherdesk/lumenfall/storage/BundleStore.kt:80-86`

If the deleted bundle was the live preview's bundle in `BundleSettingsActivity`, the preview WebView stays alive and rendering. `ContactedDomains.clear` is called but no preview teardown.

**Repro:** Open Settings for a bundle. Long-press the bundle in Main, delete it. Return to Settings. Preview keeps running.

**Fix:** Push bundle deletion through a new `BundleDeletedBus`, or have `BundleSettingsActivity` check `store.get(id) == null` in `onResume` and `finish()`.

**Resolution:** Resolved in Task 5 (`BundleDeletedBus`, 47893ca..8608e35), Task 10 (store notifies, 46c62b8..bd01f26) and Task 11 (settings screen tears down its preview and finishes, also checks the bundle in `onResume`; bd01f26..2b4d665).

---

### 7. `WebSocketGuard.validate()` can pin a thread for ~30s per slow DNS resolution
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/WebSocketGuard.kt:34-44`

Each WebSocket connection spawns a fresh `Thread` that calls `InetAddress.getAllByName(host)` synchronously. A bundle opening 10 concurrent WebSockets against slow-resolution hosts pins 10 threads for up to ~30s each. Thread pool starvation.

**Fix:** Concurrent-in-flight cap (same pattern as `NativeFetchBridge.MAX_CONCURRENT = 8`), or a TTL-bound DNS cache.

**Resolution:** Resolved in Task 14 (96053e7..b41ba54). Validation runs on a bounded executor (8 in flight, extras rejected with a clear error) with a short DNS cache; Ruling R16 fixes the TTL at 5 s with a documented TOCTOU window.

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

**Resolution:** Resolved in Task 10 (46c62b8..bd01f26). All override reads, bundled and imported, tolerate corrupt JSON and fall back to empty overrides with a log line.

---

### 9. `BundleStore.properties()` re-runs the Rust `parseLivelyProperties` JNI call on every read
**File:** `app/src/main/java/com/aetherdesk/lumenfall/storage/BundleStore.kt:116-127`

The bundled envelope is constant for the install lifetime. `MainActivity.refresh()` calls `store.properties(meta.id)` for every bundle on every refresh; `BundleSettingsActivity.onCreate` calls it once per property; `effectiveValues` calls it twice. Comment on line 118 says "cached per call" — meaning the author knew caching was needed and didn't implement it.

**Fix:** `private val cachedProps: MutableMap<String, List<BundleProperty>>` keyed by id, invalidated on import/delete.

**Resolution:** Resolved in Task 9 (8602bdd..46c62b8) and Task 10 (46c62b8..bd01f26). The parsed property schema is cached per bundle id and invalidated on import, delete and bundled-asset upgrade.

---

### 10. `WallpaperWebViewHost` mutates state from non-main threads
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/WallpaperWebViewHost.kt:206-213`

`markFailed()` is invoked from the `WallpaperPresentation.onIrrecoverable` callback, which is invoked from `BundleWebView.onRenderProcessGone`, which Android delivers on a *WebView-internal thread*. `failed`/`virtualDisplay`/`presentation` are read/written from there and from the main-thread `onSurface`. Currently safe by convention; one future change to the WebViewClient wiring breaks it.

**Fix:** `@Synchronized` on the fields, or hop to main via `mainHandler.post` before any mutation.

**Resolution:** Resolved in Task 6 (8608e35..37ddcf5). `WallpaperWebViewHost` state is mutated on the main thread only; render-process-gone and other WebView-thread callbacks hop through the main handler.

---

### 11. `MainActivity.refresh()` rebuilds the whole list and loses scroll position
**File:** `app/src/main/java/com/aetherdesk/lumenfall/ui/MainActivity.kt:208-236`

Called from: `onResume`, after import, after delete, every `BundleThumbnailer.generateMissing` callback, after `setActiveAndPreview`. Importing a bundle without a preview causes a full list rebuild ~8 times in a row (once per thumbnail that lands). Visible flicker on slower devices.

**Fix:** `RecyclerView` with `DiffUtil`.

**Resolution:** Resolved in Task 15 (b41ba54..ce99678). The library is a `RecyclerView` with `ListAdapter`/`DiffUtil`; a finished thumbnail updates one row.

---

### 12. `WallpaperWebViewHost.enterDeepSleep()` allocates a ~16MB bitmap outside a `try/finally`
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/WallpaperWebViewHost.kt:284-303`

For a 1600×2560 tablet: 16MB per call, allocated before `PixelCopy`. If `PixelCopy` callback throws or never fires (low-memory OOM during GC), bitmap leaks until the lambda runs. No `recycle()` on the failure path.

**Fix:** Allocate inside the lambda; `recycle()` in `finally` after draw.

**Resolution:** Resolved in Task 6 (8608e35..37ddcf5). The deep-sleep snapshot bitmap is allocated inside the PixelCopy flow and recycled on every path.

---

## Architectural smells / robustness gaps

### 13. `BundleProperty.raw` is an untyped `JSONObject` — every consumer does stringly-typed lookups
**File:** `app/src/main/java/com/aetherdesk/lumenfall/storage/BundleProperty.kt:14-18`

UI does `p.raw.optString("folder")`, `optJSONArray("items")`, `optString("filter")` — typo'd keys silently return empty. Replace with a typed `PropertySchema` sealed hierarchy: `Slider(min, max, step)`, `Dropdown(items)`, `FolderDropdown(folder, filter, extensions)`.

**Resolution:** Resolved in Task 9 (8602bdd..46c62b8) and Task 11 (bd01f26..2b4d665). `PropertySchema` is a typed sealed hierarchy parsed once; the settings UI is driven from it, with no string-keyed lookups. `BundleProperty` is removed.

### 14. `BundleWebView.buildShim` is 850 lines of embedded JS in a Kotlin string
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/BundleWebView.kt:332-852`

Not testable from JUnit, not linted, no syntax highlighting beyond what IntelliJ does for multiline strings. The shim is *the* bridge contract — a single typo and every bundle breaks.

**Fix:** Move to `app/src/main/assets/aetherdesk-shim.js`, load via `assets.open(...).bufferedReader().use { it.readText() }`. Now lintable, formattable, tree-sitterable.

**Resolution:** Resolved in Task 7 (6164813..bed0696). The shim lives in `assets/bridge/aetherdesk-shim.js`, read once and cached by `ShimSource`; `just shim-check` runs `node --check` on it and a JVM test checks its entry points.

### 15. No tests for the Kotlin layer that orchestrates the Rust core
**File:** `app/src/test/java/com/aetherdesk/lumenfall/importer/BundleFilesTest.kt` (only one)

The orchestrating logic in `BundleImporter`, `BundleStore`, `WallpaperWebViewHost`, `BundleThumbnailer` has zero unit tests. `BundleImporter` is the highest-risk surface — file IO + JSON parsing + transactional cleanup + JNI — and one bug there loses user data.

**Fix:** `BundleImporterTest.kt`: (a) zip-slip throws + dir cleaned, (b) invalid LivelyInfo throws + dir cleaned, (c) screening rejection + dir cleaned, (d) valid + screening ok + meta + screening.json written.

**Resolution:** Resolved in Task 12 (2b4d665..0f61725). The importer depends on a narrow `CoreApi`; `BundleImporterTest` covers zip-slip, invalid `LivelyInfo`, screening rejection and the valid path.

### 16. `GeolocationProvider.bestEffortLabel` blocks up to ~5s on devices without a geocoder
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/GeolocationProvider.kt:158-170`

`Geocoder.getFromLocation` blocks when no system geocoder is installed (e.g., Pixel Tablet without Play Services). `runCatching` catches the timeout but the 5 seconds is still spent. A bundle calling `getCurrentPosition` in a tight loop wedges the UI thread.

**Fix:** Use `Geocoder.isPresent` to skip; make the lookup async if present.

**Resolution:** Resolved in Task 14 (96053e7..b41ba54) per Ruling R15. Reverse geocoding is skipped without a `Geocoder`, and otherwise runs asynchronously; the position result stays immediate and the label comes from a coordinate cache filled for the next request.

### 17. `WallpaperWebViewHost.applyPerformance` schedules deep-sleep at engine creation even when not hidden
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/WallpaperWebViewHost.kt:121-124`

At creation, `contentVisible` defaults to `false`; `applyPerformance` is called → deep-sleep schedules 60s out regardless of actual visibility.

**Fix:** Don't schedule deep-sleep until `setVisible(false)` actually fires.

**Resolution:** Resolved in Task 6 (8608e35..37ddcf5). Deep sleep is scheduled only after an actual `setVisible(false)`.

### 18. `BundleStore.effectiveValues` re-parses properties every call
**File:** `app/src/main/java/com/aetherdesk/lumenfall/storage/BundleStore.kt:151-164`

Called in `LumenfallWallpaperService.webRenderer` on every engine creation, in `BundleSettingsActivity.buildPreviewContainer` on every Settings open, in `BundleThumbnailer.WebCapture` on every thumbnail. Multiplied across bundles on a thumbnail sweep → dozens of JNI round-trips.

**Fix:** Same as #9 — memoize the parsed property list.

**Resolution:** Resolved in Task 10 (46c62b8..bd01f26), together with #9: `effectiveValues` uses the cached schema.

### 19. The `WallpaperNetworkPolicy` blocklist is hardcoded and not synced with ÆtherDesk
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/WallpaperNetworkPolicy.kt:105-131`

Doc comment claims "ported from ÆtherDesk's ContentRuleListManager rule lists." They will drift over time. No version pin, no test asserting canonical entries (`googletagmanager`, `doubleclick`, etc.) are present.

**Fix:** Move the list to `core-rust` so Rust screener and Kotlin network policy share one source. Add a test.

**Resolution:** Resolved in Task 13 (0f61725..c612ab9) per Rulings R13/R14. The blocklist lives in `core-rust/src/blocklist.rs` and is exposed over JNI (`blocklistJson`); the screener and the Kotlin network policy share it, with tests asserting canonical entries. `ipapi.co` and `api.bigdatacloud.net` are additionally blocked for every bundle.

### 20. `MainActivity.rowFor` builds a `LinearLayout` programmatically for every row, on every refresh
**File:** `app/src/main/java/com/aetherdesk/lumenfall/ui/MainActivity.kt:238-293`

Combined with #11: 8 bundled + N imported bundles × 4 nested layouts each + MaterialButton = ~50–200 view allocations per refresh.

**Fix:** `RecyclerView` with a single ViewHolder pattern.

**Resolution:** Resolved in Task 15 (b41ba54..ce99678), together with #11: one view-holder layout per row.

### 21. `SplitPaneLayout.handleDividerTouch` never calls `performClick()` on `ACTION_DOWN`
**File:** `app/src/main/java/com/aetherdesk/lumenfall/ui/SplitPaneLayout.kt:111-135`

Lint warning: `OnTouchListener.onTouch` that handles `ACTION_DOWN` and returns `true` without `performClick()` for screen-reader accessibility.

**Fix:** Call `performClick()` on `ACTION_DOWN`, or use `View.AccessibilityDelegate`.

**Resolution:** Resolved in Task 20 (2b83c5e..ab628b2, lint-clean pass at 05eb974). The divider calls `performClick()` and exposes accessibility actions to widen and narrow the pane.

### 22. `BundleFiles.findLivelyInfo` uses separator-count tiebreaker, but only files literally named `LivelyInfo.json` are candidates — so the count is moot
**File:** `app/src/main/java/com/aetherdesk/lumenfall/importer/BundleFiles.kt:36-39`

Not a bug, but the count-based logic looks misleading. A clarifying comment would help future readers.

**Resolution:** Resolved in Task 12 (2b4d665..0f61725). A comment on `BundleFiles.findLivelyInfo` explains the shallowest-match tiebreak.

### 23. `LumenfallCore.parseLivelyProperties` errors are silently swallowed
**File:** `core-rust/src/lib.rs:114-124`, `app/src/main/java/com/aetherdesk/lumenfall/storage/BundleProperty.kt:22-25`

Malformed `LivelyProperties.json` returns `{"ok": false, "error": "invalid JSON: ..."}`. `parseEnvelope` checks `ok` and returns empty list; the error string never surfaces. Settings UI shows "This bundle has no configurable properties" instead of "Couldn't parse properties."

**Fix:** Plumb the error message up.

**Resolution:** Resolved in Task 9 (8602bdd..46c62b8) and Task 11 (bd01f26..2b4d665). Property parsing returns a result carrying the Rust error message, and Settings shows "Couldn't read this wallpaper's settings: <reason>".

### 24. `BundleStore.delete` clears `activeId` but doesn't notify the preview
Same root cause as #6.

**Resolution:** Resolved with #6 (Tasks 5, 10 and 11): the deletion bus notifies the settings preview.

### 25. `PerformanceGovernor.cpuPercent` ignores the WebView's sandboxed renderer process
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/PerformanceGovernor.kt:108-114`

`Process.getElapsedCpuTime()` measures only the host process. The renderer's CPU — where the expensive work happens — is invisible. The CPU signal is mostly blind to actual cost.

**Fix:** Document as known limitation, or read `/proc/<renderer-pid>/stat` periodically.

**Resolution:** Resolved by Rulings R17/R18 in Task 16 (ce99678..63a5de1). The governor's existing achieved-fps jank signal is the renderer-cost proxy; the jank math is extracted and unit-tested, empty windows are not jank, and the CPU limitation is documented in `docs/ARCHITECTURE.md`. The on-device `/proc` probe belongs to the smoke run.

---

## Dead code / cosmetic

### 26. `web/WebWallpaperRenderer.kt` — defined, never referenced
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/WebWallpaperRenderer.kt`

30 lines, no callers. Spike leftover. Safe to delete.

**Resolution:** Resolved in Task 15 (b41ba54..ce99678). The file is deleted.

### 27. `assets/spike/` (canvas + webgl probes) shipped as the "no bundle selected" default
**File:** `app/src/main/assets/spike/`

~5 KB of APK. Functional, but a "Pick a bundle to begin" placeholder would be more honest on first launch.

**Resolution:** Resolved in Task 15 (b41ba54..ce99678). `assets/spike/` is removed; a "Pick a wallpaper to begin" placeholder page (`assets/placeholder/index.html`) renders when no bundle is active.

### 28. `WallpaperNetworkPolicy.isAlwaysBlocked` checks `metadata.azure.com`
**File:** `app/src/main/java/com/aetherdesk/lumenfall/web/WallpaperNetworkPolicy.kt:84-85`

Azure metadata is at `169.254.169.254` (covered by `isRawIpLiteral`). The hostname entry is dead code, harmless. `WebSocketGuard` has the same dead entry.

**Resolution:** Resolved in Task 13 (0f61725..c612ab9). The dead entries are removed from the policy and the guard; raw IPs stay covered.

### 29. `MainActivity.activationProblem` returns a string message but the caller only toasts it
**File:** `app/src/main/java/com/aetherdesk/lumenfall/ui/MainActivity.kt:568-575`

Activation failure is shown as a 2.6s toast. For a problem that requires re-import, `messageDialog` would be more appropriate.

**Resolution:** Resolved in Task 15 (b41ba54..ce99678). Activation failures show a dialog with the reason and a re-import hint.

### 30. `BundleStore.kt` string-concat pref keys can theoretically collide if `id` ever contains `_`
**File:** `app/src/main/java/com/aetherdesk/lumenfall/storage/BundleStore.kt:211, 216`

Not exploitable today (imported ids are `b<timestamp>`, bundled ids are `bundled/<folder>`), but fragile if bundle id formats ever change.

**Fix:** Prefix with `__` or store as `Map<String, Any>` JSON-encoded.

**Resolution:** Resolved in Task 10 (46c62b8..bd01f26) and Task 17 (1f8af3c..2db42d0). `PrefKeys` URL-encodes ids, and a versioned one-time migration moves existing keys.

### 31. `R.string.wallpaper_service_name` was flagged as potentially undefined
Verified: `app/src/main/res/values/strings.xml:3` defines it as `"Lumenfall Live Wallpaper"`. Not an issue.

**Resolution:** No change needed; the string is defined, as the item says.

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
