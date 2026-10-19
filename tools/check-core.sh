#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kotlinc >/dev/null || { echo 'kotlinc requis (Kotlin 1.9 ou supérieur)'; exit 1; }
mkdir -p build/checks
mapfile -t sources < <(find core/src/main/kotlin -name '*.kt' | sort)
kotlinc "${sources[@]}" core/src/test/kotlin/fr/soustitresvoix/core/CoreChecks.kt -include-runtime -d build/checks/core-checks.jar
java -jar build/checks/core-checks.jar "$@"
