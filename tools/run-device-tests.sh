#!/usr/bin/env bash
set -uo pipefail
variant="${1:-debug}"
pass="${2:-1}"
if [[ "$#" -gt 2 || ! "$variant" =~ ^(debug|release)$ || ! "$pass" =~ ^[12]$ ]]; then
  echo 'Usage: run-device-tests.sh [debug|release] [1|2]' >&2
  exit 2
fi
if [[ "$variant" == release ]]; then
  evidence="build/verification/android-release-pass-$pass"
  task=(:app:connectedReleaseAndroidTest -PverifyRelease=true)
  reference=build/verification/apk-pair.sha256
  if [[ ! -f "${SUBTITRES_SIGNING_PROPERTIES:-}" || ! -f "$reference" ]]; then
    echo 'Release validation requires the CI signing properties and verified APK pair hashes.' >&2
    exit 2
  fi
else
  evidence=build/verification
  if [[ "$pass" != 1 ]]; then evidence="build/verification/android-debug-pass-$pass"; fi
  task=(:app:connectedDebugAndroidTest)
  reference=""
fi
apk="app/build/outputs/apk/$variant/app-$variant.apk"
test_apk="app/build/outputs/apk/androidTest/$variant/app-$variant-androidTest.apk"
reports=app/build/outputs/androidTest-results/connected
additional=app/build/outputs/connected_android_test_additional_output
mkdir -p "$evidence/screenshots"

# This script is for the disposable CI emulator, never a user's phone.
device="${ANDROID_SERIAL:-emulator-5554}"
if [[ "$device" != emulator-* ]]; then
  echo 'Refusing to change test-device settings on a non-emulator.' >&2
  exit 2
fi

collect_diagnostics() {
  original_status=$?
  adb -s "$device" pull /sdcard/Download/cinema03-tests/ "$evidence/screenshots/" >/dev/null 2>&1 || true
  adb -s "$device" shell dumpsys window > "$evidence/windows-after-tests.txt" 2>&1 || true
  adb -s "$device" logcat -d -t 8000 > "$evidence/logcat-after-tests.txt" 2>&1 || true
  return "$original_status"
}
trap collect_diagnostics EXIT

# With -no-boot-anim, an AOSP image may never publish init.svc.bootanim.
# Require completed boot and a responsive package service, and reject an
# animation still running. Retain ANRs instead of dismissing or retrying them.
ready=false
for attempt in {1..30}; do
  boot=$(adb -s "$device" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  animation=$(adb -s "$device" shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r')
  framework=$(adb -s "$device" shell pm path android 2>/dev/null | tr -d '\r')
  framework_ready=false
  if [[ "$framework" == package:* ]]; then framework_ready=true; fi
  printf 'attempt=%s sys.boot_completed=%q init.svc.bootanim=%q framework_ready=%s\n' \
    "$attempt" "$boot" "$animation" "$framework_ready" >> "$evidence/boot-readiness.txt"
  if [[ "$boot" == 1 && ( "$animation" == stopped || -z "$animation" ) && "$framework_ready" == true ]]; then
    ready=true
    break
  fi
  sleep 2
done
if [[ "$ready" != true ]]; then
  cat "$evidence/boot-readiness.txt"
  echo 'Emulator did not finish booting; instrumentation was not started.' >&2
  exit 2
fi
sdk=$(adb -s "$device" shell getprop ro.build.version.sdk | tr -d '\r')
abi=$(adb -s "$device" shell getprop ro.product.cpu.abi | tr -d '\r')
fingerprint=$(adb -s "$device" shell getprop ro.build.fingerprint | tr -d '\r')
printf 'sdk=%s\nabi=%s\nfingerprint=%s\n' "$sdk" "$abi" "$fingerprint" > "$evidence/device-properties.txt"
if [[ "$variant" == release && ( "$sdk" != 36 || "$abi" != x86_64 ) ]]; then
  echo 'Release validation requires the API 36 x86_64 emulator configured by CI.' >&2
  exit 2
fi
adb -s "$device" shell svc power stayon true >/dev/null || exit 2
adb -s "$device" shell settings put system screen_off_timeout 1800000 || exit 2
adb -s "$device" shell input keyevent KEYCODE_WAKEUP || exit 2
adb -s "$device" shell wm dismiss-keyguard || exit 2
# Complete only Android's one-time fullscreen tutorial on this disposable
# emulator. Its focusable SystemUI window can otherwise cover the activity
# after a delayed rotation; ANRs and application dialogs remain test failures.
# AOSP ImmersiveModeConfirmation observes this setting while SystemUI runs.
adb -s "$device" shell settings put secure immersive_mode_confirmations confirmed || exit 2
confirmation=$(adb -s "$device" shell settings get secure immersive_mode_confirmations | tr -d '\r')
printf 'immersive_mode_confirmations=%s\n' "$confirmation" >> "$evidence/boot-readiness.txt"
if [[ "$confirmation" != confirmed ]]; then
  echo 'The emulator fullscreen tutorial precondition was not applied.' >&2
  exit 2
fi
focused=false
for attempt in {1..30}; do
  adb -s "$device" shell dumpsys window > "$evidence/windows-before-tests.txt"
  if grep -q 'Application Not Responding:' "$evidence/windows-before-tests.txt"; then
    echo 'Emulator has an ANR window before instrumentation; inspect the captured diagnostics.' >&2
    exit 2
  fi
  if grep -q 'mCurrentFocus=Window{' "$evidence/windows-before-tests.txt"; then
    focused=true
    break
  fi
  sleep 2
done
if [[ "$focused" != true ]]; then
  echo 'Emulator has no focused UI window; instrumentation was not started.' >&2
  exit 2
fi

# Start each full pass with a new application/fixture process. This touches
# only the disposable emulator; no failure dialogs are dismissed or retried.
adb -s "$device" shell am force-stop fr.soustitresvoix.player.stable || exit 2
adb -s "$device" shell am force-stop fr.soustitresvoix.player.stable.test || exit 2
adb -s "$device" shell rm -rf /sdcard/Download/cinema03-tests || exit 2
rm -rf "$reports" "$additional" || exit 2
if [[ -n "$reference" ]]; then
  sha256sum --check "$reference" > "$evidence/apk-check-before.txt" 2>&1 || exit 2
fi
sha256sum "$apk" "$test_apk" > "$evidence/apk-pair-before.sha256" || exit 2
started_ns=$(python3 -c 'import time; print(time.time_ns())')
printf '%s\n' "$started_ns" > "$evidence/started-ns.txt"

# Do not use --rerun-tasks here: it can regenerate the demo and rebuild the
# APK. Removing only the old results ensures fresh instrumentation output.
gradle --no-daemon --stacktrace --no-build-cache "${task[@]}" > "$evidence/device-console.txt" 2>&1
status=$?
tail -n 180 "$evidence/device-console.txt"

# Check all method identities, so missing, duplicated or skipped tests cannot
# satisfy the release gate. These are actual device XML results, not inventory.
python3 tools/verify-test-results.py \
  --reports "$reports" --expected-count 68 --sources app/src/androidTest/java \
  --not-before-ns "$started_ns" --output "$evidence/android-test-summary.json"
report_status=$?
if [[ "$status" == 0 && "$report_status" != 0 ]]; then status=$report_status; fi
sha256sum "$apk" "$test_apk" > "$evidence/apk-pair-after.sha256"
hash_status=$?
if [[ "$hash_status" == 0 ]]; then
  cmp "$evidence/apk-pair-before.sha256" "$evidence/apk-pair-after.sha256"
  hash_status=$?
fi
if [[ "$status" == 0 && "$hash_status" != 0 ]]; then status=$hash_status; fi
# Preserve each pass before the next one removes the generated report folder.
# A failed copy must also fail validation; only optional device diagnostics
# in the EXIT trap are best effort.
for folder in "$reports" "$additional"; do
  if [[ -d "$folder" ]]; then
    cp -a "$folder" "$evidence/"
    archive_status=$?
    if [[ "$status" == 0 && "$archive_status" != 0 ]]; then status=$archive_status; fi
  fi
done
printf '%s\n' "$status" > "$evidence/exit-status.txt"
exit "$status"
