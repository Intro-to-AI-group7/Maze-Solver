Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$suitePath = Join-Path $root "test\resources\suites\full-factorial-maze-benchmark.mstests"
$csvPath = Join-Path $root "test\resources\suites\full-factorial-maze-test-matrix.csv"
$sizes = @(100, 500, 1000, 2000, 5000, 10000)
$blockedValues = @(30, 40, 50, 60, 70, 80)
$randomMaxCosts = @(5, 10, 25, 50, 100)
$distancePercents = @(20, 40, 60, 80)
$algorithms = "DFS,BFS,UCS,GREEDY,A_STAR"
$propertyLines = New-Object System.Collections.Generic.List[string]
$csvLines = New-Object System.Collections.Generic.List[string]
$csvLines.Add("TestId,ConditionNumber,MapRows,MapColumns,MapSize,BlockedPercent,CostMode,MinimumCost,MaximumCost,SymmetricCost,EndpointMode,TargetDistancePercent,TargetManhattanDistance,Reachability,Iterations,Algorithms,ExpectedAlgorithmRuns,Seed")
$index = 0

foreach ($size in $sizes) {
    $maxDistance = (2 * $size) - 4
    foreach ($blockedPercent in $blockedValues) {
        foreach ($costMode in @("UNIFORM", "RANDOM")) {
            if ($costMode -eq "RANDOM") {
                $maxCosts = $randomMaxCosts
            } else {
                $maxCosts = @(1)
            }
            foreach ($maxCost in $maxCosts) {
                foreach ($symmetric in @($true, $false)) {
                    foreach ($endpointMode in @("OPPOSITE_CORNERS", "RANDOM", "APPROXIMATE_DISTANCE")) {
                        if ($endpointMode -eq "APPROXIMATE_DISTANCE") {
                            $distanceOptions = $distancePercents
                        } else {
                            $distanceOptions = @(0)
                        }
                        foreach ($distancePercent in $distanceOptions) {
                            $index++
                            $symCode = if ($symmetric) { "SYM" } else { "ASYM" }
                            $costCode = if ($costMode -eq "UNIFORM") { "U" } else { "R" }
                            if ($endpointMode -eq "OPPOSITE_CORNERS") {
                                $endpointCode = "OC"
                            } elseif ($endpointMode -eq "RANDOM") {
                                $endpointCode = "RND"
                            } else {
                                $endpointCode = "DIST$distancePercent"
                            }
                            $testId = "FF-{0:D4}-S{1}-B{2}-C{3}M{4}-{5}-E{6}" -f `
                                $index, $size, $blockedPercent, $costCode, $maxCost, $symCode, $endpointCode
                            if ($endpointMode -eq "APPROXIMATE_DISTANCE") {
                                $targetDistance = [int][math]::Round($maxDistance * $distancePercent / 100.0)
                            } else {
                                $targetDistance = $maxDistance
                            }
                            $seed = 20261011000000L + $index
                            $prefix = "row.$($index - 1)."
                            $propertyLines.Add("$prefix" + "id=$testId")
                            $propertyLines.Add("$prefix" + "rows=$size")
                            $propertyLines.Add("$prefix" + "columns=$size")
                            $propertyLines.Add("$prefix" + "blockedPercent=$blockedPercent.0")
                            $propertyLines.Add("$prefix" + "reachability=GUARANTEED_SOLVABLE")
                            $propertyLines.Add("$prefix" + "costMode=$costMode")
                            $propertyLines.Add("$prefix" + "minimumCost=1")
                            $propertyLines.Add("$prefix" + "maximumCost=$maxCost")
                            $propertyLines.Add("$prefix" + "sameCostBothDirections=$($symmetric.ToString().ToLowerInvariant())")
                            $propertyLines.Add("$prefix" + "endpointMode=$endpointMode")
                            $propertyLines.Add("$prefix" + "startRow=1")
                            $propertyLines.Add("$prefix" + "startColumn=1")
                            $propertyLines.Add("$prefix" + "goalRow=$($size - 2)")
                            $propertyLines.Add("$prefix" + "goalColumn=$($size - 2)")
                            $propertyLines.Add("$prefix" + "targetDistance=$targetDistance")
                            $propertyLines.Add("$prefix" + "distanceTolerance=3")
                            $propertyLines.Add("$prefix" + "seed=$seed")
                            $propertyLines.Add("$prefix" + "mapSource=MANUAL_SEED")
                            $propertyLines.Add("$prefix" + "iterations=10")
                            $propertyLines.Add("$prefix" + "algorithms=$algorithms")
                            $csvLines.Add("$testId,$index,$size,$size,$size,$blockedPercent,$costMode,1,$maxCost,$($symmetric.ToString()),$endpointMode,$distancePercent,$targetDistance,GUARANTEED_SOLVABLE,10,`"$algorithms`",50,$seed")
                        }
                    }
                }
            }
        }
    }
}

if ($index -ne 2592) {
    throw "Expected 2592 conditions, generated $index."
}

$header = @(
    "# Full factorial benchmark suite generated by scripts\generate-full-factorial-suite.ps1"
    "# 2592 conditions x 5 algorithms x 10 iterations = 129600 measured runs."
    "# Each row uses one persisted manual seed; all algorithms share each row's map."
    "# Reachability is fixed at GUARANTEED_SOLVABLE because it is not an experimental factor."
    "# For APPROXIMATE_DISTANCE, targetDistance is rounded from the requested percentage of max Manhattan distance."
    "format=maze-solver-test-suite"
    "version=1"
    "applicationVersion=0.8.1"
    "name=full-factorial-maze-benchmark"
    "automaticExport=false"
    "exportDirectory="
    "rowCount=$index"
)
[System.IO.File]::WriteAllLines($suitePath, $header + $propertyLines, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllLines($csvPath, $csvLines, [System.Text.UTF8Encoding]::new($false))
Write-Output "Generated $index conditions."
Write-Output $suitePath
Write-Output $csvPath
