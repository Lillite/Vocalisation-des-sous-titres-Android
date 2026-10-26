#!/usr/bin/env bash
# Optional diagnostics for the disposable synthetic-fixture emulator only.
# The caller owns a separate process group and terminates it after Gradle.
set -uo pipefail
if [[ "$#" != 2 || ! "$1" == emulator-* ]]; then
  echo 'Usage: sample-device-metrics.sh emulator-ID evidence-directory' >&2
  exit 2
fi
device="$1"
evidence="$2"
mkdir -p "$evidence" || exit 0
stop_requested=false
trap 'stop_requested=true' TERM INT
{
  printf 'nature=optional snapshots during deployment and mixed synthetic instrumented tests\n'
  printf 'application=fr.soustitresvoix.player.translation\n'
  printf 'sample_interval_seconds=30\nmaximum_samples=40\ncommand_timeout_seconds=5\n'
  printf 'No reset of frame counters. No cinematic, acoustic or phone-performance guarantee.\n'
} > "$evidence/monitor.txt" || true
printf 'sample\tfile\tcaptured_utc\tcommand_exit_status\n' > "$evidence/samples.tsv" || true

capture() {
  local kind="$1"
  shift
  local file captured_utc capture_status
  printf -v file '%03d-%s.txt' "$sample" "$kind"
  captured_utc=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  # --foreground keeps timeout/adb inside the monitor's group so the caller
  # can stop and reap the complete diagnostic tree promptly, including sleep.
  timeout --foreground 5s adb -s "$device" shell "$@" > "$evidence/$file" 2>&1
  capture_status=$?
  printf '%s\t%s\t%s\t%s\n' "$sample" "$file" "$captured_utc" "$capture_status" >> "$evidence/samples.tsv" || true
  return 0
}

for sample in {1..40}; do
  if [[ "$stop_requested" == true ]]; then break; fi
  sample_started=$SECONDS
  capture memory dumpsys meminfo fr.soustitresvoix.player.translation
  if [[ "$stop_requested" == true ]]; then break; fi
  capture frames dumpsys gfxinfo fr.soustitresvoix.player.translation framestats
  if [[ "$stop_requested" == true ]]; then break; fi
  capture cpu dumpsys cpuinfo
  if [[ "$stop_requested" == true || "$sample" == 40 ]]; then break; fi
  remaining=$((30 - (SECONDS - sample_started)))
  if [[ "$remaining" -gt 0 ]]; then sleep "$remaining" || true; fi
done
printf 'stop_requested=%s\nlast_sample=%s\n' "$stop_requested" "$sample" > "$evidence/monitor-finished.txt" || true
exit 0
