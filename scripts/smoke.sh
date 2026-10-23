#!/usr/bin/env bash
# Copyright (C) 2026 Stephen de la Vega
# SPDX-License-Identifier: GPL-3.0-or-later
# Device smoke run against the DEBUG build (the release build has no
# SmokeReceiver and a different signature; the release APK is checked by hand,
# see docs/releasing.md). Needs exactly one adb device.
# Usage: scripts/smoke.sh [--soak-minutes N] [--skip-install] [--dry-run]
#   --dry-run prints every adb command instead of running it (no device needed).
# Only keyevents and `am` commands are sent to the device; no system settings
# are changed and KEYCODE_POWER is never used (it would lock the device).
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=com.sdelavega.lumenfall
SERVICE="$PKG/.service.LumenfallWallpaperService"
MAIN_ACTIVITY="$PKG/.ui.MainActivity"
ACTION=com.sdelavega.lumenfall.SMOKE_SET_ACTIVE
SOAK_MINUTES=30
SKIP_INSTALL=0
DRY_RUN=0

while [ "$#" -gt 0 ]; do
    case "$1" in
        --soak-minutes) SOAK_MINUTES="${2:?--soak-minutes needs a number}"; shift 2 ;;
        --skip-install) SKIP_INSTALL=1; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) sed -n 5,9p "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
case "$SOAK_MINUTES" in ''|*[!0-9]*) echo "--soak-minutes must be a whole number" >&2; exit 2 ;; esac

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
if [ "$DRY_RUN" -eq 0 ] && [ ! -x "$ADB" ]; then
    echo "error: adb not found at $ADB (set ANDROID_HOME)" >&2
    exit 2
fi

OUT="smoke/$(date +%Y%m%d-%H%M%S)"
[ "$DRY_RUN" -eq 1 ] || mkdir -p "$OUT"

# adb <args...>: runs adb, or prints the command in dry-run mode.
adb() {
    if [ "$DRY_RUN" -eq 1 ]; then
        echo "+ adb $*"
    else
        "$ADB" "$@"
    fi
}

pause() { # pause <message>
    echo
    echo ">> $1"
    if [ "$DRY_RUN" -eq 1 ]; then echo "   (dry-run: not waiting)"; else read -r -p "   Press Enter when done... " _; fi
}

fail=0
confirm() { # confirm <question> <label>: a manual yes/no check that counts toward the result
    echo
    echo ">> $1"
    if [ "$DRY_RUN" -eq 1 ]; then echo "   (dry-run: not asking)"; echo "SKIP  $2"; return; fi
    local answer
    read -r -p "   [y/N] " answer
    case "$answer" in
        y|Y|yes|YES) echo "PASS  $2" ;;
        *) echo "FAIL  $2"; fail=1 ;;
    esac
}

snooze() { # snooze <seconds>
    if [ "$DRY_RUN" -eq 1 ]; then echo "+ sleep $1"; else sleep "$1"; fi
}

if [ "$DRY_RUN" -eq 0 ]; then
    count=$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
    if [ "$count" -ne 1 ]; then
        echo "error: need exactly one adb device, found $count" >&2
        exit 2
    fi
fi
echo "Output: $OUT"

# 1. Install the debug build.
if [ "$SKIP_INSTALL" -eq 0 ]; then
    apk=$(ls app/build/outputs/apk/debug/*.apk 2>/dev/null | head -n 1 || true)
    if [ -z "$apk" ]; then
        if [ "$DRY_RUN" -eq 1 ]; then apk="app/build/outputs/apk/debug/app-debug.apk"
        else echo "error: no debug APK; run ./gradlew :app:assembleDebug" >&2; exit 2; fi
    fi
    adb install -r "$apk"
fi

# 2. Choose the live wallpaper.
adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER \
    --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT "$SERVICE"
pause "Confirm Lumenfall in Android's wallpaper preview (tap its Set wallpaper button there)"

# 3. Clean log, then stream crash + main buffers to a file for the whole run: a
# single `logcat -d` after a long soak can lose early lines to the ring buffer.
adb logcat -c
LOGCAT_PID=""
stop_logcat() {
    if [ -n "$LOGCAT_PID" ]; then
        kill "$LOGCAT_PID" 2>/dev/null || true
        wait "$LOGCAT_PID" 2>/dev/null || true
        LOGCAT_PID=""
    fi
}
trap stop_logcat EXIT
if [ "$DRY_RUN" -eq 1 ]; then
    echo "+ adb logcat -b crash,main -v threadtime > $OUT/logcat.txt &"
else
    "$ADB" logcat -b crash,main -v threadtime > "$OUT/logcat.txt" &
    LOGCAT_PID=$!
fi

# 4. Each bundled wallpaper: select, wait, screenshot.
for dir in app/src/main/assets/wallpapers/*/; do
    folder=$(basename "$dir")
    id="bundled/$folder"
    echo "== $id"
    adb shell input keyevent KEYCODE_HOME
    adb shell am broadcast -a "$ACTION" -p "$PKG" --es id "$id"
    snooze 8
    if [ "$DRY_RUN" -eq 1 ]; then
        echo "+ adb exec-out screencap -p > $OUT/$folder.png"
    else
        "$ADB" exec-out screencap -p > "$OUT/$folder.png"
    fi
done

# 5. Manual checks.
pause "Import a test Lively zip, then change one of its properties live"
pause "Tap the imported bundle in Lumenfall and confirm it in Android's preview so it is the ACTIVE wallpaper, then open its Settings and (split-screen or floating window if you can) delete it from the library"
# The engine must fall back to the placeholder when the active bundle is deleted.
confirm "Press Home. Does the home screen now show the 'Pick a wallpaper to begin' placeholder (not the deleted bundle)?" \
    "delete-active falls back to the placeholder"
pause "Rotate the device (portrait to landscape and back)"

# 6. Soak: alternate hiding and showing the wallpaper every 60 s.
echo "== soak: $SOAK_MINUTES minute(s)"
if [ "$DRY_RUN" -eq 1 ]; then cycles=2; else cycles=$((SOAK_MINUTES)); fi
i=0
while [ "$i" -lt "$cycles" ]; do
    if [ $((i % 2)) -eq 0 ]; then
        adb shell am start -n "$MAIN_ACTIVITY"   # app in front hides the wallpaper
    else
        adb shell input keyevent KEYCODE_HOME    # home shows it again
    fi
    snooze 60
    i=$((i + 1))
done
adb shell input keyevent KEYCODE_HOME

# 7. Logcat scan (the streamed file; stop the stream first so it is complete).
stop_logcat
check() { # check <label> <pattern>
    local n
    if [ "$DRY_RUN" -eq 1 ]; then echo "+ grep -c '$2' $OUT/logcat.txt"; echo "SKIP  $1"; return; fi
    n=$(grep -c -e "$2" "$OUT/logcat.txt" || true)
    if [ "$n" -eq 0 ]; then echo "PASS  $1"; else echo "FAIL  $1 ($n match(es))"; fail=1; fi
}
echo "== logcat scan"
check "no FATAL EXCEPTION" "FATAL EXCEPTION"
check "no ANR in $PKG" "ANR in $PKG"
# WallpaperWebViewHost.handleFailure logs "bundle unrecoverable (<reason>)", e.g.
# "(no response for 30s)" from the watchdog, via the host's log sink.
check "no watchdog / bundle failure" "bundle unrecoverable"

# 8. #25 probe: can the app read the WebView renderer's /proc/<pid>/stat?
probe="$OUT/proc-probe.txt"
echo "== #25 probe"
if [ "$DRY_RUN" -eq 1 ]; then
    echo "+ adb shell ps -A | grep -i sandboxed_process"
    echo "+ adb shell run-as $PKG cat /proc/<renderer pid>/stat"
else
    pid=$("$ADB" shell ps -A 2>/dev/null | grep -i sandboxed_process | awk '{print $2}' | head -n 1 || true)
    if [ -z "$pid" ]; then
        echo "no sandboxed renderer process found" | tee "$probe"
    elif stat_out=$("$ADB" shell run-as "$PKG" cat "/proc/$pid/stat" 2>&1); then
        { echo "readable (renderer pid $pid)"; echo "$stat_out"; } | tee "$probe"
    else
        { echo "unreadable (renderer pid $pid)"; echo "$stat_out"; } | tee "$probe"
    fi
fi

echo
if [ "$DRY_RUN" -eq 1 ]; then echo "dry run complete"; exit 0; fi
echo "Screenshots, logcat.txt and proc-probe.txt are in $OUT"
exit "$fail"
