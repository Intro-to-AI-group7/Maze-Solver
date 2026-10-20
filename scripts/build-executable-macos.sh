#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLASSES="$ROOT/build/classes"
PACKAGE="$ROOT/build/package"
DIST="$ROOT/dist"
JAR="$PACKAGE/MazeSolver.jar"
VERSION="0.8.1"
JAVAC_BIN="$(command -v javac)"
JAVA_HOME="$(cd "$(dirname "$JAVAC_BIN")/.." && pwd)"
JAVAC="$JAVA_HOME/bin/javac"
JAR_TOOL="$JAVA_HOME/bin/jar"
JPACKAGE="$JAVA_HOME/bin/jpackage"

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "This script must run on macOS. jpackage creates native packages for the host OS." >&2
    exit 1
fi

JAVA_MAJOR="$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')"
if [[ -z "$JAVA_MAJOR" || "$JAVA_MAJOR" -lt 21 ]]; then
    echo "JDK 21 or newer is required; found JDK ${JAVA_MAJOR:-unknown} at $JAVA_HOME." >&2
    exit 1
fi

mkdir -p "$CLASSES" "$PACKAGE" "$DIST"
mapfile -t SOURCES < <(find "$ROOT/src" -type f -name '*.java' -print)

(
    cd "$ROOT"
    "$JAVAC" -Xlint:all -d "$CLASSES" "${SOURCES[@]}"
    rm -f "$JAR"
    "$JAR_TOOL" --create --file "$JAR" --main-class app.MS -C "$CLASSES" .
    rm -rf "$DIST/MazeSolver.app" "$DIST/MazeSolver-$VERSION.dmg"
    "$JPACKAGE" \
        --type dmg \
        --name MazeSolver \
        --input "$PACKAGE" \
        --main-jar "$(basename "$JAR")" \
        --main-class app.MS \
        --dest "$DIST" \
        --app-version "$VERSION" \
        --mac-package-name "Maze-Solver" \
        --mac-package-identifier "group7.introtoai.mazesolver" \
        --vendor "Intro-to-AI-group7"
)

echo "macOS installer created at: $DIST/MazeSolver-$VERSION.dmg"
echo "The package architecture matches the macOS JDK used to build it."
