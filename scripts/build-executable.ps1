Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $root "build\classes"
$package = Join-Path $root "build\package"
$dist = Join-Path $root "dist"
$jar = Join-Path $package "MazeSolver.jar"

New-Item -ItemType Directory -Force -Path $classes,$package | Out-Null
$sources = (Get-ChildItem -LiteralPath (Join-Path $root "src") -Filter *.java -Recurse).FullName

Push-Location $root
try {
    & javac -Xlint:all -d $classes $sources
    if ($LASTEXITCODE -ne 0) {
        throw "Application compilation failed."
    }

    if (Test-Path -LiteralPath $jar) {
        Remove-Item -LiteralPath $jar -Force
    }
    & jar --create --file $jar --main-class app.MS -C $classes .
    if ($LASTEXITCODE -ne 0) {
        throw "JAR creation failed."
    }

    if (Test-Path -LiteralPath $dist) {
        Remove-Item -LiteralPath $dist -Recurse -Force
    }
    & jpackage `
        --type app-image `
        --name MazeSolver `
        --input $package `
        --main-jar (Split-Path -Leaf $jar) `
        --main-class app.MS `
        --dest $dist `
        --app-version 0.7 `
        --vendor "Intro-to-AI-group7"
    if ($LASTEXITCODE -ne 0) {
        throw "Executable packaging failed."
    }

    Write-Host "Executable created at: $(Join-Path $dist 'MazeSolver\MazeSolver.exe')"
}
finally {
    Pop-Location
}