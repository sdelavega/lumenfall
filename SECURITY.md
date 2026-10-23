# Security policy

## Supported versions

Only the latest release receives fixes.

## Reporting a vulnerability

Please report security problems privately, using GitHub's
[private vulnerability reporting](https://github.com/sdelavega/lumenfall/security/advisories/new)
for this repository. Do not open a public issue for an unfixed vulnerability.

Include the Lumenfall version, your Android version and device, and steps or a
sample bundle that reproduce the problem. Expect an acknowledgement within a
week. Fixes are released as a new version and credited in its notes, unless you
prefer otherwise.

## Scope

Lumenfall runs third-party web wallpapers, so the interesting areas are the
bundle importer (zip extraction, screening), the WebView network policy and
WebSocket guard, the JavaScript bridge, and location access. A wallpaper that
escapes those, reads another app's data, reaches a blocked host, or gets
location without the per-wallpaper opt-in is a vulnerability.

WebSocket validation is best-effort: the check and the real connection resolve
the host separately, so DNS rebinding keeps a short window (documented in
`docs/ARCHITECTURE.md`). Reports that show a bypass that needs no race are in
scope; the race itself is a known limitation.

Out of scope: wallpapers that merely misbehave within their sandbox, such as
burning CPU or crashing, and issues that need a rooted or already-compromised
device.
