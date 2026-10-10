# Option 1 Intelligent Route-Planning Agent

This document summarizes the work required to evolve the existing Maze-Solver application into the weighted intelligent route-planning agent described in the course requirements and the group proposal. Existing components are shown in blue; planned additions or changes are highlighted in orange.

Status notes use the following fields:

- **Implementation status**: whether the repository currently contains the described change.
- **Automated validation**: whether the change has been compiled or exercised by an automated smoke test.
- **Human confirmation**: whether the user or another developer has independently confirmed or tested the behavior. Weighted-map and weighted-solver behavior has been manually confirmed; remaining roadmap items may still be pending confirmation.

## Table of Contents

- [Remaining Work Summary](#remaining-work-summary)
- [Application Architecture](#application-architecture)
  - [Technology Stack Summary](#technology-stack-summary)
  - [Data Storage and External Services](#data-storage-and-external-services)
  - [Key Architectural Decisions](#key-architectural-decisions)
- [Component Relationships](#component-relationships)
  - [Component Inventory](#component-inventory)
- [Implementation Plan](#implementation-plan)
  - [1. Define the Weighted Problem Model](#1-define-the-weighted-problem-model)
  - [2. Extend Maze Data and File Handling](#2-extend-maze-data-and-file-handling)
  - [3. Implement the Required Search Algorithms](#3-implement-the-required-search-algorithms)
  - [4. Introduce Structured Search Results](#4-introduce-structured-search-results)
  - [5. Update the Interactive UI](#5-update-the-interactive-ui)
  - [6. Add Reproducible Experiment Execution](#6-add-reproducible-experiment-execution)
  - [7. Add Metrics Export and Analysis Inputs](#7-add-metrics-export-and-analysis-inputs)
  - [8. Add Automated Tests](#8-add-automated-tests)
  - [9. Update Documentation](#9-update-documentation)
- [Recommended Build Order](#recommended-build-order)
- [Definition of Done](#definition-of-done)

## Remaining Work Summary

### Must be added

- **Completed** - Weighted movement costs with positive-integer validation.
  - Automated validation: Yes - compilation and weighted import/export smoke tests passed.
  - Human confirmation: Yes.
- **Completed** - Backward-compatible weighted map import and export.
  - Automated validation: Yes - weighted round-trip and legacy-map compatibility smoke tests passed.
  - Human confirmation: Yes.
- **Completed** - Uniform Cost Search using accumulated route cost `g(n)`.
  - Positive movement costs guarantee minimum-cost solutions.
  - Human confirmation: Yes - repeated instant weighted-map runs matched A*.
- **Completed** - Greedy Best-First Search using Manhattan heuristic `h(n)`.
  - The algorithm is intentionally not guaranteed to minimize route cost.
  - Human confirmation: Yes - repeated instant weighted-map runs produced
    deterministic valid routes.
- **Completed** - Cost-aware A* Search.
  - Automated validation: Yes - weighted A* selected a lower-cost longer route in a smoke test.
  - Human confirmation: Yes - repeated instant weighted-map runs matched UCS.
- **Completed** - Total path-cost calculation and display.
  - `MazeSolver` calculates and exposes path cost through `getPathCost()`.
  - UI displays live current-route cost and final path cost.
  - Automated validation: Yes - DFS, BFS, UCS, Greedy, and A* path costs were checked in a smoke test.
  - Human confirmation: Yes.
- **Completed** - Solver timing reports computation time rather than animation
  delay or renderer work. A monotonic clock surrounds only each search-step
  call, avoiding platform-dependent thread CPU clock quantization. Human
  confirmation: Yes - compared instant, 10 ms animated, and 50 ms animated
  solves with matching visited-node counts and interval-independent compute
  times.
- Configurable or seeded start and goal selection.
- **Partially completed** - Random generation supports optional independent
  directional weights with a validated 1..100 range. Seeded generation,
  obstacle-density controls, and reproducible cost distributions remain pending.
- A non-UI experiment runner for repeated algorithm comparisons.
- CSV metrics export.
- Automated tests for maps, algorithms, heuristics, metrics, and reproducibility.

### Must be changed

- **Completed** - [Maze.java](../../../../../src/Maze.java) represents directional movement costs in addition to occupancy and neighbors.
  - Automated validation: Yes.
  - Human confirmation: No.
- **Completed** - [MazeSolver.java](../../../../../src/MazeSolver.java) uses actual transition costs instead of the former hardcoded `+1` in weighted searches and skips disabled edges in all current algorithms.
  - Automated validation: Yes.
  - Human confirmation: Yes.
- **Completed for the current cost convention** - The A* heuristic uses Manhattan distance. Because every valid movement cost is at least `1`, this is an admissible lower bound. A minimum-cost multiplier is not required unless the cost model changes.
  - Automated validation: Yes - weighted A* behavior was smoke-tested.
  - Human confirmation: No.
- [MazeRenderer.java](../../../../../src/MazeRenderer.java) exposes UCS and Greedy, route-cost display, and solve diagnostics. Comparison-oriented workflows remain future work.
- [MS.java](../../../../../src/MS.java) may need an experiment or command-line entry mode.
- [README.md](../../../../../README.md) and generated Doxygen documentation describe the weighted problem and required algorithms.

### Should remain

- Existing map import and export, with compatibility for old binary maps.
- Random maze generation as one available environment type.
- DFS and BFS as optional supplemental baselines.
- Incremental solving, instant solving, zoom, viewport clipping, and diagnostics.

### May be reduced or moved

- Full maze and neighbor-table console output should become an optional debug mode.
- DFS should not be presented as one of the three required weighted algorithms.
- Benchmark timing must be separated from Swing rendering, timers, dialogs, and console output.

## Application Architecture

<!-- mermaid-checked: no \n, no em-dash/en-dash, no {} in labels, subgraphs are id["label"], arrows are -->|"label"|, all subgraphs closed by end, ids unique -->
~~~mermaid
flowchart TD
    subgraph UserLayer["User Layer"]
        User["Student or evaluator"]
    end
    subgraph ExistingApp["Existing Java Swing Application"]
        Entry["MS entry point"]
        UI["MazeRenderer Swing UI"]
        Model["Maze grid and graph model"]
        Solver["MazeSolver incremental search"]
        ExistingGen["Random maze generator"]
        ExistingIO["Binary map import and export"]
    end
    subgraph PlannedAI["Planned Weighted Agent"]
        Costs["Weighted transition costs"]
        UCS["Uniform Cost Search"]
        Greedy["Greedy Best First Search"]
        WeightedAStar["Cost aware A Star Search"]
        Result["Structured search result"]
        Heuristic["Admissible cost heuristic"]
    end
    subgraph PlannedEval["Planned Evaluation Layer"]
        Generator["Seeded experiment generator"]
        Runner["Experiment runner"]
        Metrics["Metrics collector"]
        Csv["CSV result export"]
        Tests["Automated test suite"]
    end
    subgraph Files["Local Files"]
        Maps[("Weighted maze files")]
        Results[("Experiment CSV files")]
    end

    User -->|"selects map and algorithm"| UI
    Entry -->|"starts application"| UI
    UI -->|"loads and displays"| Model
    ExistingIO -->|"reads and writes"| Maps
    ExistingGen -->|"creates"| Model
    Model -->|"provides graph"| Solver
    Model -->|"provides costs"| Costs
    Costs -->|"weights transitions"| UCS
    Costs -->|"weights transitions"| WeightedAStar
    Costs -->|"weights transitions"| Greedy
    Heuristic -->|"estimates remaining cost"| Greedy
    Heuristic -->|"estimates remaining cost"| WeightedAStar
    UCS -->|"produces"| Result
    Greedy -->|"produces"| Result
    WeightedAStar -->|"produces"| Result
    Result -->|"renders path and metrics"| UI
    Generator -->|"creates seeded maps"| Model
    Runner -->|"executes same map"| UCS
    Runner -->|"executes same map"| Greedy
    Runner -->|"executes same map"| WeightedAStar
    Runner -->|"collects"| Metrics
    Metrics -->|"writes"| Csv
    Csv -->|"stores"| Results
    Tests -.->|"verifies"| Model
    Tests -.->|"verifies"| UCS
    Tests -.->|"verifies"| Greedy
    Tests -.->|"verifies"| WeightedAStar

    classDef existing fill:#d9ecff,stroke:#2878b5,color:#111;
    classDef planned fill:#ffe0b2,stroke:#d97706,color:#111;
    classDef data fill:#e5e7eb,stroke:#4b5563,color:#111;
    class User,Entry,UI,Model,Solver,ExistingGen,ExistingIO existing;
    class Costs,UCS,Greedy,WeightedAStar,Result,Heuristic,Generator,Runner,Metrics,Csv,Tests planned;
    class Maps,Results data;
~~~

### Technology Stack Summary

| Layer | Technology | Version | Purpose |
|---|---|---:|---|
| Runtime | Java | JDK 17+ recommended | Compiles and runs the application |
| UI | Java Swing and AWT | JDK standard library | Interactive map selection, controls, and rendering |
| Model | Plain Java classes | Current source | Stores grid occupancy, nodes, neighbors, start, and goal |
| Search | Plain Java collections | JDK standard library | Deques for DFS/BFS and priority queues for informed search |
| Persistence | Local text files | Custom format | Imports and exports maze layouts |
| Documentation | Doxygen-generated HTML/PDF | 1.12.0 generated docs | Documents public classes and methods |
| Evaluation | Planned plain Java runner | To be added | Repeats seeded trials and exports metrics |

### Data Storage and External Services

The application has no database, network API, cache, or external service. Its current persistence boundary is the local maze text file format. The planned evaluation layer should continue using local files: weighted maze files for reproducible instances and CSV files for experiment results. Rendering and benchmark execution should remain separate so UI work does not affect algorithm timing.

### Key Architectural Decisions

- Keep the existing plain Java and Swing architecture; the course emphasizes the AI algorithms rather than a UI framework migration.
- Preserve binary-map compatibility by treating omitted movement costs as an implicit cost of `1`.
- Make the required weighted algorithms independent of Swing so they can be tested and benchmarked deterministically.
- Use a shared Manhattan heuristic for Greedy Best-First Search and A*.
  Because the minimum valid movement cost is `1`, the distance is already a
  valid lower bound.

## Component Relationships

<!-- mermaid-checked: no \n, no em-dash/en-dash, no {} in labels, subgraphs are id["label"], arrows are -->|"label"|, all subgraphs closed by end, ids unique -->
~~~mermaid
flowchart LR
    subgraph cPresentation["Presentation"]
        cEntry["MS"]
        cRenderer["MazeRenderer"]
        cPaint["Paint based grid"]
    end
    subgraph cModelLayer["Model and IO"]
        cMaze["Maze"]
        cNode["Maze Node"]
        cMapIO["Map import and export"]
        cGenerator["Random maze generation"]
    end
    subgraph cSearchLayer["Search Engine"]
        cSolver["MazeSolver"]
        cUcs["Uniform Cost Search"]
        cGreedy["Greedy Best First"]
        cAStar["Cost aware A Star"]
        cHeuristic["Weighted heuristic"]
        cSearchResult["Search result"]
    end
    subgraph cEvaluationLayer["Evaluation"]
        cExperimentGenerator["Seeded experiment generator"]
        cExperimentRunner["Experiment runner"]
        cMetricCollector["Metric collector"]
        cCsvWriter["CSV writer"]
        cTestSuite["Automated tests"]
    end

    cEntry -->|"launches"| cRenderer
    cRenderer -->|"creates and controls"| cSolver
    cRenderer -->|"renders"| cPaint
    cRenderer -->|"loads and saves"| cMapIO
    cRenderer -->|"requests"| cGenerator
    cMapIO -->|"builds"| cMaze
    cGenerator -->|"builds"| cMaze
    cMaze -->|"contains"| cNode
    cMaze -->|"supplies graph and costs"| cSolver
    cSolver -->|"selects"| cUcs
    cSolver -->|"selects"| cGreedy
    cSolver -->|"selects"| cAStar
    cHeuristic -->|"scores"| cGreedy
    cHeuristic -->|"scores"| cAStar
    cUcs -->|"returns"| cSearchResult
    cGreedy -->|"returns"| cSearchResult
    cAStar -->|"returns"| cSearchResult
    cSearchResult -->|"updates"| cRenderer
    cExperimentGenerator -->|"creates identical seeded inputs"| cExperimentRunner
    cExperimentRunner -->|"invokes"| cUcs
    cExperimentRunner -->|"invokes"| cGreedy
    cExperimentRunner -->|"invokes"| cAStar
    cExperimentRunner -->|"records"| cMetricCollector
    cMetricCollector -->|"serializes"| cCsvWriter
    cTestSuite -.->|"checks"| cMaze
    cTestSuite -.->|"checks"| cUcs
    cTestSuite -.->|"checks"| cGreedy
    cTestSuite -.->|"checks"| cAStar

    classDef current fill:#d9ecff,stroke:#2878b5,color:#111;
    classDef plannedComponent fill:#ffe0b2,stroke:#d97706,color:#111;
    class cEntry,cRenderer,cPaint,cMaze,cNode,cMapIO,cGenerator,cSolver current;
    class cUcs,cGreedy,cAStar,cHeuristic current;
    class cSearchResult,cExperimentGenerator,cExperimentRunner,cMetricCollector,cCsvWriter,cTestSuite plannedComponent;
~~~

### Component Inventory

| Component | Layer | Type | Responsibility |
|---|---|---|---|
| `MS` | Presentation | Existing entry point | Starts Swing on the event dispatch thread |
| `MazeRenderer` | Presentation | Existing UI controller and renderer | Owns title flow, controls, visualization, and solve orchestration |
| Paint-based grid | Presentation | Existing renderer | Efficiently paints visible maze cells |
| `Maze` | Model and IO | Existing domain model | Stores dimensions, occupancy, neighbors, start, and goal |
| `Maze.Node` | Model and IO | Existing graph node | Represents one traversable grid cell |
| Map import/export | Model and IO | Existing file boundary | Reads and writes binary maze files; must be extended for costs |
| Random maze generation | Model and IO | Existing generator | Creates traditional carved mazes with optional directional weights; seeded configurable generation remains pending |
| `MazeSolver` | Search Engine | Existing search coordinator | Maintains incremental frontier state and dispatches the selected algorithm |
| Uniform Cost Search | Search Engine | Implemented algorithm | Finds the least-cost route using accumulated path cost |
| Greedy Best-First | Search Engine | Implemented algorithm | Uses only the heuristic and may sacrifice route cost for search effort |
| Cost-aware A* | Search Engine | Implemented algorithm | Combines accumulated cost and an admissible remaining-cost estimate |
| Weighted heuristic | Search Engine | Implemented in `MazeSolver` | Computes an admissible Manhattan lower bound under the positive-cost convention |
| Search result | Search Engine | Planned value object | Carries path, total cost, explored nodes, runtime, algorithm, and status |
| Seeded experiment generator | Evaluation | Planned generator | Produces controlled maps with repeatable seeds and conditions |
| Experiment runner | Evaluation | Planned orchestration layer | Runs all algorithms on identical instances without rendering |
| Metric collector | Evaluation | Planned metrics component | Captures cost, explored nodes, runtime, and success |
| CSV writer | Evaluation | Planned persistence component | Exports machine-readable experiment results |
| Automated tests | Evaluation | Planned verification layer | Protects model, algorithm, heuristic, and reproducibility behavior |

## Implementation Plan

### 1. Define the Weighted Problem Model

**Status: Completed.** The repository uses directional edge costs in `[North, East, South, West]` order. `0` disables movement on an adjacent edge, while positive values represent valid movement costs. Legacy maps default adjacent open-cell movement to cost `1`. Automated validation has passed; independent human confirmation is still pending.

Before changing code, document one unambiguous cost convention. The recommended convention is:

- Movement remains four-directional.
- Every legal move has a positive integer cost.
- A move pays the cost of the cell being entered.
- Existing binary maps use cost `1` for every adjacent open-cell connection.
- The maze is static during a solve.
- The graph is undirected unless the project explicitly chooses directional costs later.

Use `long` for accumulated route cost even if individual costs remain positive integers. This avoids overflow during large experiments.

### 2. Extend Maze Data and File Handling

**Status: Partially completed.** Items 1, 2, 4, 5, 6, and 8 are implemented in [Maze.java](../../../../../src/Maze.java). Seeded generation controls for obstacle density and cost distributions remain pending. Automated validation covers compilation, weighted import/export, validation, and legacy compatibility; human confirmation is pending.

Update [Maze.java](../../../../../src/Maze.java) to:

1. **Completed** - Store directional costs for every traversable node transition.
2. **Completed** - Provide validated cost lookup for adjacent nodes.
3. **Not required for the current cost convention** - Provide a minimum valid step cost only if the cost model changes; current valid costs are at least `1`, so Manhattan distance is already admissible.
4. **Completed** - Preserve the existing binary format as a legacy format.
5. **Completed** - Add an optional `WEIGHTS` section with explicit cost data.
6. **Completed** - Export weighted maps without losing start, goal, dimensions, or costs.
7. Add seeded generation controls for obstacle density and cost distribution.
8. **Completed** - Keep neighbor validation independent from cost validation while validating weighted topology where appropriate.

The import path rejects negative, malformed, non-numeric, and dimension-mismatched costs with clear `IllegalArgumentException` messages. Zero is intentionally valid as the no-movement sentinel, so it is rejected only where topology requires a valid positive edge.

### 3. Implement the Required Search Algorithms

**Status: Completed.** UCS, Greedy Best-First Search, and weighted A* now share
the incremental solver API and directional movement-cost model. DFS and BFS
remain available as supplemental baselines.

Refactor [MazeSolver.java](../../../../../src/MazeSolver.java) so algorithm-specific state is isolated and all algorithms use the same maze transition-cost API.

#### Uniform Cost Search

- Frontier: priority queue ordered by `g(n)`.
- State: best known cost and parent per node.
- Completion: stop when the goal is removed from the frontier.
- Correctness: positive costs guarantee an optimal result.

#### Greedy Best-First Search

- Frontier: priority queue ordered by `h(n)`.
- State: discovered or closed tracking and parent per node.
- Completion: stop when the goal is removed from the frontier.
- Limitation: explicitly report that it is not guaranteed to minimize route cost.

#### Cost-aware A*

**Status: Completed.** The current implementation uses `long` cumulative costs, skips stale entries, skips disabled edges, and uses a directly implemented Manhattan heuristic. Automated weighted-route validation has passed; human confirmation is pending.

- Frontier: priority queue ordered by `g(n) + h(n)`.
- Transition update: `tentativeG = currentG + transitionCost`.
- Heuristic: Manhattan distance. This is admissible because the minimum valid movement cost is `1`.
- Stale queue entries: skip entries whose stored cost no longer matches the best-known cost.

Retain BFS and DFS as supplemental baselines if they remain useful, but do not use them as substitutes for UCS, Greedy, or A*.

### 4. Introduce Structured Search Results

**Status: Partially completed.** `MazeSolver` now exposes `getPathCost()` in addition to the existing solved state, path, visited-node count, and runtime. A dedicated result value object and start-to-goal path ordering remain pending. Automated validation has checked path costs; human confirmation is pending.

Add a result type instead of requiring callers to reconstruct separate values from the solver. It should contain:

- Algorithm name.
- Solved flag.
- Ordered start-to-goal path.
- Total path cost.
- Path length.
- Nodes explored.
- Search-only runtime.
- Optional seed and map metadata.

Make path ordering explicit. The current `getFinalPath()` documentation says the path begins at the goal and ends at the start, which is inconvenient for reporting and visualization. Prefer returning start-to-goal order and update the renderer accordingly.

Define `nodes explored` consistently as nodes removed from the frontier and finalized or expanded. Use the same definition across all required algorithms.

### 5. Update the Interactive UI

Update [MazeRenderer.java](../../../../../src/MazeRenderer.java) to:

- UCS, Greedy Best-First, and weighted A* are available in the algorithm selector.
- Display total route cost beside runtime and explored nodes.
- Display the active cost mode or a visible cost legend.
- Allow start and goal selection, or expose a controlled coordinate/seed dialog.
- Support importing and exporting weighted maps.
- Keep instant mode from including rendering time in algorithm metrics.
- Add a comparison action that runs all required algorithms on the same map.
- Make verbose maze and neighbor diagnostics opt-in.

Keep UI changes focused on demonstrating the AI system. The experiment runner should remain usable without opening Swing.

### 6. Add Reproducible Experiment Execution

Create a separate experiment package or set of plain Java classes. It should accept:

- Map dimensions.
- Obstacle density.
- Cost mode.
- Minimum and maximum cost.
- Seed.
- Start and goal policy.
- Number of repetitions.
- Algorithms to compare.

For each generated instance:

1. Freeze the map and cost data.
2. Run UCS, Greedy Best-First, and A* on independent solver instances.
3. Record results using the same map, start, goal, and seed.
4. Exclude rendering, file dialogs, logging, and CSV writes from timed search sections.
5. Preserve failed or unsolved runs as valid experiment results.

At minimum, support the proposal matrix:

- Sizes: 20x20, 50x50, and 100x100.
- Obstacle density: 10%, 25%, 40%, and 75%.
- Costs: uniform `1` and random positive costs.
- Ten seeded maps per condition.

Include deliberately contrasting maps where a shorter route has a higher cost than a longer route.

### 7. Add Metrics Export and Analysis Inputs

Export one CSV row per algorithm and map instance. Recommended fields:

```text
experiment_id,seed,rows,columns,obstacle_density,cost_mode,
min_cost,max_cost,start_row,start_col,goal_row,goal_col,
algorithm,solved,path_length,path_cost,nodes_explored,
runtime_ns,runtime_ms
```

The final analysis should explain causes, not only report values. It should address:

- Whether UCS and A* produce equal optimal costs.
- Whether A* explores fewer nodes than UCS.
- Whether Greedy explores fewer nodes and how often it sacrifices cost.
- How cost variation affects the heuristic.
- How obstacle density and map size affect each algorithm.
- Which cases produce no route or unusually high search effort.

### 8. Add Automated Tests

Add tests for:

- Legacy binary map compatibility.
- Weighted map parsing and round-trip export.
- Invalid cost values and malformed dimensions.
- Seed reproducibility.
- Start and goal validity.
- UCS optimality on a map with competing route costs.
- Greedy's non-optimal behavior on a deliberately misleading map.
- A* matching UCS on positive weighted maps.
- Heuristic admissibility.
- No-path and start-equals-goal cases.
- Consistent explored-node counts.
- Path-cost summation.
- CSV schema and result determinism.

If no test framework is desired, a small Java test harness can be added, but a standard test runner is preferable for repeatable progress-report evidence.

### 9. Update Documentation

**Status: Completed for the current feature set.** [README.md](../../../../../README.md), [Maze.java](../../../../../src/Maze.java), [MazeSolver.java](../../../../../src/MazeSolver.java), and [MazeRenderer.java](../../../../../src/MazeRenderer.java) document weighted maps, directional costs, disabled edges, UCS, Greedy, weighted A*, path costs, and solve diagnostics. Generated Doxygen has been regenerated.

Update [README.md](../../../../../README.md) with:

- The weighted route-planning problem definition.
- State, action, transition cost, and objective definitions.
- UCS, Greedy, and A* descriptions.
- The heuristic formula and admissibility explanation.
- Weighted map format and backward compatibility.
- Experiment commands and seed behavior.
- Metrics and CSV schema.
- Known limitations and failure cases.

Regenerate the Doxygen output under [doc/](../../../../../doc/) after public APIs stabilize. Do not manually edit generated HTML or PDF files.

## Recommended Build Order

1. Decide and document the transition-cost convention.
2. Add weighted maze storage and compatibility parsing.
3. Add cost-aware solver primitives and structured results.
4. Add model and algorithm tests.
5. Add seeded generation and the non-UI experiment runner.
6. Add CSV export.
7. Run the controlled experiment matrix.
8. Update README, Doxygen, presentation material, and final analysis.

## Definition of Done

- [x] Binary maps still load and solve with an implicit cost of `1`. Automated validation: Yes. Human confirmation: Yes.
- [x] Weighted maps load, validate, export, and reload without data loss. Automated validation: Yes. Human confirmation: Yes.
- [x] UCS and Greedy Best-First run on the same weighted maze model. Automated weighted smoke test and repeated manual runs passed.
- [x] UCS and A* return equal minimum route costs on the weighted example map. Automated weighted smoke test and repeated manual runs passed.
- [x] Greedy's route cost can be compared against the optimal cost. Repeated runs demonstrated a valid but more expensive Greedy route.
- [x] The UI displays current route cost, final path cost, explored nodes, runtime, and diagnostic path cost. Automated validation and manual testing passed.
- [ ] Start and goal selection or seeded placement is configurable.
- [ ] Experiments are reproducible from recorded seeds.
- [ ] At least the proposed map sizes, densities, cost modes, and ten repetitions per condition are supported.
- [ ] CSV results include route cost, explored nodes, runtime, and success status.
- [ ] Automated tests cover the model, all required algorithms, heuristic behavior, and reproducibility. Current weighted-map and solver checks are smoke tests, not a committed automated test suite.
- [ ] Benchmark timing excludes Swing rendering and UI delays. The solver's
  displayed computation-time metric now excludes these concerns; the non-UI
  benchmark runner is still pending.
- [x] README and generated API documentation match the final implementation. README, inline/Javadoc documentation, and Doxygen V0.6 output are updated.
- [ ] The final report explains algorithm behavior and trade-offs rather than only listing measurements.
