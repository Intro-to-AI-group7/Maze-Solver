#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLASSES="$ROOT/build/classes"

mkdir -p "$CLASSES"
mapfile -t SOURCES < <(find "$ROOT/src" "$ROOT/test/java" -type f -name '*.java' -print)

(
    cd "$ROOT"
    javac -Xlint:all -d "$CLASSES" "${SOURCES[@]}"
    java -cp "$CLASSES" test.RegressionTests
    java -cp "$CLASSES" app.MS
)
