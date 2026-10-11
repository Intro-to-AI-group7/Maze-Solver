# Maze-Solver Remaining Work

The core SOFE 3720 Option 1 Intelligent Route-Planning Agent is implemented.
Weighted maps, cost-aware search, reproducible experiment suites, persistence,
exports, result review, navigation, packaging, and regression coverage are
complete. Feature and architecture documentation is maintained in
[README.md](README.md), while generated API documentation is maintained under
`doc/html`.

## Remaining Work

These items are optional follow-up improvements and are not blockers for the
current route-planning agent implementation.

- [x] Reduce large-suite experiment memory usage by regenerating generated maps
      from persisted seeds, paging result rows, reporting active conditions, and
      surfacing heap exhaustion.
- [x] Add opt-in bounded parallel solver execution for experiment suites while
      retaining sequential execution as the default and preserving result order.
- [x] Persist the parallel execution mode in suite files and verify that
      seeded sequential and parallel runs produce matching solver results.
- [x] Add sequential, fixed parallel, and adaptive parallel execution modes
      through the test-suite editor dropdown.
- [x] Show the currently active worker count beside JVM memory utilization.
- [x] Add adaptive execution diagnostics showing selected workers, CPU load, heap
      headroom, and the observed peak CPU load used for the next decision.
- [x] Keep sequential mode visible as one assigned worker and reset the worker
      readout to zero only when the suite is idle.
- [x] Replace the fixed four-worker ceiling with a processor-aware cap that
      reserves one processor for the operating system and UI, independent of
      the number of algorithms selected on a single map.
- [x] Share each generated map across its independent solver jobs while
      scheduling jobs from multiple map iterations through one bounded
      processor- and memory-aware executor.
- [x] Add live suite metrics for elapsed runtime, solves completed in the
      latest measurement window, and average solves per second beside memory
      utilization and worker count.
- [x] Count parallel solves when each worker actually finishes rather than
      when deterministic result collection reaches that worker's future.
- [x] Drive progress-bar updates from the live completion count instead of
      queuing one Swing event per solve, and show used, committed, and maximum
      heap separately to clarify garbage-collection fluctuations.
- [x] Log full experiment failures to stderr so map-generation and solver
      exceptions are retained in the UI error log as well as shown to the user.
- [x] Make approximate-distance endpoint selection deterministic after its
      random search fallback so valid distance targets do not terminate a
      large suite after probabilistic endpoint-selection exhaustion.
- [x] Prefetch map iterations using a dimension-based memory estimate and a
      bounded heap-headroom budget, releasing each batch after its results are
      collected.
- [x] Split the parallel compute budget between prioritized map-generation
      workers and solver workers so map creation can proceed concurrently
      without oversubscribing the processor/UI reservation.
- [x] Ensure adaptive batch executors and CPU-monitor threads are cleaned up
      when map generation or solver execution fails.
- [x] Preserve the last measured instantaneous solves-per-second value after
      a suite completes instead of replacing it with zero.
- [x] Add an opt-in results-table follow mode that keeps the view on the
      dynamically growing final page during a suite run.
- [x] Add an alternative live solve-log view that follows each completed
      result as it arrives without changing table pagination.
- [x] Bound the live diagnostic solve log to the most recent 500 entries so
      long suites do not grow the Swing text component without limit.
- [x] Split the live log into solver and map-generation panes, report map
      readiness/dispatch/completion events, and show the bounded count of
      generated maps waiting in memory for solver dispatch.
- [x] Make automatic heap mode choose a launch-time JVM maximum from currently
      available system memory while reserving room for Windows and other apps;
      retain explicit manual heap sizing for controlled benchmark runs.
- [x] Add cooperative Pause/Resume controls and make cancellation clear ready
      maps, scheduler state, and completion queues so retained map references
      are released when a run is cancelled.
- [x] Restart the application after saving a changed memory setting so the new
      JVM heap allocation is applied immediately instead of waiting for a
      manual relaunch; route repository launches through the PowerShell
      launcher so automatic heap sizing is applied on restart.
- [x] Replace scheduler polling delays with completion notifications so map
      and solver completions wake the coordinator immediately.
- [x] Batch-dispatch every currently admitted ready map in one scheduler pass
      while retaining bounded executor and memory-admission limits.
- [x] Remove per-map and per-solver console output from the worker hot path to
      avoid stdout serialization; UI progress callbacks remain available.
- [x] Bound ready-map retention and queued solver tasks after observing that
      unrestricted batch dispatch allowed queued solver lambdas to retain
      hundreds of generated maps. The scheduler now limits both queues to
      approximately two worker-widths while preserving parallel execution.
- [x] Consolidate scheduler queue limits and executor shutdown timing into
      shared helpers/constants, add missing public listener Javadocs, and move
      scheduler implementation details into the developer section of README.
- [x] Remove confirmed unused scheduler/UI helpers and stale warning-prone
      constants; retain package-private Swing editors/renderers because table
      configuration wires them indirectly.
- [ ] Re-run the large parallel benchmark after restarting the application and
      confirm live-map count and heap occupancy fall as solver work completes.
- [ ] Benchmark sequential versus fixed/adaptive parallel execution with the
      same suite and record throughput, queue wait, and memory results.
- [x] Reduce map-generation setup overhead by pre-sizing candidate storage and
      caching repeated test-case values without changing the seeded shuffle
      sequence or solver implementations.
- [x] Add a headless command-line experiment runner with selectable execution
      mode, repeatable seeded benchmarks, CSV export, cooperative Ctrl+C
      cancellation, and periodic heap/worker/progress monitoring.
- [x] Batch-drain completed map and solver futures in the suite-wide parallel
      coordinator so multiple worker completions are handled per wake-up.
      On the example suite, five fixed-parallel CLI runs improved from a
      46.941 ms average to 40.169 ms after the change.
- [ ] Use the command-line runner to record sequential, fixed-parallel, and
      adaptive-parallel timings on the same large suite, including map
      generation throughput, solver throughput, queue wait, and peak memory.
- [ ] Build the search-visualization and comparison overhaul in stages:
      - [ ] Define a shared visualization-session model for a map, one or more
            solver instances, per-solver colors, visibility, current node,
            explored nodes, path, status, and step timing.
      - [ ] Add a configurable visualization step-delay policy, including
            instant mode, a user-selected delay matching the animated renderer,
            and pause/resume/cancel controls. Keep delays out of benchmark
            runtime measurements.
      - [ ] Add a small-suite algorithm race view that runs selected algorithms
            on one shared map with an intentional step delay, updates each
            solver one step at a time, and shows progress, nodes explored,
            current cost, elapsed time, and completion state side by side.
      - [ ] Integrate race mode with the live test-suite diagnostics as an
            opt-in mode for small workloads only. Define safeguards so it
            cannot accidentally add animation delays to large benchmark runs.
      - [ ] Add multi-select result review from the results table, limited to
            results that reference the same effective map and compatible
            endpoint/cost configuration.
      - [ ] Add a synchronized comparison renderer that advances selected
            solvers one step at a time, assigns stable colors to each solver,
            draws distinct explored-node shades and solution-path shades, and
            displays a legend with algorithm names and status.
      - [ ] Add per-solver visibility toggles, including hide/show explored
            nodes and hide/show final path, without resetting that solver's
            position or timing.
      - [ ] Define behavior for incompatible selections, saved maps versus
            generated maps, different seeds, and results whose maps cannot be
            reconstructed; provide explicit UI explanations instead of
            silently dropping selections.
      - [ ] Add an optional detached live-maze window showing the most recent
            active worker, with worker hand-off behavior, throttled updates,
            bounded lifecycle, and safe closing when a suite is cancelled or
            completes.
      - [ ] Add focused regression tests for shared-map identity, synchronized
            stepping, visibility toggles, cancellation, delay exclusion from
            metrics, and safe UI cleanup.
- [ ] Design an extensible search-algorithm provider API so new algorithms can
      be added without expanding UI conditionals throughout the application.
      Document algorithm metadata, solver lifecycle, capabilities, metrics,
      visualization hooks, and compatibility requirements.
- [ ] Explore a far-future algorithm-maker/plugin workbench that can generate
      or configure search strategies safely. Keep this separate from the
      benchmark path and require validation, sandboxing/limits, reproducible
      execution, and explicit user approval before generated algorithms run.

### Visualization overhaul delivery order

1. **Foundation:** extract a solver-agnostic visualization session around the
   existing incremental `MazeSolver` API. The session owns stepping and
   lifecycle; renderers only draw snapshots. This avoids duplicating stepping
   logic between animated review, race mode, and live-worker monitoring.
2. **Single-solve parity:** move the existing animated renderer to the shared
   delay/pause/step model without changing its current behavior.
3. **Offline comparison:** add multi-select review and synchronized stepping
   for completed results on the same map. Validate map identity using the
   effective seed plus the complete generation configuration, or saved-map
   content identity.
4. **Race mode:** reuse the comparison renderer for deliberately delayed
   concurrent or interleaved solves. Show that the delay is presentation-only
   and never part of benchmark timing or result persistence.
5. **Live worker window:** expose snapshots from active experiment workers
   through a bounded, throttled diagnostic channel. The window follows the
   newest worker by default but permits pinning a worker and safely handles
   worker completion.
6. **Extensibility:** formalize algorithm registration and visualization
   capabilities before considering plugins or generated strategies.

### Visualization overhaul feasibility and design notes

The overhaul is feasible because `MazeSolver` already supports incremental
execution through `updateSolve()` and exposes the metrics needed for live
display, including visited-node count, current path cost, final path, solve
status, and computation time. `MazeRenderer` also provides existing timer,
delay, pause, restart, and cell-coloring behavior that can be extracted and
reused.

Keep the following boundaries during implementation:

- Benchmark execution and visualization must remain separate. A presentation
  delay must never slow a normal suite or become part of benchmark runtime
  measurements.
- Comparison sessions must create independent solver instances over one
  shared read-only map. A renderer must never mutate another solver's search
  state or the map.
- Swing rendering should consume immutable snapshots. The UI must not inspect
  mutable solver state from an executor thread.
- The existing static/global renderer state should be reduced or wrapped behind
  a reusable session model so multiple windows and comparison sessions do not
  interfere with each other.
- Large benchmark suites must remain protected by throttled updates, bounded
  queues/history, cancellation, and an explicit small-suite limit for race
  mode.

The shared visualization model should eventually include:

- A map reference and session lifecycle (`running`, `paused`, `completed`,
  `cancelled`, and `failed`).
- One independent solver state per algorithm.
- Current node, explored nodes, optional frontier state, current and final
  path cost, final path, status, and computation elapsed time.
- Presentation elapsed time kept separate from solver computation time.
- Stable per-solver colors, legend metadata, and explored/path color shades.
- Per-solver visibility for explored nodes and final paths.
- Pause, resume, reset, cancel, single-step, and configurable delay controls.

Race mode should use one shared timer and deterministic interleaved stepping
for small workloads: each active solver receives one `updateSolve()` call per
tick before the view repaints. This is preferable to delayed worker threads
because it makes the visual comparison fair and keeps the presentation logic
out of benchmark workers. Completed solvers should remain visible until the
race ends so completion order and final metrics can be compared.

Multi-result review must validate a stable map identity before opening. For
generated maps, the identity should include the effective seed and complete
generation configuration, including dimensions, source mode, density,
reachability, costs, and endpoints. Saved maps should use a stable content
identity or canonical serialized-map hash; matching a seed alone is not
sufficient. Unsupported selections must explain the incompatibility and leave
the existing review unchanged.

Live worker visualization should begin with lightweight immutable snapshots
(worker identity, current node, counters, costs, timing, and status). Full
visited-cell rendering should only be added after measuring the cost of
bounded snapshots or incremental cell deltas. Stale snapshots may be dropped
when the UI falls behind, and workers must never block waiting for Swing.
The detached window should follow the newest worker by default, support
pinning a worker, and safely handle worker completion, suite cancellation,
suite completion, and window closure.

Do not begin the algorithm-maker idea until the provider boundary exists.
First introduce an algorithm provider/factory abstraction with stable IDs,
display metadata, lifecycle, metrics, compatibility requirements, and
visualization capabilities. Any later configurable or generated strategy must
remain outside the normal benchmark path and require deterministic execution,
resource limits, validation, sandboxing or isolation, and explicit user
approval.

### Visualization overhaul open decisions

- Decide whether the initial comparison view renders only explored cells and
  paths, or also current nodes and frontier/open-set cells.
- Decide whether race stepping is strictly round-robin or supports a shared
  timer cadence with different solver advancement rates.
- Define the maximum algorithm count, map dimensions, and workload allowed in
  race mode.
- Decide whether completed results only are eligible for multi-select review or
  whether active solves can also be attached to a session.
- Finalize the stable saved-map identity format.
- Decide whether live-worker following is automatic only or also supports
  pinning by condition, iteration, and algorithm.

No implementation of this overhaul has started. The existing scheduler, live
log, active-worker diagnostics, and animated renderer remain unchanged until
the foundation phase is deliberately started.

### Visualization overhaul acceptance criteria

- Benchmark execution remains headless-compatible and produces the same
  deterministic results regardless of whether visualization is disabled.
- A comparison session never mutates the shared map or another solver's
  search state.
- Every visible solver has an explicit color, legend entry, visibility state,
  and completion/cancellation state.
- Step delay affects only the viewing session; measured solve runtime remains
  computation time.
- Large suites remain protected from unbounded UI work through throttling,
  bounded history, cancellation, and an explicit small-suite race limit.
- Unsupported multi-result selections explain the incompatibility and leave
  the current review unchanged.
- [x] Add regression coverage proving adaptive execution completes and keeps
      deterministic iteration ordering.
- [ ] Benchmark adaptive execution on representative map sizes and tune the
      worker cap and memory-per-worker estimate if needed.
- [ ] Benchmark the map-generation/solver worker split on representative map
      sizes and tune the map-worker fraction, prefetch fraction, and queue
      multiplier using completion throughput and system responsiveness.
- [x] Replace iteration-ordered map waiting with completion-order dispatch so
      ready maps can start solving while another map is still generating.
- [x] Include solver-state memory and a safety factor in map admission
      estimates; adjust map-worker count when fewer maps fit in current heap
      headroom.
- [ ] Add a throttled scheduler diagnostic summary covering admitted,
      generating, ready, and solving maps plus reserved versus actual heap.
- [x] Add heap-used, uncommitted-headroom, and maximum-heap values to map
      admission diagnostics so low committed memory can be distinguished from
      low scheduler admission.
- [x] Replace batch lifetime with a coordinated admission loop: reserve
      `map + all solver states + safety factor`, generate maps while reservations
      fit, and admit the next map immediately when a map's final solver releases
      its reservation.
- [x] Hold ready maps until map admission is full (or all planned maps are
      admitted), so an available solver slot cannot take priority over another
      map that still fits the reservation budget.
- [x] Bound map-generation submissions to one worker-sized wave while retaining
      the strict frontier gate, so a finite suite does not queue unbounded work
      or start solving while the next map still fits.
- [x] Replace separate map and solver pools with one shared worker pool:
      initially prioritize map-generation tasks, then queue solves after the
      reservation frontier is full or all planned maps are admitted.
- [x] Add map-phase transition and per-solver timing diagnostics to distinguish
      map-generation delays, solver delays, and intentional admission waits.
- [x] Build one ordered map-generation queue for the complete suite so memory
      reservations span test cases; admit the next row or iteration whenever a
      completed map's final solver releases its reservation.
- [x] Correct map admission estimates so a retained map is not charged for
      every selected algorithm's solver state; queued solver tasks allocate
      state only when workers execute them.
- [x] Increase the map admission ceiling from 75% to 90% of JVM headroom;
      the previous ceiling left several gigabytes unused on a 16 GB heap.
- [x] Bound map-generation submissions to one worker-sized wave so solver
      tasks are not buried behind thousands of already-admitted map tasks.
- [x] Restore strict map-frontier priority: submit solver work only after the
      active map wave finishes and the next queued map cannot fit, or all maps
      have been admitted.
- [x] Add a separate Swing map-generation progress bar so suite operators can
      compare generated-map progress with completed-solver progress.
- [x] Show the scheduler's retained-map reservation estimate and current
      admission budget on the map progress bar.
- [x] Fail clearly when the first queued map's reservation cannot fit even in
      an otherwise empty admission frontier.
- [ ] Use large-suite diagnostics to tune the reservation estimate; current
      per-case iteration counts may be too small to fill a large configured JVM
      heap even when the scheduler is behaving correctly.
- [x] Reduce the map reservation safety factor from 50% to 20% after comparing
      scheduler reservations with observed JVM heap usage.
- [x] Avoid the producer polling timeout when no map-generation task is in
      flight, preventing an unnecessary delay before each ready-map solve set.
- [x] Dispatch ready-map solver work immediately when the next map cannot fit;
      the coordinator waits for map generation only when no ready map exists.
- [x] Replace the footer heap summary with live solving-versus-mapping worker
      counts and elapsed/throughput metrics.
- [x] Correct admission for estimator drift by charging only the portion of
      the reservation not already represented by observed heap growth; this
      avoids double-counting retained objects against remaining heap headroom.
- [x] Prioritize queued map-generation tasks over queued solver tasks in the
      bounded shared executor, while preserving the total worker limit.
- [x] Add regression coverage proving that parallel suite execution uses
      multiple concurrent map-generation workers.
- [x] Make worker diagnostics sample four times per second and show the
      mapping/solving peak observed between UI refreshes, rather than a
      lifetime peak that becomes stale.
- [x] Replace the 256 MB per-solver admission floor with cell-based search-table
      sizing plus a fixed solver-object overhead; increase the bounded queue so
      small maps are not artificially limited to a few resident maps.
- [ ] Profile individual map generation before considering parallelizing its
      internal layout/endpoint steps; prefer concurrent map iterations unless
      profiling proves one map dominates.
- [ ] Add an optional Windows hybrid-CPU affinity setting for P-core-only
      performance comparisons.
- [ ] Add aggregate statistical analysis for repeated experiment runs,
      including averages, medians, minimums, maximums, and standard deviation
      by algorithm and test condition.
- [ ] Add optional benchmark charts or a report-oriented analysis export.
- [ ] Add an application-level logging toggle to control verbose diagnostic
      console output.
- [x] Regenerate the Doxygen HTML reference and release PDF for the 0.8.0
      release documentation pass.
- [ ] Improve user-facing explanations for disabled saved-map configuration
      fields if usability testing identifies confusion.
