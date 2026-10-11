#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLASSES="$ROOT/build/classes"
PACKAGE="$ROOT/build/package"
DIST="$ROOT/dist"
JAR="$PACKAGE/MazeSolver.jar"
PORTABLE_ZIP="$DIST/MazeSolver-portable-linux.zip"
JAVAC_BIN="$(readlink -f "$(command -v javac)")"
JAVA_HOME="$(cd "$(dirname "$JAVAC_BIN")/.." && pwd)"
JAVAC="$JAVA_HOME/bin/javac"
JAR_TOOL="$JAVA_HOME/bin/jar"
JPACKAGE="$JAVA_HOME/bin/jpackage"
JAVA_MAJOR="$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')"
if [[ -z "$JAVA_MAJOR" || "$JAVA_MAJOR" -lt 21 ]]; then
    echo "JDK 21 or newer is required; found JDK ${JAVA_MAJOR:-unknown} at $JAVA_HOME." >&2
    exit 1
fi

mkdir -p "$CLASSES" "$PACKAGE"
mapfile -t SOURCES < <(find "$ROOT/src" -type f -name '*.java' -print)

(
    cd "$ROOT"
    "$JAVAC" -Xlint:all -d "$CLASSES" "${SOURCES[@]}"
    rm -f "$JAR"
    "$JAR_TOOL" --create --file "$JAR" --main-class app.MS -C "$CLASSES" .
    rm -rf "$DIST"
    "$JPACKAGE" \
        --type app-image \
        --name MazeSolver \
        --input "$PACKAGE" \
        --main-jar "$(basename "$JAR")" \
        --main-class app.MS \
        --dest "$DIST" \
        --app-version 0.8.0 \
        --vendor "Intro-to-AI-group7"
    if ! command -v zip >/dev/null 2>&1; then
        echo "The zip command is required to create the portable bundle." >&2
        exit 1
    fi
    (
        cd "$DIST"
        zip -qr "$(basename "$PORTABLE_ZIP")" MazeSolver
    )
)

echo "Portable application bundle created at: $PORTABLE_ZIP"
echo "Keep the complete MazeSolver folder together when running the launcher."
