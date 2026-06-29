# Spike: hosting a WebView on a wallpaper surface

Status: VALIDATED on real hardware (2026-06-29). Strategy B confirmed; see Results.
Owner: —
Goal: decide whether a WebView can render Lively-style web wallpapers directly
onto an Android live-wallpaper surface, and which mechanism to commit to.

## The problem

`WallpaperService.Engine` gives you a bare `Surface` (via `SurfaceHolder`), not a
window and not a `ViewGroup`. A `WebView` is a `View`: it needs to be attached to
a real, laid-out window to render, and it relies on the hardware-accelerated view
pipeline for anything non-trivial (CSS animation, 2D canvas, WebGL). So "just put
a WebView on the wallpaper" has no direct API — the surface and the view system
don't meet anywhere obvious. This spike exists to find the meeting point and
measure whether it's good enough.

## Candidate strategies

### A. Software draw: `webView.draw(canvas)` onto `lockCanvas()`

Measure + lay out a detached WebView at the surface size, then on each frame call
`holder.lockCanvas()` and `webView.draw(canvas)`, driving redraws yourself with a
`Choreographer` loop.

- Pros: trivial, no extra system objects, works on everything.
- Cons: software rendering. Hardware-accelerated layers (WebGL, many CSS/canvas
  paths) draw blank or fall back to slow paths. You become responsible for the
  frame clock. Realistically only good enough for static images and the simplest
  DOM wallpapers — i.e. not the interesting Lively content.

### B. VirtualDisplay + Presentation (chosen)

Create a `VirtualDisplay` backed by the wallpaper `Surface`
(`DisplayManager.createVirtualDisplay(..., holder.surface, ...)`), then show a
`Presentation` (a `Dialog` with its own window/context) on that virtual display
and put the WebView inside it.

- Pros: the WebView lives in a genuine, hardware-accelerated window. WebGL, 2D
  canvas, and CSS composite normally. The display owns its own frame clock, so
  `requestAnimationFrame` just works. This is the established approach in
  production video/web live-wallpaper apps.
- Cons: more lifecycle to manage (display + presentation must track surface
  created/changed/destroyed and visibility), and Presentation behaviour has some
  OEM variance. Density/size must be propagated on resize.

### C. Offscreen WebView → GL/texture pipeline

Render the WebView to an offscreen `SurfaceTexture` and blit to the wallpaper
surface via GL (optionally snapshotting to a `Bitmap`). This is the
"offscreen snapshot pipeline" the architecture doc flags as a fallback.

- Pros: full control, room for post-processing (scaling, crossfade between
  wallpapers).
- Cons: the most code and the most ways to get frame pacing / lifecycle wrong.
  Only worth it if B proves unworkable or we need compositing effects B can't do.

## Decision

Commit the spike to **B (VirtualDisplay + Presentation)** as the primary path,
because it's the only option that renders the hardware-accelerated web content
Lively bundles actually use. Keep **A** in our back pocket as a smoke test and as
a possible degraded mode for static-image bundles on devices where B misbehaves.
Hold **C** in reserve for crossfade/compositing work later.

## What landed

Flag-gated so the placeholder engine still works (`USE_WEBVIEW_SPIKE` in
`LumenfallWallpaperService`).

- `web/WallpaperWebViewHost.kt` — owns the `VirtualDisplay` + `Presentation`
  lifecycle and a sandboxed `WebView`. Driven from the Engine callbacks:
  `onSurface` (create/resize), `setVisible` (pause/resume), `detach`
  (surface destroyed), `release` (engine destroyed).
- `service/LumenfallWallpaperService.kt` — wires the host into the engine behind
  the flag; falls back to the `Canvas` placeholder when off.
- `assets/spike/index.html` — a self-contained animated-canvas bundle that shows
  an FPS HUD and posts heartbeat + fps back over the native bridge, so a real
  device proves: compositing works, the frame clock runs, and the bridge
  round-trips.
- A minimal `window.aetherDesk` shim (in the host) so bundles find
  `properties` / `system` / `display` / `postMessage` — a first step toward the
  ÆtherDesk bridge contract.

### Sandbox / security choices

The WebView disables `allowFileAccess`, `allowContentAccess`,
`allowFileAccessFromFileURLs`, and `allowUniversalAccessFromFileURLs`. Assets
under `file:///android_asset/` still load (documented exemption of
`setAllowFileAccess`). For real imported bundles, move to `WebViewAssetLoader`
serving from a virtual `https://appassets.androidplatform.net/` origin instead of
raw `file://`, so bundles run under a normal web origin with no filesystem reach.

## How to run

1. `USE_WEBVIEW_SPIKE = true` is already set in `LumenfallWallpaperService`.
2. Build + install: `./gradlew :app:installDebug`.
3. Set Lumenfall as the live wallpaper (Settings → Wallpaper, or via the picker).
4. Expect the animated particle ring with a live FPS HUD.
5. `adb logcat -s LumenfallWallpaper LumenfallWebHost` should show recurring
   `web heartbeat` / `web bridge: {"heartbeat":true,"fps":N}` lines.

(The Rust `.so` is not required for this spike — it exercises only the Kotlin +
WebView path.)

## Validation checklist (do this on real hardware)

Test matrix: at least one mid-range and one flagship Android tablet, plus a
foldable if available; cover Android 8 (minSdk 26) through current.

- [x] WebView actually composites to the wallpaper (not black, not the placeholder).
- [x] Sustained FPS for a canvas bundle (target ~60, note thermal throttling).
- [x] **WebGL** bundle renders (the real test of strategy B vs A).
- [ ] Battery / GPU cost over 30–60 min idle on the home screen.
- [ ] Memory footprint and stability across hours (no leak across
      surface destroy/recreate cycles — rotate, lock/unlock, switch apps).
- [x] `onVisibilityChanged(false)` genuinely halts work (CPU ~0 when home screen
      is covered); resume restores animation.
- [ ] Rotation / multi-window / fold-unfold resizes correctly via
      `VirtualDisplay.resize`.
- [~] Behaviour in the wallpaper **preview** pane (a second engine instance) —
      preview renders correctly; two-at-once leak not yet stress-tested.
- [x] OEM spread: confirm `Presentation.show()` from a service context is allowed
      (the host logs and bails if not).

## Results (Pixel Tablet, Android 17 beta / API 37, debug build, 2026-06-29)

Validated by side-loading via `adb` and setting Lumenfall as the live wallpaper.

- **Compositing works.** The canvas test bundle and a WebGL fragment-shader
  bundle (`assets/spike/webgl.html`) both render on the wallpaper surface via
  VirtualDisplay + Presentation — not black, not the placeholder.
- **WebGL renders** — a fullscreen fragment-shader plasma composites correctly.
  This is the decisive B-vs-A result: software draw (A) cannot do this.
- **Frame clock runs** — canvas bundle held ~61 fps; `requestAnimationFrame`
  drives steadily on the service-owned virtual display.
- **Bridge round-trips** — `web heartbeat` logs at a steady ~1 Hz over
  `aetherDeskNative.postMessage`.
- **Idle-when-hidden passes (battery-critical).** Covering the wallpaper with an
  opaque activity fired `onVisibilityChanged(false)`; process CPU dropped to
  **0.0%** and heartbeats ceased entirely. Returning to home resumed both.
- **Presentation from a service context is allowed** on this Pixel / Android 17 —
  the flagged OEM risk did not bite here.

### New findings / caveats

- **Visible CPU is high:** ~111% (≈1.1 cores) for the busy 60 fps canvas in a
  *debug* build. Needs a release-build + frame-pacing pass before it's
  battery-acceptable; not a spike blocker. (Hidden CPU is 0, which is what
  matters most.)
- **The shim-injection race is real, observed live.** The WebGL probe's one-shot
  GPU-renderer report (sent at script start) never reached native because
  `window.aetherDesk` did not exist yet — the shim is injected at
  `onPageFinished`, after first paint. Recurring heartbeats get through once the
  shim lands. This confirms the "shim injection timing" risk below and makes the
  document-start-injection follow-up a real requirement, not just hygiene.
- **Reloading after reinstall:** `adb shell am force-stop` on the wallpaper
  package *unsets* Lumenfall as the wallpaper (system reverts to default). To
  reload new code, re-select it in the live-wallpaper chooser instead.

## Known risks / open questions

- **Presentation from a service context.** Works in practice for app-owned
  virtual displays, but is the kind of thing an OEM can break. The host catches
  and logs `show()` failure instead of crashing.
- **`pauseTimers()` is process-global.** Fine while the wallpaper is the only
  WebView; revisit if the importer/preview ever runs a WebView concurrently.
- **Shim injection timing.** `onPageFinished` runs after first paint, so a
  bundle's startup script may run before `window.aetherDesk` exists. The test
  bundle polls to tolerate this; production should use androidx.webkit
  `WebViewCompat.addDocumentStartJavaScript()` so the bridge exists before any
  bundle code runs.
- **Audio types.** `webaudio` / `videostream` map to web/video kinds but their
  audio policy on a wallpaper is undecided (probably muted by default).

## Follow-ups if B passes

1. ~~Replace `file://` asset loading with `WebViewAssetLoader`.~~ DONE
   (2026-06-29): bundles serve from `https://appassets.androidplatform.net/assets/`
   with `allowFileAccess=false`. Verified loading + rendering on device.
2. Bridge injection: ~~move the shim to document-start injection
   (androidx.webkit).~~ DONE (2026-06-29): `WebViewCompat.addDocumentStartJavaScript`
   when `DOCUMENT_START_SCRIPT` is supported, post-load fallback otherwise.
   Verified: the WebGL bundle's one-shot startup report now reaches native
   (`web bridge: {"webgl":true,"renderer":"Mali-G710"}`) — previously lost to the
   race. Bridge contract: DONE (2026-06-29) — `properties` baked in at
   document-start, `display` (incl. dpr), `system` suspend/resume, and
   `onPropertyChange` (+ Lively `livelyPropertyListener`) fired on change.
   Properties are parsed on import, edited in a per-bundle settings screen, and
   persisted as overrides; verified a bundle renders its configured values
   (`rings=22 palette=Cool`). Live push is best-effort (engine must be
   co-resident); the reliable path is inject-on-recreate.
3. Video rendering: DONE (2026-06-29) — Media3 ExoPlayer renders straight onto
   the wallpaper surface (`setVideoSurfaceHolder`), looping + muted, paused while
   hidden. Verified on device. A GL layer for crossfade/consistent scaling is a
   possible later refinement, not needed for basic playback.
4. Renderer routing: DONE (2026-06-29) — `WallpaperRenderer` interface with
   web/video/image implementations; the Engine picks one off the active bundle's
   `kind` (from the Rust core). Verified all three render on device. GIF/WebP
   animation: DONE (2026-06-29) — image bundles now use `AnimatedImageDrawable`
   when Android can decode the source as an animated image, and pause while the
   wallpaper is hidden. Still open: aspect/crop options for video; `unsupported`
   kinds still fall back to the default.
