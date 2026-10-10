#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLASSES="$ROOT/build/classes"
PACKAGE="$ROOT/build/package"
DIST="$ROOT/dist"
JAR="$PACKAGE/MazeSolver.jar"

mkdir -p "$CLASSES" "$PACKAGE"
mapfile -t SOURCES < <(find "$ROOT/src" -type f -name '*.java' -print)

(
    cd "$ROOT"
    javac -Xlint:all -d "$CLASSES" "${SOURCES[@]}"
    rm -f "$JAR"
    jar --create --file "$JAR" --main-class app.MS -C "$CLASSES" .
    rm -rf "$DIST"
    jpackage \
        --type app-image \
        --name MazeSolver \
        --input "$PACKAGE" \
        --main-jar "$(basename "$JAR")" \
        --main-class app.MS \
        --dest "$DIST" \
        --app-version 0.7 \
        --vendor "Intro-to-AI-group7"
)

echo "Executable created at: $DIST/MazeSolver/bin/MazeSolver"
