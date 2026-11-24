# Maze-Solver V0.9.0a

Maze-Solver V0.9.0a is an unverified alpha build of the Java Swing desktop app for loading, visualizing, and
solving grid mazes using DFS, BFS, Uniform Cost Search, Greedy Best-First
Search, and A* with directional movement costs. Maze files support optional per-direction movement
costs while preserving the original unweighted format.

It is designed for both interactive exploration and large-map performance testing, with a paint-based renderer that supports very large mazes efficiently.

## Table of Contents

1. Overview
2. Features
3. Requirements
4. Quick Start
5. How to Use
6. Results Analysis
7. Maze File Format
8. Included Maze Files
9. Algorithms
10. Rendering and Performance
11. Project Structure
12. API and Extension Notes
13. Launch From Release JAR
14. Troubleshooting
15. Development Notes
16. License

## Overview

Maze-Solver has six main responsibilities:

1. Load human-readable `.msmap` maze files from disk.
2. Validate and visualize the maze graph.
3. Run DFS, BFS, UCS, Greedy Best-First Search, or A* incrementally or instantly.
4. Configure reproducible multi-algorithm experiment suites and export results.
5. Review exact experiment maps in the animated renderer.
6. Show traversal progress and final path with clear visual semantics.

## Features

- Title-screen startup flow with import-or-generate selection.
- Random maze generation using traditional corridor carving.
- Interactive solver controls for start, pause, restart, algorithm, and delay.
- Instant solve mode that runs to completion in a background thread.
- In-session map controls for loading a different map, saving the current map, and generating a new random map.
- Paint-based grid rendering for all maze sizes.
- Viewport clipping so only visible cells are painted.
- Zoom controls (+/- and Ctrl + mouse wheel).
- Gridline overlay for easier node-by-node visibility.
- Start/goal color preservation after final path draw.
- Debug diagnostics for maze import and neighbor consistency checks.
- Optional weighted movement data using a compact `[North, East, South, West]` format.
- Optional grid overlay for viewing directional movement costs. Equal opposite
  costs share one edge label; asymmetric costs show separate directional labels.
- Default startup solve settings: A* algorithm and 200 ms step delay.
- Application Settings can configure the maximum JVM heap for the next launch
  (1–64 GB, or 0 for automatic sizing). Saving offers an immediate restart.
  Java cannot increase the heap limit of an already-running process; the
  Windows repository launcher reads the saved setting and starts Java with the
  corresponding `-Xmx` option. Automatic sizing uses 70% of available physical
  memory, capped at 75% of total physical memory and clamped to 1–64 GB.
  Direct `java` launches and packaged launchers use JVM defaults unless an
  explicit heap option is supplied; they do not read the preferences file.
- Configurable test-suite workflow from the title screen.
- Seeded experiment generation with adjustable map size, interior obstacle
  density, reachability, endpoint placement, and movement-cost policies.
- Generated experiment maps use a seeded traditional corridor-maze backbone.
  The configured blocked percentage is applied by opening additional cells or
  removing dead-end corridors, so density changes preserve long orthogonal
  walls and one-cell-wide passages instead of producing speckled noise.
- Guaranteed-solvable generation retries disconnected layouts and uses a
  seeded winding connector only as a final fallback. Fixed endpoints are
  preserved during density adjustment.
- Shared-map comparisons across any selected subset of the five algorithms.
- Suite save/load, progress and cancellation, per-result animated review,
  suite map export, and UTF-8 CSV export.
- Column-header filters and multi-column sorting with editable expressions for
  results and test configurations. Results are queried before pagination.
- Per-map/per-algorithm summaries, optional IQR outlier removal, normalized
  means, and raw/normalized overlays with configurable graph axes and legends.
- Headless PNG/CSV analysis exports, saved analysis views, and results CSV reload.

## Requirements

- Java JDK 21 (LTS) or newer, including the `jpackage` tool for executable
  generation.
- Windows, macOS, or Linux with desktop GUI support.
- No external dependencies required.
- Alpha build: `V0.9.0a` (also exposed by `app.Version.RELEASE` and `ExperimentCli --version`).

## Quick Start

From the repository root:

1. Compile (PowerShell)

```powershell
New-Item -ItemType Directory -Force build\classes | Out-Null
$sources = @((Get-ChildItem -Filter *.java src -Recurse).FullName) +
    @((Get-ChildItem -Filter *.java test\java -Recurse).FullName)
javac -Xlint:all -d build\classes $sources
```

2. Run

```powershell
java -cp build\classes app.MS
```

Run the dependency-free regression suite with:

```powershell
java -cp build\classes test.RegressionTests
```

Run an experiment suite without opening Swing:

```powershell
java -cp build\classes experiments.ExperimentCli `
    --suite test\resources\suites\test_testSuiteExample.mstests `
    --mode SEQUENTIAL `
    --monitor-ms 1000 `
    --csv build\results\cli-results.csv
```

The command-line runner prints periodic progress, heap usage, active worker
counts, and interval worker peaks. Use `FIXED_PARALLEL` or
`ADAPTIVE_PARALLEL` to benchmark the execution modes, and `--repeat N` to
repeat the same seeded suite. Press Ctrl+C to request cooperative
cancellation. Use `--quiet` when only the final timing summary is needed.
This runner uses the same `ExperimentRunner` as the application, so command
line runs exercise the production scheduler without UI rendering overhead.

Startup sequence:

1. Title screen appears.
2. Choose Import Maze or Generate Random.
3. If importing, select a maze text file. If generating, enter rows and columns,
   then optionally enable movement weights with a range from 1 through 100.
4. Solve interactively.

Use **Back to Main Menu** in the maze controls to leave an interactive session
without exiting the application. The test-suite editor has the same navigation
button, and closing either secondary window returns to the title screen. The
title screen is hidden while a secondary screen is open so each menu is only
shown once.

To configure experiments, choose **Configure Tests** on the title screen. Add
one row per test condition, edit the row values, select algorithms and
iterations, then choose **Run Tests**. Each generated-map iteration creates
one map and uses that same map for every selected algorithm. Generated layouts
retain the suite's row/column, blocked-percent, reachability, endpoint, seed,
and movement-cost settings. Saved-map rows
reuse their embedded map for every iteration. Automatic seeds are materialized
when a suite is saved so later generated-map runs can be reproduced. Results
can be exported manually or automatically to CSV, and unique maps can be
exported for later inspection.

The test editor uses dropdowns for reachability, cost, endpoint, and map-source
preferences. The Algorithms cell provides a checkbox multi-select, and
boolean options use checkboxes. Numeric values are normalized to integers when
editing ends. In `RANDOM` and `DISTANCE` endpoint modes, start and goal
coordinates are disabled; `DISTANCE` enables the target-distance field.

Each row can use an automatic seed, a manually entered seed, or a saved map.
Saved maps are imported through the row editor and embedded in the suite file
using UTF-8-safe encoding, so the suite does not depend on the original map
file remaining available. Saved-map results identify the source as `SAVED_MAP`
and display `N/A (saved map)` instead of a generated seed.
Test suites use the human-readable `.mstests` extension; individual maps use
the human-readable `.msmap` extension. Import and save dialogs filter and
normalize to their corresponding extension and open in the application
directory. A suite saved by this build contains `applicationVersion=V0.9.0a` and
`version=1`; the former identifies the application release and the latter is
the stable suite schema version. Older schema-version-1 suites remain
readable.

Before measured runs begin, the experiment runner performs three warm-up rounds
using each selected algorithm on a small seeded map. Warm-up solves are excluded
from results, progress counts, and per-result search timing. CLI suite elapsed
time includes warm-up, generation, scheduling, and result handling.

Experiment result runtimes measure one complete solver run from the start of
the search through result collection. They exclude map generation and suite
scheduling, and they do not read the solver's per-step diagnostic timer. This
avoids adding two `System.nanoTime()` calls to every search step, which is
particularly important for small mazes. If live solver progress is requested,
the runner performs throttled progress timing checks; the command-line runner
does not request those callbacks.

Experiment suites run sequentially by default. Select **Fixed parallel** or
**Adaptive parallel** in the test-suite editor to run independent algorithms
for each generated map concurrently. Sequential mode remains available for
compatibility checks and comparisons. Each generated map is shared by the
selected algorithms for that iteration. Returned results and CSV exports are
ordered by test condition, iteration, and algorithm; the live UI displays
results in completion order.

Parallel execution is most useful for larger maps or suites with several
selected algorithms. Small suites may show little improvement because there is
not enough work to keep all workers busy.

Experiment results retain only measurements, configuration metadata, and the
effective map seed. Generated maps are recreated from that seed only when a
result is reviewed, which avoids retaining large serialized maps for every
algorithm run. The results table is paged with selectable page sizes of 10,
25, 50, or 100 rows. Use **First**, **Previous**, **Next**, **Last**, or enter
a page number directly. The footer shows active mapping and
solving workers, interval peaks, elapsed time, and solve throughput. The map
progress bar shows heap usage and scheduler reservations. The progress status
identifies the most recently completed condition.
Enable **Follow latest results** to jump to the newest completed result that
matches your filters and scroll it into view. With a custom sort, that row
can appear on any page. Manual paging unchecks follow mode so you can inspect
other results.
Choose **Live solve log** in the **Results view** selector to follow solves
as they finish. The log appends each completed solve with its condition,
iteration, algorithm, solved status, path cost, explored-node count, and
runtime, and automatically follows the newest entry. It is intentionally
bounded to the most recent 500 solve entries; the complete results remain
available in the table and exported result data.
The live log is split into a wide solver-activity pane and a narrower
map-generation pane. The map pane records map readiness, solver dispatch, and
map completion events, along with the number of generated maps currently
loaded in memory waiting for solver work. That loaded-map count is distinct
from maps still being generated and is also shown on the map progress bar.
Table and solver-log refreshes run only while their respective view is visible.
Completed solve history is retained up to the 500-entry limit in either view;
map-log events are recorded only while the live log is selected.
During a run, **Pause** cooperatively stops new map generation and solver work
at task boundaries; **Resume** continues the same run. **Cancel** requests
cooperative shutdown and clears coordinator references and queued work.
An already-running map generation or solve can finish before its worker stops;
pause and cancellation do not interrupt individual search steps.
While solves are active, the log places live worker lines above the completed
history and updates their nodes explored, current path cost, and elapsed time
in place. Progress notifications are throttled so the diagnostic view does
not receive one UI event per visited node. A future small-suite race view may
make this comparison more visual.

Seed behavior is stable for the complete execution of each test case. Manual
seed mode reuses the configured seed for every iteration, while automatic seed
mode generates one seed once and reuses it for every iteration. Consequently,
generated iterations in the same row recreate the same map; repeated solver
runs are directly comparable rather than independent random samples. Saved-map
mode reuses the embedded map and does not use a generated seed. Every selected
algorithm in an iteration receives the same read-only map.

Approximate-distance endpoint selection first uses seeded random sampling for
speed. If sampling misses a valid pair, the generator performs a deterministic
distance-offset search before rejecting the layout. This keeps results
reproducible while avoiding failures caused only by random endpoint sampling.

The results table includes a **Review** button for each result. It reconstructs
the map from the run's configuration and effective seed, or imports its embedded
saved map, and opens MazeRenderer with the result's algorithm selected.
**Export Maps** uses the current configuration table and creates a
`<suite-name>-maps` folder under the selected directory and writes one
complete saveable map file for each unique map. Repeated iterations are grouped
in the filename, such as `iterations-1-3`, while distinct maps retain their
iteration numbers. Seed-generated maps are converted into the same format used
by imported maps, making them portable for later inspection or reuse.
For exports that must match a run, save and reload the suite before running or
exporting maps, and keep its configuration unchanged. Saving materializes
automatic seeds; otherwise a separate export resolves fresh automatic seeds.

### Included benchmark matrix

`test/resources/suites/full-factorial-maze-benchmark.mstests` contains 1,296
conditions, with five algorithms and ten iterations each (64,800 solves).
Factors cover square sizes of 100, 500, and 1,000 cells per side; blocked
percentages from 30 through 80 in steps of 10; uniform costs or random maxima
of 5, 10, 25, 50, or 100; symmetric/asymmetric costs; and corner, random, or
approximate-distance endpoints. Reachability is fixed to guaranteed solvable.
Each condition has one manual seed reused across iterations.

`scripts/generate-full-factorial-suite.ps1` regenerates both the suite and its
companion CSV matrix. The generated suite defaults to sequential execution;
select another mode in the UI or pass the CLI `--mode` option when comparing
throughput. This is a large benchmark, separate from the regression suite.

## How to Use

After loading or generating a maze:

- Start: begin or resume timer-based solving.
- Pause: stop solving without clearing current search state.
- Restart: clear colors and reset the active algorithm state.
- Load Map: replace the current session maze with another file.
- Save Map: export the currently loaded maze to a text file.
- Generate Map: create and load a new random maze without leaving the solver window.
- Algorithm: choose DFS, BFS, Uniform Cost Search, Greedy Best-First Search, or A*.
- Step Delay (ms): set timer delay for step-by-step mode (1..5000).
- Instant: solve to completion immediately (async worker).
- Show Weights: overlay movement costs on grid edges. Labels appear when zoom is
  at least 20 pixels per cell and use a slightly larger font for readability.
  Equal opposite-direction costs use one number;
  different costs use direction markers (`>`, `<`, `v`, `^`) and two numbers.
  During solving, labels next to visited nodes use a subdued gray style. Once
  solved, only the exact consecutive edges in the final path use an orange-on-gold highlight. Solver
  updates repaint only the changed cells and adjacent edge-label area; the
  entire grid is not redrawn for each visited node. The localized repaint
  includes a small pixel margin because labels are centered on cell boundaries.
- Current Cost: the cost from the start to the most recently visited node.
  This tracks only that node's selected parent route, not all explored branches.
- Final Cost: the total movement cost of the returned route, or `--` when no
  solution has been found.
- Solve Time: elapsed time spent inside solver search-step calls. Timer delays,
  UI repainting, and animation intervals are outside those measurements, so
  animated and instant solving times are directly comparable. The interactive
  viewer keeps this per-step diagnostic timing enabled. Test-suite results use
  a separate external timer around the complete solve instead. The console
  also prints a diagnostic line containing the result, interval, algorithm,
  visited node count, final path cost, compute time, and wall-clock session
  time after each completed solve.
- Zoom +/-: adjust cell size.
- Ctrl + Mouse Wheel: zoom while cursor is over the maze viewport.

Saved maze files automatically receive a `.msmap` extension when one is not
provided. The import dialog filters for `.msmap` maze files; reopening the dialog
is sufficient to see a newly saved file, and restarting the application is not
required.

Random generation details:

- Uses a depth-first backtracking corridor-carving strategy for a classic maze look.
- Keeps the outer border blocked.
- Ensures the configured goal cell is connected to the carved maze.
- Extends carving into final interior row/column on even dimensions to avoid persistent right/bottom wall strips.
- The generator can optionally enable movement weights and accepts an inclusive
  minimum and maximum range from `1` through `100`. These limits are centralized
  in `Maze.MIN_MOVEMENT_COST` and `Maze.MAX_MOVEMENT_COST`.
- The default maximum in the generator dialog is `10`.
- The generator defaults to using the same positive integer cost in both
  directions of each connection. Clear the same-cost checkbox to generate
  independent integer costs for reverse directions.

## Results Analysis

The results area offers **Table view**, **Summary**, **Graphs**, and the existing
**Live solve log**. Use **Toggle setup** or drag the divider to give analysis
more space. **Expand graph** opens a larger graph preview; image exports keep
their full resolution regardless of the preview size.

### Filters and sorting

Open **Analysis options** to choose a filter field, operator, and value, then
select **Add**. This panel also contains custom sorting, summary grouping, and
outlier controls. Separate rules with a semicolon to require all of them.
Use `|` for alternatives within a rule. You can also edit the expression
directly and press **Apply** or Enter. Examples:

```text
algorithm=UCS|A_STAR;rows>=500
solved=true;actual_blocked>=40;actual_blocked<=60
map~first-few-characters-of-the-map-id
```

`=` and `!=` compare values, `~` searches text, and numeric fields also support
`>`, `<`, `>=`, and `<=`. Filters cover configuration, map identity, algorithm,
outcome, dimensions, requested/actual obstacle density, costs, endpoint distance,
seed, iteration, runtime, nodes, source, execution mode, symmetry, and heuristic.
`ExperimentCli --fields` lists all field names. Names and text comparisons are
case-insensitive. Missing costs/lengths do not pass numeric comparisons.

Click a table heading to sort ascending/descending. Shift-click adds a secondary
key. Right-click a heading to choose a sort direction, add a secondary key,
add a filter, or clear that column's filters or sort key. These actions update
the editable **Filters** and **Sort** fields and apply the query immediately.
Open **Analysis options** to edit the results sort expression. It accepts
entries such as `map:asc,algorithm:asc,runtime_ms:asc`. Sorting uses the
underlying numeric values at full precision, with missing values last in both
directions. **Reset
sort** restores arrival order, while **Clear filters** preserves the sort.
Both operations apply to the complete result set before it is paged. The footer
reports matching and total rows. Review uses the actual displayed record.
Right-click a result to copy its cell value, its row as tab-separated text,
or its map ID. The menu can also filter to the row's algorithm, compare the map,
or open the solve review when the original suite is available.
Press Enter in **Summary groups** or select **Apply** to save grouping edits.
An invalid chart setting displays an error in the graph preview while the table
and summary continue to use the selected filters and sort order.

The test configuration table has the same column-header actions. **Sort / Filter**
opens its editable query fields. For example, use
`algorithms~UCS;iterations>=10` to find configurations that include UCS and
`rows:asc,iterations:desc` to order them. Hover over a heading for its query
field name. The configuration query changes only the editor view. Saving and
running use the full suite in its original order. Reset the query to drag
configurations into a new order.

### Compare algorithms on the same map

Right-click a table result and select **Compare this map**. This selects the
exact map, includes all its algorithms/iterations, sorts them by algorithm and
iteration, and opens an overlay graph. Map identity includes generation settings
and the effective seed, or a saved-map content hash; matching seed values alone
are insufficient. Execution modes are always kept separate.

Summary defaults to `configuration,map,algorithm`. Algorithm and execution mode
are always separated, even if omitted from grouping. Each row reports original, excluded, and retained runs; distinct
maps; raw and retained success rates; runtime mean, median, sample standard
deviation, minimum and maximum; nodes; cost; and matched UCS comparisons.
Cost statistics exclude unsuccessful solves and show their sample count.
UCS baselines come from the complete dataset even when filters hide UCS rows.
Missing baselines and zero denominators produce N/A, not invented percentages.

### All iterations and normalized results

Graph View options are:

- **ITERATIONS:** measurements without outlier removal. Bar/line charts use the
  selected raw statistic; scatter/box charts show the measurement distribution.
- **NORMALIZED:** use one retained mean for each exact
  **map × algorithm × execution mode** group. Algorithms are never averaged
  together. If several maps share an X value, their means have equal weight.
- **OVERLAY:** on categorical axes, solid bars show normalized means and
  translucent bars in the same series color show individual runs, including
  outliers. On numeric axes, normalized means appear as diamonds. Each raw
  series has one translucent line joining its dots in iteration order within
  the same suite, configuration, map, algorithm, and execution mode.
  This is the default comparison view.
- **BOTH:** show iteration and normalized charts in two panels.

Normalization uses optional **IQR** outlier removal, enabled by default, with
runtime as the default trimming metric. Within each map/algorithm/execution
group, values below `Q1 - 1.5 × IQR` or above `Q3 + 1.5 × IQR` are excluded.
Quartiles use linear interpolation; at least four eligible observations are
required. A zero IQR retains values equal to the quartiles. **NONE** averages
all iterations instead. Changing the trimming metric can change which complete
observations enter the normalized summary. Raw measurements are never modified.
Exclusion counts appear in summaries, image captions, and overlay legends.

Ten iterations using one seed are ten timing repetitions of **one map**, not
ten independent environments. Use multiple seeds/conditions to generalize
conclusions. The summary exposes both run and map counts. Normalized values
describe the selected observations; outlier removal is not a guarantee of a
more representative result. Report the raw results and trimming policy too.

### Graph controls and image export

Choose X and Y independently. X can be a category or numeric field; line and
scatter charts require numeric X. Y includes runtime, nodes, path cost/length,
success, excess cost versus UCS, and node reduction versus UCS. For example:
choose **LINE**, X **ROWS** (or **CELLS**), Y **Runtime (ms)**, and
**NORMALIZED** to compare each algorithm as map size increases. For rectangular
maps, CELLS represents total grid cells; filter/group other conditions carefully.

Chart choices include grouped bars, lines, scatter points, and box plots.
Category order can follow the X values or the plotted value; numeric line/scatter
axes always stay in numeric order. Toggle **Show legend** to show/hide the
algorithm/execution key in both the preview and the exported image. Colors stay
stable when filtering. Optional log Y displays positive values only. Error bars
on mean charts represent one sample SD, not a confidence interval. In normalized
charts that SD is across map means, so one map does not produce an SD error bar.
Boxes show Q1/median/Q3 with min/max whiskers. Normalized box plots describe map
means, not the original iterations.

**Export graph PNG** writes a labeled 1600×1000 image, or 3200×1000 for BOTH.
Images include filters, ordering, sample/map counts, trimming policy, and units.
The preview fits its available area. Categories are limited to 40 and series to
15; refine filters/grouping rather than silently dropping groups. Scatter charts
explicitly sample at most 4,000 displayed points; CSV/statistics retain all rows.
Conditions not on an axis or separated into series are pooled, as noted on images.

### Save, reopen, and validate

The **Results actions** menu groups exports, CSV loading, saved views, and help.
**Export all results CSV** preserves every raw observation. **Export filtered CSV**
exports every matching row, not just the visible page, in the selected order.
**Export summary CSV** preserves the grouped statistics and exclusion counts.
**Open results CSV** reloads measurements without rerunning a suite. New UI/CLI
exports include versioned map identities, execution mode, symmetry, endpoints,
and heuristic metadata. Original map files are not embedded: imported result
CSVs support analysis, but cannot open animated map review by themselves.

Older application CSVs remain readable, with absent provenance marked UNKNOWN.
Their map count cannot be verified, and normalized graphs/normalized CSV export
require a new run with map identities. General summaries and iteration charts
remain available. **Save analysis view** and **Load analysis view** persist
filters, sorting, grouping, axes, graph view, legend, and trimming controls in
`.msview` files.

The CLI uses the same query, statistics, and image renderer as Swing:

```powershell
# Reopen measurements; do not rerun the solvers.
java -Djava.awt.headless=true -cp build\classes experiments.ExperimentCli `
    --results build\results\cli-results.csv `
    --filter 'algorithm=UCS|GREEDY|A_STAR' `
    --sort 'map:asc,algorithm:asc,iteration:asc' `
    --normalized-csv build\results\per-map-means.csv `
    --filtered-csv build\results\selected-runs.csv `
    --chart build\results\comparison.png --chart-view OVERLAY --validate

# Each algorithm's normalized mean runtime as map size increases; hide the key.
java -Djava.awt.headless=true -cp build\classes experiments.ExperimentCli `
    --results build\results\cli-results.csv `
    --chart build\results\scaling.png --chart-type LINE `
    --chart-x ROWS --chart-y RUNTIME_MS --normalize --outliers IQR --hide-legend
```

`--summary-csv` respects `--group-by`; `--normalized-csv` always separates map
and algorithm. `--preset FILE` loads a saved UI view; subsequent flags override
its controls. `--chart-width` / `--chart-height` configure per-panel resolution.
CLI `--repeat` still benchmarks suite repetitions and exports the final run;
within-suite iterations are preserved individually. `--validate` checks metric
consistency and available matched UCS/A* costs; it does not reconstruct paths
or establish correctness when no baseline is present.

Run the deterministic validation workflow:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\validate-analysis.ps1
```

It compiles sources, checks table queries, pagination, live following, result
identity, and image rendering, and verifies that two algorithms on one map retain separate means of **10 ms**
and **100 ms**, with one outlier excluded from each five-run group. It also
executes a 90-solve suite across three map sizes and tests CLI axis/legend options.
Inspectable CSV/PNG fixtures are written under `build/analysis-validation/`.
The full `test.RegressionTests` suite also includes the core analysis checks.
For a controlled timing study, prefer sequential execution and analyze saved
measurements afterward; concurrent UI analysis still consumes CPU resources.

## Maze File Format

Expected format:

1. Row count
2. Column count
3. start: row,col
4. end: row,col
5. One row per maze row using:
	- 1 for traversable/open
	- 0 for blocked/wall
6. Optional `WEIGHTS` section with one four-value row per grid coordinate.

Example:

```text
4
5
start: 3,0
end: 0,4
11111
10001
10101
11111
```

Legacy files without a `WEIGHTS` section remain valid. Every adjacent open-cell
connection receives a default movement cost of `1`.

### Weighted map format

The optional section follows the complete text layout:

```text
4
5
start: 3,0
end: 0,4
11111
10001
10101
11111

WEIGHTS
0,1,1,0
0,1,0,1
0,1,0,1
0,1,0,1
0,0,1,1
1,0,1,0
0,0,0,0
0,0,0,0
0,0,0,0
1,0,1,0
1,1,0,0
0,1,0,1
0,1,0,1
0,1,0,1
1,0,0,1
1,1,0,0
1,1,0,1
1,1,0,1
1,1,0,1
1,0,0,1
0,0,0,0
0,0,0,0
```

There is one weight row for every coordinate in row-major order, including
blocked cells. Each row is ordered `[North, East, South, West]` and uses:

- `0`: no permitted movement in that direction.
- `1` or greater: the positive cost of moving in that direction.

For an open cell, a direction leading outside the maze or into a blocked cell
must have cost `0`. Blocked cells must contain four zero values. Costs may be
asymmetric, so the cost from A to B does not have to equal the cost from B to A.
The current map model preserves geometric neighbor references separately from
movement availability: a neighboring node is traversable only when its movement
cost is greater than zero.

`MazeSolver` uses movement costs when determining whether an edge is traversable
and when calculating weighted search scores. UCS and A* guarantee a minimum-cost
route when a route exists because all enabled movement costs are positive.

Validation rules enforced by import:

- Dimensions must be positive.
- Layout row count must match declared row count.
- Each layout row width must match declared column count.
- Only 0 and 1 are valid cell values.
- Start and end must be in bounds and on traversable cells.
- If `WEIGHTS` is present, it must contain exactly `rows * columns` rows.
- Each weight row must contain exactly four integer values. Enabled movement
  costs must be in the range `1..100`; `0` is reserved for disabled or
  non-existent directions.
- Weight rows must match the layout topology.

## Included Maze Files

Located in `test/resources/maps/`:

- `*.msmap`: human-readable individual maze map files.
- test_100x100.msmap: medium map for standard performance checks.
- test_346x346.msmap: larger map for stress testing.
- test_1000x1000.msmap: very large map for renderer scalability tests.
- test_weighted_100x100.msmap: weighted 100x100 example with directional costs.

## Algorithms

The solver supports:

- DFS: depth-first exploration, memory-light but not shortest-path or
  minimum-cost guaranteed.
- BFS: breadth-first exploration, shortest by number of moves when all enabled
  moves have equal cost; it respects disabled edges but does not optimize
  weighted path cost.
- Uniform Cost Search: uninformed priority search ordered by accumulated route
  cost `g(n)`. With positive movement costs, it returns a minimum-cost path.
- Greedy Best-First Search: priority search ordered only by the Manhattan
  heuristic `h(n)`. It can explore fewer nodes, but it is not guaranteed to
  return a minimum-cost path.
- A*: informed search using cumulative directional movement costs and
  the Manhattan-distance heuristic. Since valid movement costs are at least
  `1`, the heuristic remains an admissible lower bound and A* returns a
  minimum-cost path when one exists.

All algorithms use the `[North, East, South, West]` movement costs from the
loaded maze. A neighboring cell with cost `0` is treated as an unavailable
edge. A* stores cumulative costs as `long` values to avoid narrowing the sum
of many individual movement costs.

A* heuristic used:

$$h(n) = |r_n - r_g| + |c_n - c_g|$$

where $r_n,c_n$ is current node and $r_g,c_g$ is goal.

The priority functions used by the informed searches are:

- UCS: `f(n) = g(n)`
- Greedy Best-First Search: `f(n) = h(n)`
- A*: `f(n) = g(n) + h(n)`

The solver uses graph-search repeated-state tracking for all priority-based
algorithms. UCS and A* ignore stale frontier entries after a cheaper route is
discovered. Greedy keeps the first discovered route to each cell, consistent
with its heuristic-only ordering and non-optimality guarantee.

The Manhattan heuristic is admissible because every enabled move costs at
least `1`. It is also consistent for this four-directional maze: moving to an
adjacent cell changes Manhattan distance by at most `1`, while the transition
cost is at least `1`.

Here A* uses `g(n) + h(n)` on a weighted graph. It does not multiply the
heuristic by an additional weighting factor.

## Rendering and Performance

Current renderer behavior:

- One paint-based path for every maze size.
- Cell colors are cached in a backing array.
- Only visible clip-region cells are painted each frame.
- Gridlines are rendered with a subtle alpha stroke.
- Start and goal labels (S/G) are drawn at larger zoom levels.

Color semantics:

- Start: green
- Goal: blue
- Open: white
- Blocked: dark gray
- Visited: configurable (default yellow)
- Final path: cyan

When the weight overlay is enabled, `0` means movement is disabled and positive
numbers are movement costs. The overlay preserves directional asymmetry: for a
horizontal connection, `>` is movement to the east and `<` is movement to the
west; for a vertical connection, `v` is movement to the south and `^` is
movement to the north.

After a solved run, start and goal are restored to semantic colors.

## Project Structure

```text
Maze-Solver/
├─ README.md
├─ TODO.md
├─ LICENSE
├─ src/
│  ├─ app/                     application entry point
│  ├─ maze/
│  │  ├─ map/                  maze model, generation, and map persistence
│  │  └─ search/               solver facade and search strategies
│  ├─ experiments/             experiment configuration, execution, and CLI
│  ├─ analysis/                result queries, statistics, charts, and CSV I/O
│  └─ ui/                      Swing renderer and test-suite window
├─ test/
│  ├─ java/                    regression tests
│  └─ resources/
│     ├─ maps/                 .msmap fixtures
│     └─ suites/               .mstests fixtures
└─ doc/                       Doxygen configuration, HTML reference, and PDF
```

Core packages and classes:

- `app.MS`: desktop application entry point.
- `app.Version`: authoritative application release metadata.
- `maze.map.Maze`: model for occupancy, neighbors, directional costs, start, goal,
  generation, and validation.
- `maze.map.MazeFileIO`: dedicated `.msmap` parser and serializer used by the
  compatibility import/export methods on `maze.map.Maze`.
- `maze.search.MazeSolver`: public incremental solver facade, lifecycle, metrics, and
  path reconstruction.
- `maze.search.SearchStrategy`, `maze.search.UninformedSearch`, `maze.search.UCS`,
  `maze.search.GreedySearch`, and `maze.search.Astar`: internal incremental algorithm
  strategies used by the solver facade. `UninformedSearch` handles both DFS
  and BFS through its explicit `TraversalMode` frontier policy.
- `ui.MazeRenderer`: UI flow, controls, rendering, and solve orchestration.
- `experiments.TestSuite`: validated experiment-row and suite configuration.
- `experiments.ExperimentResult`: immutable per-algorithm experiment metrics.
- `experiments.ExperimentRunner`: non-UI seeded experiment execution with progress and cancellation.
- `experiments.TestSuiteIO`: versioned `.mstests` persistence and legacy CSV export.
- `experiments.ExperimentCli`: headless suite execution, saved-result analysis,
  exports, and measurement validation.
- `analysis.TableQuery` and `analysis.ResultQuery`: shared typed expression
  parsing and global filtering and sorting of results.
- `analysis.ResultRecord` and `analysis.ResultFiles`: measurement provenance and
  raw-result and summary CSV I/O.
- `analysis.ResultStatistics` and `analysis.ResultChart`: per-map and
  per-algorithm aggregation and PNG rendering shared by Swing and the CLI.
- `ui.TestSuiteWindow`: Swing configuration, execution, results, map review, and
  export workflow and coordination.
- `ui.TestConfigurationTableModel` and `ui.ExperimentResultTableModel`:
  extracted configuration/result table state.
- `ui.TestConfigurationTable`: context-sensitive saved-map and endpoint
  editability behavior.
- `ui.TestSuiteTableComponents`: package-private reusable experiment-table
  editors and renderers, including algorithm selection, saved-map import, and
  Review actions. These components are wired by the Swing table classes.
- `ui.TestSuiteConfigurationPanel` and `ui.ExperimentResultsPanel`: extracted
  Swing layout and action wiring for configuration and result areas.
- `ui.ResultAnalysisPanel` and `ui.TableHeaderQuery`: background result
  analysis and column actions that update the editable query fields.
- `test.RegressionTests`: dependency-free checks for weighted maps, all algorithms,
  reproducibility, persistence, CSV export, and cancellation.

Test fixtures are kept separate from application sources:

- `test/resources/maps/`: sample `.msmap` files used by regression checks.
- `test/resources/suites/`: sample `.mstests` experiment suite.

## API and Extension Notes

Useful extension points:

```java
Maze.Node start = maze.getStart();
MazeRenderer.setNodeColor(start, java.awt.Color.MAGENTA);
MazeRenderer.resetNodeColor(start);
```

Typical programmatic solve usage:

```java
Maze maze = Maze.importFromFile(
        "test/resources/maps/test_346x346.msmap");
Maze.Node next = maze.getStart().getNeighbor(Maze.Dir.EAST);
if (next != null && maze.getMoveCost(maze.getStart(), next) > 0) {
	System.out.println("East movement is enabled.");
}
MazeSolver solver = new MazeSolver();
solver.setMaze(maze);
solver.setAlg("A*");

while (!solver.isFinished()) {
	 solver.updateSolve();
}

if (solver.isSolved()) {
	 java.util.List<java.awt.Point> path = solver.getFinalPath();
	 System.out.println("Path nodes: " + path.size());
	 System.out.println("Path cost: " + solver.getPathCost());
}
```

## Launch From Release JAR

If you downloaded a release artifact from GitHub (or received a provided prebuilt JAR), launch it as follows:

1. Install Java JDK 21 (LTS) or newer.
2. Download the JAR and extract it first if it is packaged in a zip.
3. Open a terminal in the folder containing the JAR.
4. Run:

```bash
java -jar MazeSolver.jar
```

If your file has a different name, replace `MazeSolver.jar` with the actual filename.

The title screen is painted in Swing and needs no external image assets.

## Troubleshooting

1. App does not start

- Confirm javac and java are on PATH.
- Rebuild by compiling application sources in `src\` and regression tests in
  `test\java\` from the repository root.

2. Maze fails to load

- Check file format and dimensions.
- Ensure start/end are on traversable cells (1).

3. Slow behavior on huge maps

- Use lower zoom to reduce overdraw.
- Prefer Instant mode for completion-only runs.

4. Unexpected debug output volume

- The renderer prints diagnostics during load.
- This is useful for validation but verbose on large maps.

## Development Notes

Useful commands from repo root:

```powershell
New-Item -ItemType Directory -Force build\classes | Out-Null
$sources = @(
    (Get-ChildItem -Filter *.java src -Recurse).FullName
    (Get-ChildItem -Filter *.java test\java -Recurse).FullName
)
javac -Xlint:all -d build\classes $sources
java -cp build\classes app.MS
```

Convenience scripts are available under `scripts\`:

```powershell
.\scripts\compile-and-run.ps1
.\scripts\build-executable.ps1
```

The PowerShell launcher discovers JDK 21 or newer from PATH, `JAVA_HOME`, or
common Windows installation directories. It selects matching `java` and
`javac` binaries. Packaging still requires `jpackage` on PATH and the .NET 8 SDK.

The first script compiles the application and regression tests, runs the
regression suite, and launches the Swing application. The second creates a
self-extracting single-file Windows executable:
`dist\MazeSolver-V0.9.0a.exe`. The executable embeds the complete application
image and Java runtime, extracts to a temporary directory when launched,
waits for the application to close, and removes its temporary files. It can
be distributed as one file and does not require Java on the target machine.
Normal launches do not open a terminal window. For troubleshooting, launch
the executable with the optional debug flag:

```powershell
.\dist\MazeSolver-V0.9.0a.exe --debug
```

### Developer Notes: experiment execution

`ExperimentRunner` keeps the UI-independent execution path separate from Swing
and preserves deterministic result ordering. Sequential mode executes one map
and its algorithms directly. Fixed parallel mode uses the processor-aware
worker cap (logical processors minus one, with a minimum of one). Adaptive
mode selects its worker count once at suite startup using CPU load and heap
headroom. It reserves two processors on systems with at least four processors,
otherwise one, and can reduce the selected count further under high CPU load.
It does not resize the executor during a run.

Parallel execution uses one priority executor. Map-generation tasks are
prioritized while the memory frontier is open, but ready maps and queued solver
tasks are bounded to approximately two worker-widths. This bound is important:
queued solver tasks capture their shared map, so an unbounded queue would retain
large maps even after generation has finished.

The coordinator is completion-driven. Worker completion releases a notification
permit, avoiding fixed polling delays. A map is removed from scheduler state
after its final selected algorithm completes; the resulting
`ExperimentResult` contains measurements and metadata, not the map object.
Clearing scheduler collections during cancellation and shutdown makes those
maps eligible for garbage collection promptly. The JVM may retain committed heap
for reuse, so process working-set size is not the same as live object usage.

Admission reservations estimate map cells, solver tables, and transient solver
overhead with a safety factor. They are a scheduling guard, not a heap
measurement. Diagnostics therefore expose both reservation state and JVM heap
state. On hybrid CPUs, `Runtime.availableProcessors()` reflects processors
exposed to the JVM; process affinity can be used for controlled comparisons.

The coordinator reserves a map plus one active solver estimate with a 20%
safety allowance, against 90% of available heap headroom. Observed heap growth
is subtracted from outstanding reservations to avoid counting it twice.
These estimates do not guarantee that every workload fits the heap.

Debug mode opens a terminal window while the application runs.
Building it requires JDK 21 or newer, `jpackage`, and the .NET 8 SDK for the
native self-extractor wrapper.

On Linux, make the equivalent scripts executable once and run:

```bash
chmod +x scripts/compile-and-run.sh scripts/build-executable.sh
./scripts/compile-and-run.sh
./scripts/build-executable.sh
```

The Linux application image is created at `dist/MazeSolver/bin/MazeSolver`,
and the portable bundle is `dist/MazeSolver-V0.9.0a-portable-linux.zip`. These
bundles include the Java runtime and do not require Java to be installed on
the target machine. The generated `build/` and `dist/` directories are
ignored by Git.

On macOS, build the native installer on a Mac with JDK 21 or newer:

```bash
chmod +x scripts/build-executable-macos.sh
./scripts/build-executable-macos.sh
```

This creates `dist/MazeSolver-V0.9.0a.dmg`. Native packaging tools receive the
numeric version `0.9.0`. The package architecture matches
the installed JDK: use an Intel JDK for Intel Macs or an Apple-silicon JDK for
Apple-silicon Macs. `jpackage` does not cross-build native macOS packages from
Windows or Linux. The generated application is unsigned by default; public
distribution may require an Apple Developer ID certificate, code signing, and
notarization.

Optional cleanup of compiled artifacts:

```powershell
Remove-Item -Recurse -Force build
```

`build/`, `dist/`, and `doc/latex/` are disposable generated output. Regenerate
packages after source changes before distributing them. Keep fixtures under
`test/resources/`; save ad hoc benchmark output under `build/results/` so it
does not enter version control. Generated HTML and the current release PDF
remain under `doc/`.

Validate JavaDoc without generating the Doxygen/PDF artifacts:

```powershell
$files = (Get-ChildItem src -Filter *.java -Recurse).FullName
javadoc -quiet -Xdoclint:all -d "$env:TEMP\maze-javadoc" $files
```

Doxygen is configured by `doc/Doxyfile`. Generate the HTML reference and
release PDF with the documentation script:

```powershell
.\scripts\generate-documentation.ps1
```

The script checks for Doxygen and XeLaTeX. If either is missing, it asks before
installing it through an available package manager. It replaces the generated
`doc\html` directory so deleted classes leave no stale pages, builds the PDF,
and removes the temporary `doc\latex` folder.
The remaining documentation outputs are `doc\html`,
`doc\Maze-Solver-docs_V0.9.0a.pdf`, and `doc\Doxyfile`.

On Linux, use the equivalent script:

```bash
chmod +x scripts/generate-documentation.sh
./scripts/generate-documentation.sh
```

## License

This project is licensed under the terms in LICENSE.
