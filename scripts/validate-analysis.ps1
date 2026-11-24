param([switch]$SkipCompile)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $root "build\analysis-check"
$outputDirectory = Join-Path $root "build\analysis-validation"
$candidateBins = @()
if ($env:JAVA_HOME) { $candidateBins += Join-Path $env:JAVA_HOME "bin" }
$javaCommand = Get-Command java -ErrorAction SilentlyContinue
if ($javaCommand) { $candidateBins += Split-Path -Parent $javaCommand.Source }
$candidateBins += @(Get-Item 'C:\Program Files\Microsoft\jdk-*\bin',
    'C:\Program Files\Java\*\bin', 'C:\Program Files\Eclipse Adoptium\*\bin' -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | ForEach-Object FullName)
$javaBin = $candidateBins | Where-Object {
    (Test-Path -LiteralPath (Join-Path $_ 'java.exe')) -and
    (Test-Path -LiteralPath (Join-Path $_ 'javac.exe'))
} | Select-Object -First 1
if (-not $javaBin) { throw 'JDK 21+ is required. Set JAVA_HOME or add Java to PATH.' }
$java = Join-Path $javaBin 'java.exe'
$javac = Join-Path $javaBin 'javac.exe'

Push-Location $root
try {
    New-Item -ItemType Directory -Force -Path $classes,$outputDirectory | Out-Null
    if (-not $SkipCompile) {
        $sources = @((Get-ChildItem src,test\java -Recurse -Filter *.java).FullName)
        & $javac -Xlint:all -d $classes $sources
        if ($LASTEXITCODE -ne 0) { throw 'Analysis compilation failed.' }
    }
    & $java '-Djava.awt.headless=true' -cp $classes test.AnalysisTests --artifacts $outputDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Analysis regression checks failed.' }
    $fixture = Join-Path $outputDirectory 'known-results.csv'
    & $java '-Djava.awt.headless=true' -cp $classes ui.AnalysisUiTests $fixture
    if ($LASTEXITCODE -ne 0) { throw 'Swing analysis integration checks failed.' }
    $summary = Join-Path $outputDirectory 'normalized.csv'
    $filtered = Join-Path $outputDirectory 'filtered.csv'
    $chart = Join-Path $outputDirectory 'cli-overlay.png'
    & $java '-Djava.awt.headless=true' -cp $classes experiments.ExperimentCli `
        --results $fixture --filter 'algorithm=UCS|GREEDY' --sort 'algorithm:asc,runtime_ms:asc' `
        --filtered-csv $filtered --normalized-csv $summary --chart $chart --chart-view OVERLAY --validate
    if ($LASTEXITCODE -ne 0) { throw 'CLI analysis validation failed.' }
    $groups = @(Import-Csv -LiteralPath $summary)
    if ($groups.Count -ne 2) { throw 'Expected exactly two map/algorithm groups.' }
    foreach ($group in $groups) {
        $expected = if ($group.'Group (includes execution mode)' -match '/ UCS /') { 10 } else { 100 }
        if ([double]::Parse($group.'Runtime Mean (ms)', [Globalization.CultureInfo]::InvariantCulture) -ne $expected -or
            [int]$group.'Original Runs' -ne 5 -or [int]$group.Excluded -ne 1 -or [int]$group.'Retained Runs' -ne 4) {
            throw 'Normalized mean or per-algorithm outlier counts are incorrect.'
        }
    }
    if (@(Import-Csv -LiteralPath $filtered).Count -ne 10) { throw 'Raw iterations were lost.' }
    $smokeSuite = Join-Path $outputDirectory 'analysis-smoke.mstests'
    $smokeResults = Join-Path $outputDirectory 'smoke-results.csv'
    $scalingChart = Join-Path $outputDirectory 'runtime-by-size.png'
    & $java '-Djava.awt.headless=true' -cp $classes experiments.ExperimentCli `
        --suite $smokeSuite --quiet --csv $smokeResults --validate --chart $scalingChart `
        --chart-type LINE --chart-x ROWS --metric RUNTIME_MS --normalize --hide-legend
    if ($LASTEXITCODE -ne 0) { throw 'Live suite / configurable axes / hidden-legend CLI validation failed.' }
    if (@(Import-Csv -LiteralPath $smokeResults).Count -ne 90) { throw 'Expected 90 live benchmark measurements.' }
    foreach ($name in @('iterations.png','normalized.png','overlay.png','both.png','cli-overlay.png')) {
        if ((Get-Item -LiteralPath (Join-Path $outputDirectory $name)).Length -lt 1000) {
            throw "Missing or empty PNG: $name"
        }
    }
    Write-Host "PASS: per-map/per-algorithm means, outlier counts, raw CSV, CLI validation, and PNG exports."
    Write-Host "Artifacts: $outputDirectory"
} finally { Pop-Location }
