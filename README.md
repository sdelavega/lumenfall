# Lumenfall

Lumenfall is an Android live wallpaper host for Lively-style wallpaper bundles.
It is a sibling experiment to ÆtherDesk: keep the bundle format and property
contract familiar, but use Android's native `WallpaperService` as the host.

The project intentionally keeps Android code thin. Kotlin owns platform glue:
the wallpaper service, import picker, Android lifecycle, storage prompts, and
JNI calls. Rust owns the portable engine: Lively parsing, validation, bundle
indexing, property normalization, and eventually renderer-independent policy.

Initial scope:

- Parse `LivelyInfo.json` and `LivelyProperties.json` in Rust.
- Import folder or zip based wallpaper bundles into app storage.
- Render static images and looping videos first.
- Add HTML/JS wallpapers through a WebView-backed renderer after testing the
  wallpaper-surface constraints on real devices.
- Preserve a small JS bridge compatible with ÆtherDesk where possible:
  `window.aetherDesk.properties`, `system`, `display`, suspend/resume, and
  property update callbacks.

This scaffold intentionally leaves the renderer thin. The first engineering
spike should prove which WebView-to-wallpaper-surface strategy is viable on
current Android tablets.

## Build notes

Rust core:

```bash
cargo test
```

Android shell:

```bash
./gradlew :app:assembleDebug
```

The Android build needs an SDK configured through `ANDROID_HOME` or a local
`local.properties` file with `sdk.dir=...`.
