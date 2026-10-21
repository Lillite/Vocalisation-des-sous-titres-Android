#!/usr/bin/env bash
# Two complete passes; a failing pass is never hidden by the other pass.
set -uo pipefail
first=0
second=0
bash tools/run-device-tests.sh release 1 || first=$?
bash tools/run-device-tests.sh release 2 || second=$?
printf 'Android full-suite pass 1 exit=%s; pass 2 exit=%s\n' "$first" "$second"
[[ "$first" -eq 0 && "$second" -eq 0 ]]
