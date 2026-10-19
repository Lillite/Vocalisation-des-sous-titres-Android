#!/usr/bin/env bash
set -uo pipefail
mkdir -p build/verification/screenshots
gradle --no-daemon --stacktrace :app:connectedDebugAndroidTest > build/verification/device-console.txt 2>&1
status=$?
adb pull /sdcard/Android/data/fr.soustitresvoix.player.cinema/files/screenshots/ build/verification/screenshots/ >/dev/null 2>&1 || true
tail -n 180 build/verification/device-console.txt
exit "$status"
