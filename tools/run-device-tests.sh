#!/usr/bin/env bash
set -uo pipefail
mkdir -p build/verification/screenshots

# This script is for the disposable CI emulator, never a user's phone.
device="${ANDROID_SERIAL:-emulator-5554}"
if [[ "$device" != emulator-* ]]; then
  echo 'Refusing to change test-device settings on a non-emulator.' >&2
  exit 2
fi

collect_diagnostics() {
  adb -s "$device" pull /sdcard/Download/cinema03-tests/ build/verification/screenshots/ >/dev/null 2>&1 || true
  adb -s "$device" shell dumpsys window > build/verification/windows-after-tests.txt 2>&1 || true
  adb -s "$device" logcat -d -t 8000 > build/verification/logcat-after-tests.txt 2>&1 || true
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
    "$attempt" "$boot" "$animation" "$framework_ready" >> build/verification/boot-readiness.txt
  if [[ "$boot" == 1 && ( "$animation" == stopped || -z "$animation" ) && "$framework_ready" == true ]]; then
    ready=true
    break
  fi
  sleep 2
done
if [[ "$ready" != true ]]; then
  cat build/verification/boot-readiness.txt
  echo 'Emulator did not finish booting; instrumentation was not started.' >&2
  exit 2
fi
adb -s "$device" shell svc power stayon true >/dev/null
adb -s "$device" shell settings put system screen_off_timeout 1800000
adb -s "$device" shell input keyevent KEYCODE_WAKEUP
adb -s "$device" shell wm dismiss-keyguard
# Complete only Android's one-time fullscreen tutorial on this disposable
# emulator. Its focusable SystemUI window can otherwise cover the activity
# after a delayed rotation; ANRs and application dialogs remain test failures.
# AOSP ImmersiveModeConfirmation observes this setting while SystemUI runs.
adb -s "$device" shell settings put secure immersive_mode_confirmations confirmed || exit 2
confirmation=$(adb -s "$device" shell settings get secure immersive_mode_confirmations | tr -d '\r')
printf 'immersive_mode_confirmations=%s\n' "$confirmation" >> build/verification/boot-readiness.txt
if [[ "$confirmation" != confirmed ]]; then
  echo 'The emulator fullscreen tutorial precondition was not applied.' >&2
  exit 2
fi
focused=false
for attempt in {1..30}; do
  adb -s "$device" shell dumpsys window > build/verification/windows-before-tests.txt
  if grep -q 'Application Not Responding:' build/verification/windows-before-tests.txt; then
    echo 'Emulator has an ANR window before instrumentation; inspect the captured diagnostics.' >&2
    exit 2
  fi
  if grep -q 'mCurrentFocus=Window{' build/verification/windows-before-tests.txt; then
    focused=true
    break
  fi
  sleep 2
done
if [[ "$focused" != true ]]; then
  echo 'Emulator has no focused UI window; instrumentation was not started.' >&2
  exit 2
fi

gradle --no-daemon --stacktrace :app:connectedDebugAndroidTest > build/verification/device-console.txt 2>&1
status=$?
tail -n 180 build/verification/device-console.txt

# Gradle's final stack trace can push the actual failing assertion out of the
# log tail. Print the authoritative XML results after it, without retries or
# skipped-test allowances, and preserve the machine-readable summary.
python3 - <<'PY'
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

reports = sorted(Path('app/build/outputs/androidTest-results/connected').rglob('TEST-*.xml'))
summary = {'reports': len(reports), 'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
for path in reports:
    root = ET.parse(path).getroot()
    for case in root.iter('testcase'):
        summary['tests'] += 1
        for outcome in case:
            key = {'failure': 'failures', 'error': 'errors', 'skipped': 'skipped'}.get(outcome.tag)
            if key:
                summary[key] += 1
                print(f"{outcome.tag.upper()} {case.get('classname')}.{case.get('name')}")
                print(outcome.get('message', ''))
                print(outcome.text or '')
Path('build/verification/android-test-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print('Android instrumentation:', json.dumps(summary))
sys.exit(0 if summary['tests'] > 0 and not any(summary[k] for k in ('failures', 'errors', 'skipped')) else 1)
PY
report_status=$?
if [[ "$status" == 0 && "$report_status" != 0 ]]; then status=$report_status; fi
exit "$status"
