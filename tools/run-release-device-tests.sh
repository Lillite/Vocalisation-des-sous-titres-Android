#!/usr/bin/env bash
# One complete pass before merge. An error is retained, never retried silently.
set -euo pipefail
bash tools/run-device-tests.sh release 1
