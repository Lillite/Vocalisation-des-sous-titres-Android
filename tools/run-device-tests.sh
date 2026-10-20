#!/usr/bin/env bash
set -uo pipefail
mkdir -p build/verification/screenshots
gradle --no-daemon --stacktrace :app:connectedDebugAndroidTest > build/verification/device-console.txt 2>&1
status=$?
adb -s emulator-5554 pull /sdcard/Download/cinema03-tests/ build/verification/screenshots/ >/dev/null 2>&1 || true
adb -s emulator-5554 shell dumpsys window > build/verification/windows-after-tests.txt 2>&1 || true
adb -s emulator-5554 logcat -d -t 4000 > build/verification/logcat-after-tests.txt 2>&1 || true
tail -n 180 build/verification/device-console.txt
exit "$status"
