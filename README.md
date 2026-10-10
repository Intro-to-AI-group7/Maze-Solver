# Maze-Solver

Maze-Solver is a Java Swing desktop app for loading, visualizing, and solving grid mazes using DFS, BFS, Uniform Cost Search, Greedy Best-First Search, and A*. Maze files now support optional per-direction movement costs while preserving the original unweighted format.

It is designed for both interactive exploration and large-map performance testing, with a paint-based renderer that supports very large mazes efficiently.

## Table of Contents

1. Overview
2. Features
3. Requirements
4. Quick Start
5. How to Use
6. Maze File Format
7. Included Maze Files
8. Algorithms
9. Rendering and Performance
10. Project Structure
11. API and Extension Notes
12. Launch From Release JAR
13. Troubleshooting
14. Development Notes
15. License

## Overview

Maze-Solver has four main responsibilities:

1. Load maze files from disk.
2. Validate and visualize the maze graph.
3. Run DFS, BFS, UCS, Greedy Best-First Search, or weighted A* incrementally or instantly.
4. Show traversal progress and final path with clear visual semantics.

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

## Requirements

- Java JDK 17+ (11+ may work, but 17+ is recommended).
- Windows, macOS, or Linux with desktop GUI support.
- No external dependencies required.

## Quick Start

From the repository root:

1. Compile (PowerShell)

```bash
javac src\*.java
```

2. Run

```bash
java -cp src MS
```

Startup sequence:

1. Title screen appears.
2. Choose Import Maze or Generate Random.
3. If importing, select a maze text file. If generating, enter rows and columns,
   then optionally enable movement weights with a range from 1 through 100.
4. Solve interactively.

## How to Use

After loading or generating a maze:

- Start: begin or resume timer-based solving.
- Pause: stop solving without clearing current search state.
- Restart: clear colors and reset the active algorithm state.
- Load Map: replace the current session maze with another file.
- Save Map: export the currently loaded maze to a text file.
- Generate Map: create and load a new random maze without leaving the solver window.
- Algorithm: choose DFS, BFS, Uniform Cost Search, Greedy Best-First Search, or weighted A*.
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
  animated and instant solving times are directly comparable. The console also
  prints a diagnostic line containing the result, interval, algorithm, visited
  node count, final path cost, compute time, and wall-clock session time after
  each completed solve.
- Zoom +/-: adjust cell size.
- Ctrl + Mouse Wheel: zoom while cursor is over the maze viewport.

Saved maze files automatically receive a `.txt` extension when one is not
provided. The import dialog filters for `.txt` maze files; reopening the dialog
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

The optional section follows the complete binary layout:

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

Located in src:

- test.txt: small sample map for quick checks.
- test_100x100.txt: medium map for standard performance checks.
- test_346x346.txt: larger map for stress testing.
- test_1000x1000.txt: very large map for renderer scalability tests.
- test_weighted_100x100.txt: weighted 100x100 example with directional costs.

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
- A*: weighted informed search using cumulative directional movement costs and
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
│  ├─ MS.java
│  ├─ Maze.java
│  ├─ MazeSolver.java
│  ├─ MazeRenderer.java
│  ├─ test.txt
│  ├─ test_100x100.txt
│  ├─ test_346x346.txt
│  ├─ test_1000x1000.txt
│  ├─ test_weighted_100x100.txt
│  └─ images/
└─ doc/
```

Core classes:

- MS: application entry point.
- Maze: model for occupancy, neighbors, directional costs, start, goal, import/export.
- MazeSolver: incremental solver engine and path reconstruction.
- MazeRenderer: UI flow, controls, rendering, and solve orchestration.

## API and Extension Notes

Useful extension points:

```java
Maze.Node start = maze.getStart();
MazeRenderer.setNodeColor(start, java.awt.Color.MAGENTA);
MazeRenderer.resetNodeColor(start);
```

Typical programmatic solve usage:

```java
Maze maze = Maze.importFromFile("src/test_346x346.txt");
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

1. Install Java 17+.
2. Download the JAR and extract it first if it is packaged in a zip.
3. Open a terminal in the folder containing the JAR.
4. Run:

```bash
java -jar MazeSolver.jar
```

If your file has a different name, replace `MazeSolver.jar` with the actual filename.

For builds distributed with external assets, keep this layout:

```text
release-folder/
├─ MazeSolver.jar
└─ images/
   └─ TitleImage.jpg
```

If the app starts but the title image does not appear, ensure the `images` folder is present next to the JAR. The title screen images can be loaded from `images/TitleImage.jpg`. 

## Troubleshooting

1. App does not start

- Confirm javac and java are on PATH.
- Rebuild with javac src\*.java from the repository root.

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

```bash
javac src\*.java
java -cp src MS
```

Optional cleanup of compiled artifacts:

```bash
del src\*.class
```

Optional docs generation can be done later from the doc tooling already included in this repo.

## License

This project is licensed under the terms in LICENSE.