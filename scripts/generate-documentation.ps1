Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$doc = Join-Path $root "doc"
$htmlDir = Join-Path $doc "html"
$latexDir = Join-Path $doc "latex"
$pdf = Join-Path $doc "Maze-Solver-docs_V1.0.1a.pdf"

function Install-MissingTool {
    param(
        [string]$CommandName,
        [string]$WingetId,
        [string]$ChocolateyPackage
    )

    if (Get-Command $CommandName -ErrorAction SilentlyContinue) {
        return
    }

    $answer = Read-Host "$CommandName is required. Install it now? (Y/N)"
    if ($answer -notmatch "^(?i)y(?:es)?$") {
        throw "Cannot generate documentation without $CommandName."
    }

    if (Get-Command winget -ErrorAction SilentlyContinue) {
        & winget install --id $WingetId --exact --accept-source-agreements `
            --accept-package-agreements
    } elseif (Get-Command choco -ErrorAction SilentlyContinue) {
        & choco install $ChocolateyPackage -y
    } else {
        throw "No supported package manager was found. Install $CommandName manually."
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Installation of $CommandName failed."
    }
}

Install-MissingTool "doxygen" "Doxygen.Doxygen" "doxygen.install"
Install-MissingTool "xelatex" "TeXLive.TeXLive" "texlive"

$doxygen = (Get-Command doxygen -ErrorAction Stop).Source
$xelatex = (Get-Command xelatex -ErrorAction Stop).Source

try {
    Push-Location $doc
    try {
        # Doxygen does not remove pages for classes that no longer exist.
        # Restrict cleanup to the generated HTML directory under this repo.
        $resolvedHtml = [IO.Path]::GetFullPath($htmlDir)
        $expectedHtml = [IO.Path]::GetFullPath((Join-Path $root "doc\html"))
        if ($resolvedHtml -ne $expectedHtml) {
            throw "Refusing to clean an unexpected documentation path: $resolvedHtml"
        }
        if (Test-Path -LiteralPath $resolvedHtml) {
            Remove-Item -LiteralPath $resolvedHtml -Recurse -Force
        }
        & $doxygen Doxyfile
        if ($LASTEXITCODE -ne 0) {
            throw "Doxygen generation failed."
        }

        Get-ChildItem -LiteralPath $latexDir -Filter *.tex | ForEach-Object {
            $text = Get-Content -LiteralPath $_.FullName -Raw
            $text = $text.Replace('\newline', '\newline{}')
            Set-Content -LiteralPath $_.FullName -Value $text -NoNewline
        }

        Push-Location $latexDir
        try {
            & $xelatex -interaction=nonstopmode refman.tex
            if ($LASTEXITCODE -ne 0) {
                throw "XeLaTeX failed on the first pass."
            }
            & $xelatex -interaction=nonstopmode refman.tex
            if ($LASTEXITCODE -ne 0) {
                throw "XeLaTeX failed on the second pass."
            }
            Copy-Item -LiteralPath (Join-Path $latexDir "refman.pdf") -Destination $pdf -Force
        } finally {
            Pop-Location
        }
    } finally {
        Pop-Location
    }
} finally {
    if (Test-Path -LiteralPath $latexDir) {
        Remove-Item -LiteralPath $latexDir -Recurse -Force
    }
}

Write-Host "Documentation generated:"
Write-Host "  HTML: $([IO.Path]::GetFullPath((Join-Path $doc 'html')))"
Write-Host "  PDF:  $([IO.Path]::GetFullPath($pdf))"
