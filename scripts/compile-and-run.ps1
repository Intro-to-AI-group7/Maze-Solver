Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $root "build\classes"
$requiredJavaMajor = 21

$candidateBins = [System.Collections.Generic.List[string]]::new()

foreach ($commandName in @("javac", "jpackage", "java")) {
    $command = Get-Command $commandName -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        $candidateBins.Add((Split-Path -Parent $command.Source))
    }
}

if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $candidateBins.Add((Join-Path $env:JAVA_HOME "bin"))
}

$commonBinPatterns = @(
    "C:\Program Files\Microsoft\jdk-*\bin",
    "C:\Program Files\Java\*\bin",
    "C:\Program Files\Eclipse Adoptium\*\bin",
    "C:\Program Files (x86)\Java\*\bin"
)
foreach ($pattern in $commonBinPatterns) {
    Get-Item -Path $pattern -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        ForEach-Object { $candidateBins.Add($_.FullName) }
}

$javaBin = $null
$javac = $null
$java = $null
$javaMajor = $null

foreach ($candidateBin in $candidateBins | Select-Object -Unique) {
    $candidateJava = Join-Path $candidateBin "java.exe"
    $candidateJavac = Join-Path $candidateBin "javac.exe"
    if (-not (Test-Path -LiteralPath $candidateJava) -or
        -not (Test-Path -LiteralPath $candidateJavac)) {
        continue
    }

    $versionOutput = (& $env:ComSpec /c "`"$candidateJava`" -version 2>&1" | Out-String)
    if ($versionOutput -match 'version "(\d+)') {
        $candidateMajor = [int]$Matches[1]
        if ($candidateMajor -ge $requiredJavaMajor) {
            $javaBin = $candidateBin
            $javac = $candidateJavac
            $java = $candidateJava
            $javaMajor = $candidateMajor
            break
        }
    }
}

if ($null -eq $java) {
    throw "JDK $requiredJavaMajor or newer is required, but no matching java.exe and javac.exe could be found. Set JAVA_HOME or add the JDK bin directory to PATH."
}

Write-Host "Using JDK $javaMajor at $javaBin"

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