Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $root "build\classes"
$requiredJavaMajor = 21
$jpackageCommand = Get-Command jpackage -ErrorAction SilentlyContinue
$javacCommand = Get-Command javac -ErrorAction Stop
if ($null -ne $jpackageCommand) {
    $javaBin = Split-Path -Parent $jpackageCommand.Source
    $javac = Join-Path $javaBin "javac.exe"
    $java = Join-Path $javaBin "java.exe"
} else {
    $javaBin = Split-Path -Parent $javacCommand.Source
    $javac = $javacCommand.Source
    $java = Join-Path $javaBin "java.exe"
}
if (-not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $java)) {
    throw "Could not find matching javac and java executables in $javaBin."
}
$versionOutput = (& $env:ComSpec /c "`"$java`" -version 2>&1" | Out-String)
if ($versionOutput -notmatch 'version "(\d+)') {
    throw "Could not determine the Java runtime version for $java."
}
$javaMajor = [int]$Matches[1]
if ($javaMajor -lt $requiredJavaMajor) {
    throw "JDK $requiredJavaMajor or newer is required; found JDK $javaMajor at $javaBin."
}

New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(
    (Get-ChildItem -LiteralPath (Join-Path $root "src") -Filter *.java -Recurse).FullName
    (Get-ChildItem -LiteralPath (Join-Path $root "test\java") -Filter *.java -Recurse).FullName
)

Push-Location $root
try {
    & $javac -Xlint:all -d $classes $sources
    if ($LASTEXITCODE -ne 0) {
        throw "Compilation failed."
    }

    & $java -cp $classes test.RegressionTests
    if ($LASTEXITCODE -ne 0) {
        throw "Regression tests failed."
    }

    $javaOptions = @()
    $preferencesPath = Join-Path $env:USERPROFILE ".maze-solver\preferences.properties"
    $configuredHeapGb = 0
    if (Test-Path -LiteralPath $preferencesPath) {
        $preferenceLine = Get-Content -LiteralPath $preferencesPath |
            Where-Object { $_ -match '^maxHeapGb=(\d+)$' } |
            Select-Object -First 1
        if ($null -ne $preferenceLine -and $preferenceLine -match '^maxHeapGb=(\d+)$') {
            $configuredHeapGb = [int]$Matches[1]
        }
    }
    if ($configuredHeapGb -ge 1 -and $configuredHeapGb -le 64) {
        $javaOptions += "-Xmx${configuredHeapGb}g"
        Write-Host "Using configured maximum heap: ${configuredHeapGb} GB"
    } else {
        $memory = Get-CimInstance -ClassName Win32_OperatingSystem
        $availableGb = [double]$memory.FreePhysicalMemory / 1048576.0
        $totalGb = [double]$memory.TotalVisibleMemorySize / 1048576.0
        # Keep room for Windows and other applications while adapting to
        # memory already in use when the launcher starts.
        $automaticHeapGb = [math]::Floor([math]::Min(
                $availableGb * 0.70,
                $totalGb * 0.75))
        if ($automaticHeapGb -lt 1) {
            $automaticHeapGb = 1
        }
        if ($automaticHeapGb -gt 64) {
            $automaticHeapGb = 64
        }
        $javaOptions += "-Xmx${automaticHeapGb}g"
        Write-Host ("Using automatic maximum heap: {0} GB " +
                "(available system memory: {1:N1} GB)" -f
                $automaticHeapGb, $availableGb)
    }
    & $java @javaOptions -cp $classes app.MS
    if ($LASTEXITCODE -ne 0) {
        throw "Maze-Solver exited with code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}