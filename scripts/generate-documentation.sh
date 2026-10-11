#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOC="$ROOT/doc"
LATEX="$DOC/latex"
PDF="$DOC/Maze-Solver-docs_V0.8.0.pdf"

install_missing() {
    local command_name="$1"
    local apt_package="$2"
    if command -v "$command_name" >/dev/null 2>&1; then
        return
    fi

    read -r -p "$command_name is required. Install it now? (y/N) " answer
    if [[ ! "$answer" =~ ^[Yy]([Ee][Ss])?$ ]]; then
        echo "Cannot generate documentation without $command_name." >&2
        exit 1
    fi
    if command -v apt-get >/dev/null 2>&1; then
        sudo apt-get update
        sudo apt-get install -y "$apt_package"
    elif command -v dnf >/dev/null 2>&1; then
        sudo dnf install -y "$apt_package"
    elif command -v pacman >/dev/null 2>&1; then
        sudo pacman -S --needed --noconfirm "$apt_package"
    else
        echo "No supported package manager was found. Install $command_name manually." >&2
        exit 1
    fi
}

install_missing doxygen doxygen
install_missing xelatex texlive-xetex

cleanup() {
    if [[ -d "$LATEX" ]]; then
        rm -rf "$LATEX"
    fi
}
trap cleanup EXIT

(cd "$DOC" && doxygen Doxyfile)

while IFS= read -r -d '' file; do
    sed -i -E 's/\\newline([A-Za-z])/\\newline{}\\1/g' "$file"
done < <(find "$LATEX" -maxdepth 1 -type f -name '*.tex' -print0)

(cd "$LATEX" && xelatex -interaction=nonstopmode refman.tex)
(cd "$LATEX" && xelatex -interaction=nonstopmode refman.tex)
cp "$LATEX/refman.pdf" "$PDF"

echo "Documentation generated:"
echo "  HTML: $DOC/html"
echo "  PDF:  $PDF"
