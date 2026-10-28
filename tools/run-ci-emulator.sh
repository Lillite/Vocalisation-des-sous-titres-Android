#!/usr/bin/env bash
# Disposable CI AVD only. No boot-time key injection and no internal test retry.
set -euo pipefail
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_root" || ! -d "$sdk_root" ]]; then
  echo 'ANDROID_HOME or ANDROID_SDK_ROOT must identify the official Android SDK.' >&2
  exit 2
fi
export ANDROID_HOME="$sdk_root"
export PATH="$sdk_root/platform-tools:$sdk_root/emulator:$PATH"
# The runner's "latest" alias may still point at an older tool that resolves
# API 37 images but writes target=android-0 when creating their AVD metadata.
# The workflow installs this exact tool version before reaching this script.
cmdline_tools_bin="${ANDROID_CMDLINE_TOOLS_BIN:-$sdk_root/cmdline-tools/22.0/bin}"
sdkmanager="$cmdline_tools_bin/sdkmanager"
avdmanager="$cmdline_tools_bin/avdmanager"
adb="$sdk_root/platform-tools/adb"
emulator="$sdk_root/emulator/emulator"
for tool in "$sdkmanager" "$avdmanager" "$adb"; do
  if [[ ! -x "$tool" ]]; then echo "Required SDK tool is missing: $tool" >&2; exit 2; fi
done
command -v setsid >/dev/null
command -v timeout >/dev/null
port=5554
device="emulator-$port"
if [[ -n "${ANDROID_SERIAL:-}" && "$ANDROID_SERIAL" != "$device" ]]; then
  echo 'Refusing to replace or target another Android device.' >&2
  exit 2
fi
export ANDROID_SERIAL="$device"
evidence=build/verification/emulator-startup
mkdir -p "$evidence"
avd_home=""
emulator_pid=""
cleanup() {
  local original_status=$?
  trap - EXIT INT TERM
  set +e
  {
    printf 'captured_utc=%s\noriginal_exit_status=%s\nemulator_pid=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$original_status" "$emulator_pid"
    if [[ -n "$emulator_pid" ]]; then
      timeout -k 1s 5s "$adb" -s "$device" shell dumpsys window > "$evidence/windows-at-exit.txt" 2>&1
      printf 'windows_capture_exit=%s\n' "$?"
      timeout -k 1s 5s "$adb" -s "$device" logcat -d -t 8000 > "$evidence/logcat-at-exit.txt" 2>&1
      printf 'logcat_capture_exit=%s\n' "$?"
      timeout -k 1s 5s "$adb" -s "$device" emu kill > "$evidence/emulator-stop.txt" 2>&1
      printf 'emulator_stop_exit=%s\n' "$?"
      for attempt in {1..20}; do
        if ! kill -0 -- "-$emulator_pid" 2>/dev/null; then break; fi
        sleep 0.25
      done
      # Only the isolated process group created below, never Gradle or adb server.
      if kill -0 -- "-$emulator_pid" 2>/dev/null; then
        kill -TERM -- "-$emulator_pid" 2>/dev/null
        sleep 0.25
        kill -KILL -- "-$emulator_pid" 2>/dev/null
      fi
    fi
    if [[ -n "$avd_home" && "$avd_home" == */tts-ci-avd.* ]]; then
      timeout -k 1s 10s rm -rf -- "$avd_home"
      printf 'avd_cleanup_exit=%s\n' "$?"
    fi
    printf 'cleanup_finished_utc=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  } > "$evidence/cleanup.txt" 2>&1
  exit "$original_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Never adopt an existing emulator, even on a disposable runner.
timeout -k 1s 15s "$adb" start-server > "$evidence/adb-start.txt" 2>&1
timeout -k 1s 10s "$adb" devices > "$evidence/adb-devices-before.txt" 2>&1
if awk -v target="$device" 'NR > 1 && $1 == target { found=1 } END { exit !found }' "$evidence/adb-devices-before.txt"; then
  echo 'The dedicated emulator port is already occupied; refusing to touch it.' >&2
  exit 2
fi
# Android 17 is published under android-37.0, not android-37. The SDK
# repository's current schema resolves that exact stable AOSP image. Do not
# substitute an older Android image when resolution or installation fails.
image='system-images;android-37.0;default;x86_64'
if [[ ! -x "$emulator" || ! -f "$sdk_root/system-images/android-37.0/default/x86_64/package.xml" ]]; then
  timeout -k 1s 180s "$sdkmanager" --licenses < <(yes) > "$evidence/sdk-licenses.txt" 2>&1
  timeout -k 1s 600s "$sdkmanager" emulator "$image" > "$evidence/sdk-install.txt" 2>&1
else
  printf 'Official emulator and Android 17 / API 37.0 AOSP default x86_64 image already installed.\n' > "$evidence/sdk-install.txt"
fi
{
  date -u '+%Y-%m-%dT%H:%M:%SZ'
  printf 'cmdline_tools_bin=%s\n' "$cmdline_tools_bin"
  cat "$cmdline_tools_bin/../source.properties"
  timeout -k 1s 15s "$adb" version
  timeout -k 1s 30s "$sdkmanager" --version
  timeout -k 1s 15s "$emulator" -version
  cat "$sdk_root/system-images/android-37.0/default/x86_64/source.properties"
} > "$evidence/tool-versions.txt" 2>&1
avd_home=$(mktemp -d "${RUNNER_TEMP:-/tmp}/tts-ci-avd.XXXXXX")
export ANDROID_AVD_HOME="$avd_home"
avd_name=tts-ci-api37
printf 'no\n' | timeout -k 1s 120s "$avdmanager" create avd --name "$avd_name" --package "$image" --device pixel_2 > "$evidence/avd-create.txt" 2>&1
{
  printf 'hw.cpu.ncore=4\nhw.ramSize=2048\ndisk.dataPartition.size=2048M\n'
} >> "$avd_home/$avd_name.avd/config.ini"
cp "$avd_home/$avd_name.avd/config.ini" "$evidence/avd-config.ini"
cp "$avd_home/$avd_name.ini" "$evidence/avd.ini"
# Retain both AVD files before boot and reject the old tools' malformed target.
# Resolving the image or observing sys.boot_completed alone does not establish
# that the emulator was created for the requested Android platform.
if ! awk -F '=' '$1 == "target" { sub(/\r$/, "", $2); count++; print "observed_target=" $2; if ($2 == "android-37.0") valid++ } END { exit !(count == 1 && valid == 1) }' \
    "$evidence/avd.ini" > "$evidence/avd-target-check.txt"; then
  echo 'The AVD target is not exactly android-37.0; refusing to boot or run instrumentation.' >&2
  exit 2
fi
# Use the supported ANGLE + SwiftShader backend on this GPU-less runner.
# SystemUI timed out while the former legacy backend was warming shaders.
# Keep the same device, resources, readiness/ANR guards and complete test runs.
command=("$emulator" -port "$port" -avd "$avd_name" -cores 4 -memory 2048
  -accel on -no-window -gpu swangle -no-snapshot -noaudio -no-boot-anim -camera-back none)
printf '%q ' "${command[@]}" > "$evidence/emulator-command.txt"
printf '\n' >> "$evidence/emulator-command.txt"
date -u '+%Y-%m-%dT%H:%M:%SZ' > "$evidence/launched-utc.txt"
setsid "${command[@]}" > "$evidence/emulator-console.txt" 2>&1 &
emulator_pid=$!
printf '%s\n' "$emulator_pid" > "$evidence/emulator-pid.txt"
timeout -k 1s 120s "$adb" -s "$device" wait-for-device > "$evidence/adb-wait-for-device.txt" 2>&1
# A new AVD may show keyguard first. Establish a real drawn focused window
# before dismissing keyguard through its framework command, without a keyevent.
python3 tools/wait-emulator-ready.py --device "$device" --evidence-dir "$evidence/window-ready" --any-focused-window --timeout 180
timeout -k 1s 10s "$adb" -s "$device" shell svc power stayon true
timeout -k 1s 10s "$adb" -s "$device" shell wm dismiss-keyguard
python3 tools/wait-emulator-ready.py --device "$device" --evidence-dir "$evidence/launcher-ready" --expected-package com.android.launcher3 --timeout 120
for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
  timeout -k 1s 10s "$adb" -s "$device" shell settings put global "$setting" 0.0
  actual=$(timeout -k 1s 10s "$adb" -s "$device" shell settings get global "$setting" | tr -d '\r')
  printf '%s=%s\n' "$setting" "$actual" >> "$evidence/animation-settings.txt"
  if [[ "$actual" != 0 && "$actual" != 0.0 ]]; then
    echo "Emulator animation precondition was not applied: $setting=$actual" >&2
    exit 2
  fi
done
# Retain successful boot diagnostics before instrumentation replaces logcat.
# This includes the renderer's shader warm-up, not an application benchmark.
timeout -k 1s 10s "$adb" -s "$device" logcat -d -t 12000 > "$evidence/logcat-ready.txt" 2>&1
# The existing guards, exact APK hashes, one whole suite and failure statuses
# remain owned by the device runner. No failed suite is retried here.
bash tools/run-release-device-tests.sh
