#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLASSES="$ROOT/build/classes"
JAVAC_BIN="$(readlink -f "$(command -v javac)")"
JAVA_HOME="$(cd "$(dirname "$JAVAC_BIN")/.." && pwd)"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
JAVA_MAJOR="$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')"
if [[ -z "$JAVA_MAJOR" || "$JAVA_MAJOR" -lt 21 ]]; then
    echo "JDK 21 or newer is required; found JDK ${JAVA_MAJOR:-unknown} at $JAVA_HOME." >&2
    exit 1
fi

mkdir -p "$CLASSES"
mapfile -t SOURCES < <(find "$ROOT/src" "$ROOT/test/java" -type f -name '*.java' -print)

(
    cd "$ROOT"
    "$JAVAC" -Xlint:all -d "$CLASSES" "${SOURCES[@]}"
    "$JAVA" -cp "$CLASSES" test.RegressionTests
    "$JAVA" -cp "$CLASSES" app.MS
)
