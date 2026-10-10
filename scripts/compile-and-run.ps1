Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $root "build\classes"

New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(
    (Get-ChildItem -LiteralPath (Join-Path $root "src") -Filter *.java -Recurse).FullName
    (Get-ChildItem -LiteralPath (Join-Path $root "test\java") -Filter *.java -Recurse).FullName
)

Push-Location $root
try {
    & javac -Xlint:all -d $classes $sources
    if ($LASTEXITCODE -ne 0) {
        throw "Compilation failed."
    }

    & java -cp $classes test.RegressionTests
    if ($LASTEXITCODE -ne 0) {
        throw "Regression tests failed."
    }

    & java -cp $classes app.MS
    if ($LASTEXITCODE -ne 0) {
        throw "Maze-Solver exited with code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}