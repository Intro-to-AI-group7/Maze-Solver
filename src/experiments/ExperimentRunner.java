package experiments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.sun.management.OperatingSystemMXBean;

import maze.map.Maze;
import maze.search.MazeSolver;

/**
 * Executes configured experiment rows without Swing or rendering overhead.
 *
 * <p>One map is generated per row iteration and reused by every selected
 * algorithm. Solver timing therefore excludes map generation, result
 * construction, callbacks, and console output. Parallel execution uses
 * bounded admission so queued solver work cannot retain an unbounded number of
 * generated maps.
 */
public final class ExperimentRunner {
    private static final long LIVE_PROGRESS_INTERVAL_NS = 100_000_000L;
    private static final int JVM_WARMUP_ROUNDS = 3;
    /** Per-solver overhead reserved for the active solver state. */
    private static final long SOLVER_OVERHEAD_BYTES = 512L * 1024L;
    private static final int READY_MAP_QUEUE_MULTIPLIER = 2;
    private static final int SOLVER_QUEUE_MULTIPLIER = 2;
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_MS = 2_000L;
    /** Estimate for the generated map and its per-cell objects. */
    private static final long ESTIMATED_BYTES_PER_MAP_CELL = 64L;
    /**
     * Fraction of currently available heap allowed for prefetched maps.
     * The remaining 10% protects transient generation and UI allocations.
     */
    private static final double PREFETCH_HEADROOM_FRACTION = 0.90;
    /** Reserve a portion of the compute budget for concurrent map generation. */
    private static final double MAP_WORKER_FRACTION = 0.25;
    /** Bound queued map tasks without making queue length the memory policy. */
    private static final int PREFETCH_BATCH_MULTIPLIER = 8;
    /** Reserve additional headroom for temporary generation and solver objects. */
    private static final double MEMORY_SAFETY_FACTOR = 1.20;
    /** CPU load at which adaptive mode removes one worker from the next batch. */
    private static final double CPU_LOAD_LIMIT = 0.80;
    private static final SecureRandom SEED_SOURCE = new SecureRandom();
    private static final OperatingSystemMXBean OS_BEAN =
            ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
    private static volatile int activeWorkerCount;
    private static final AtomicInteger activeMapWorkerCount = new AtomicInteger();
    private static final AtomicInteger activeSolverWorkerCount = new AtomicInteger();
    private static final AtomicInteger peakMapWorkerCount = new AtomicInteger();
    private static final AtomicInteger peakSolverWorkerCount = new AtomicInteger();
    private static final Object workerPeakLock = new Object();
    private static volatile double lastAdaptiveCpuLoad = -1.0;

    /** Receives progress after each completed algorithm run. */
    public interface ProgressListener {
        /**
         * Reports completed work.
         *
         * @param completedRuns completed algorithm runs
         * @param totalRuns total planned algorithm runs
         * @param result result that was just completed
         */
        void onProgress(int completedRuns, int totalRuns, ExperimentResult result);
    }

    /** Receives progress after each map has finished generating. */
    public interface MapProgressListener {
        /**
         * Reports generated map progress.
         *
         * @param generatedMaps maps that have finished generating
         * @param totalMaps total maps planned for the suite
         * @param reservedBytes estimated bytes reserved by admitted maps
         * @param admissionBudgetBytes current admission budget
         * @param heapUsedBytes current JVM heap usage
         * @param maxHeapBytes configured JVM maximum heap
         * @param loadedMaps generated maps waiting for solver dispatch
         * @param message formatted lifecycle message for the map log
         */
        void onProgress(
                int generatedMaps, int totalMaps, long reservedBytes,
                long admissionBudgetBytes, long heapUsedBytes, long maxHeapBytes,
                int loadedMaps, String message);
    }

    private static final class SuiteMapWork {
        private final int order;
        private final TestSuite.TestCase testCase;
        private final long seed;
        private final int iteration;
        private final long reservationBytes;

        private SuiteMapWork(
                int order, TestSuite.TestCase testCase, long seed,
                int iteration, long reservationBytes) {
            this.order = order;
            this.testCase = testCase;
            this.seed = seed;
            this.iteration = iteration;
            this.reservationBytes = reservationBytes;
        }
    }

    private static void reportMapProgress(
            MapProgressListener listener, int generatedMaps, int totalMaps,
            long reservedBytes, long admissionBudgetBytes,
            long heapUsedBytes, long maxHeapBytes, int loadedMaps, String message) {
        if (listener != null) {
            listener.onProgress(generatedMaps, totalMaps, reservedBytes,
                    admissionBudgetBytes, heapUsedBytes, maxHeapBytes, loadedMaps, message);
        }
    }

    private static final class SuiteGeneratedMapResult {
        private final SuiteMapWork work;
        private final Maze.GeneratedExperimentMap map;
        private final long elapsedNs;

        private SuiteGeneratedMapResult(
                SuiteMapWork work, Maze.GeneratedExperimentMap map, long elapsedNs) {
            this.work = work;
            this.map = map;
            this.elapsedNs = elapsedNs;
        }
    }

    /** Receives throttled progress from solver workers while they are active. */
    public interface SolveProgressListener {
        /**
         * Reports the current state of one solver.
         *
         * @param progress current worker state
         */
        void onProgress(SolveProgress progress);
    }

    /** Immutable diagnostic snapshot for one active solver worker. */
    public static final class SolveProgress {
        private final String configurationId;
        private final int iteration;
        private final MazeSolver.Algorithm algorithm;
        private final int nodesExplored;
        private final long pathCost;
        private final long elapsedNs;
        private final boolean finished;

        private SolveProgress(
                String configurationId, int iteration, MazeSolver.Algorithm algorithm,
                int nodesExplored, long pathCost, long elapsedNs, boolean finished) {
            this.configurationId = configurationId;
            this.iteration = iteration;
            this.algorithm = algorithm;
            this.nodesExplored = nodesExplored;
            this.pathCost = pathCost;
            this.elapsedNs = elapsedNs;
            this.finished = finished;
        }

        /** Gets the test-row configuration identifier.
         * @return test-row configuration identifier
         */
        public String getConfigurationId() { return configurationId; }
        /** Gets the one-based map iteration.
         * @return one-based map iteration
         */
        public int getIteration() { return iteration; }
        /** Gets the algorithm being measured.
         * @return algorithm being measured
         */
        public MazeSolver.Algorithm getAlgorithm() { return algorithm; }
        /** Gets the finalized nodes explored so far.
         * @return finalized nodes explored so far
         */
        public int getNodesExplored() { return nodesExplored; }
        /** Gets the current path cost.
         * @return current path cost, or {@code -1} when unavailable
         */
        public long getPathCost() { return pathCost; }
        /** Gets elapsed solver time.
         * @return elapsed solver time in nanoseconds
         */
        public long getElapsedNs() { return elapsedNs; }
        /** Indicates whether this snapshot represents the completed solve.
         * @return true when the solve is complete
         */
        public boolean isFinished() { return finished; }
    }

    /** Allows a caller to stop a long-running experiment. */
    public interface CancellationToken {
        /**
         * Indicates whether execution should stop before the next algorithm.
         *
         * @return true when execution should be cancelled
         */
        boolean isCancelled();
    }

    /** Adds cooperative pausing to cancellation for interactive runs. */
    public interface ExecutionControl extends CancellationToken {
        /**
         * Indicates whether new work should wait for a resume request.
         *
         * @return true while execution is paused
         */
        boolean isPaused();

        /**
         * Waits until execution is resumed or cancellation is requested.
         */
        void awaitIfPaused();
    }

    /** Token that never cancels execution. */
    public static final CancellationToken NEVER_CANCEL = () -> false;

    private static void awaitIfPaused(CancellationToken token) {
        if (token instanceof ExecutionControl) {
            ((ExecutionControl) token).awaitIfPaused();
        }
    }

    private ExperimentRunner() {
    }

    /**
     * Runs a complete suite.
     *
     * @param suite validated suite to execute
     * @return immutable results in row, iteration, and algorithm order
     * @throws IllegalArgumentException if the suite is null or invalid
     */
    public static List<ExperimentResult> run(TestSuite suite) {
        return run(suite, null, NEVER_CANCEL, null, null);
    }

    /**
     * Runs a suite with progress and cancellation support.
     *
     * <p>When cancellation is requested, completed results are returned and no
     * partially executed solver is converted into a result.
     *
     * @param suite validated suite to execute
     * @param listener optional progress listener
     * @param cancellation optional cancellation token
     * @return immutable completed results
     * @throws IllegalArgumentException if the suite is null or invalid
     */
    public static List<ExperimentResult> run(
            TestSuite suite, ProgressListener listener, CancellationToken cancellation) {
        return run(suite, listener, cancellation, null, null);
    }

    /**
     * Runs a suite with completed-result and throttled solver diagnostics.
     *
     * @param suite validated suite to execute
     * @param listener optional completed-result listener
     * @param cancellation optional cancellation token
     * @param solveProgress optional active-solver listener
     * @return immutable completed results
     */
    public static List<ExperimentResult> run(
            TestSuite suite, ProgressListener listener, CancellationToken cancellation,
            SolveProgressListener solveProgress) {
        return run(suite, listener, cancellation, solveProgress, null);
    }

    /**
     * Runs a suite with solver and generated-map progress diagnostics.
     *
     * @param suite validated suite to execute
     * @param listener optional completed-result listener
     * @param cancellation optional cancellation token
     * @param solveProgress optional active-solver listener
     * @param mapProgress optional generated-map listener
     * @return immutable completed results
     */
    public static List<ExperimentResult> run(
            TestSuite suite, ProgressListener listener, CancellationToken cancellation,
            SolveProgressListener solveProgress, MapProgressListener mapProgress) {
        if (suite == null) {
            throw new IllegalArgumentException("Experiment suite cannot be null.");
        }
        suite.validate();
        CancellationToken token = cancellation == null ? NEVER_CANCEL : cancellation;
        if (!warmUpJvm(suite, token)) {
            return Collections.emptyList();
        }
        int totalRuns = countRuns(suite);
        int totalMaps = countMaps(suite);
        AtomicInteger completedRuns = new AtomicInteger();
        AtomicInteger completedMaps = new AtomicInteger();
        List<ExperimentResult> results = new ArrayList<>();
        int parallelWorkers = getParallelWorkerCount();
        ExecutorService fixedExecutor = suite.getExecutionMode() == TestSuite.ExecutionMode.FIXED_PARALLEL
                ? newPriorityExecutor(parallelWorkers) : null;
        activeWorkerCount = suite.getExecutionMode() == TestSuite.ExecutionMode.SEQUENTIAL
                ? 1 : fixedExecutor == null ? 0 : parallelWorkers;
        activeMapWorkerCount.set(0);
        activeSolverWorkerCount.set(0);
        peakMapWorkerCount.set(0);
        peakSolverWorkerCount.set(0);
        lastAdaptiveCpuLoad = -1.0;
        System.out.printf(
                "[ExperimentRunner] mode=%s, processors=%d, fixedWorkers=%d%n",
                suite.getExecutionMode(), Runtime.getRuntime().availableProcessors(),
                fixedExecutor == null ? 0 : parallelWorkers);
        try {
            if (fixedExecutor == null
                    && suite.getExecutionMode() == TestSuite.ExecutionMode.SEQUENTIAL) {
                for (TestSuite.TestCase testCase : suite.getTestCases()) {
                    long testCaseSeed = resolveTestCaseSeed(testCase);
                    for (int iteration = 1; iteration <= testCase.getIterations(); iteration++) {
                        final int currentIteration = iteration;
                        if (token.isCancelled()) {
                            return Collections.unmodifiableList(results);
                        }
                        List<ExperimentResult> mapResults = runMap(
                                suite.getName(), testCase, iteration, testCaseSeed,
                                solveProgress, () -> reportMapProgress(
                                        mapProgress, completedMaps.incrementAndGet(), totalMaps,
                                        estimateMapBytes(testCase),
                                        (long) (getHeapHeadroomBytes()
                                                        * PREFETCH_HEADROOM_FRACTION),
                                                getHeapUsedBytes(), Runtime.getRuntime().maxMemory(),
                                                0, String.format(Locale.ROOT,
                                                        "%-12s | %-32.32s | iteration %3d",
                                                        "GENERATED", testCase.getId(), currentIteration)));
                        for (ExperimentResult result : mapResults) {
                            results.add(result);
                            int completed = completedRuns.incrementAndGet();
                            reportProgress(listener, completed, totalRuns, result);
                        }
                    }
                }
            } else if (!token.isCancelled()) {
                results.addAll(runContinuousParallelSuite(
                        suite, fixedExecutor, parallelWorkers, listener, totalRuns,
                        completedRuns, solveProgress, mapProgress, token));
            }
            return Collections.unmodifiableList(results);
        } finally {
            if (fixedExecutor != null) {
                shutdownExecutor(fixedExecutor, null);
            }
            activeWorkerCount = 0;
            activeMapWorkerCount.set(0);
            activeSolverWorkerCount.set(0);
        }
    }

    private static List<ExperimentResult> runContinuousParallelSuite(
            TestSuite suite, ExecutorService fixedExecutor, int maximumWorkers,
            ProgressListener listener, int totalRuns, AtomicInteger completedRuns,
            SolveProgressListener solveProgress, MapProgressListener mapProgress,
            CancellationToken token) {
        int workers = suite.getExecutionMode() == TestSuite.ExecutionMode.ADAPTIVE_PARALLEL
                ? getAdaptiveWorkerCount(maximumWorkers) : maximumWorkers;
        activeWorkerCount = workers;
        if (suite.getExecutionMode() == TestSuite.ExecutionMode.ADAPTIVE_PARALLEL) {
            lastAdaptiveCpuLoad = -1.0;
        }

        List<SuiteMapWork> workQueue = new ArrayList<>();
        for (TestSuite.TestCase testCase : suite.getTestCases()) {
            long seed = resolveTestCaseSeed(testCase);
            for (int iteration = 1; iteration <= testCase.getIterations(); iteration++) {
                workQueue.add(new SuiteMapWork(
                        workQueue.size(), testCase, seed, iteration, estimateMapBytes(testCase)));
            }
        }

        boolean closeExecutor = fixedExecutor == null;
        ExecutorService executor = closeExecutor
                ? newPriorityExecutor(workers) : fixedExecutor;
        Thread cpuMonitor = null;
        AtomicBoolean monitorCpu = new AtomicBoolean(true);
        if (closeExecutor) {
            cpuMonitor = new Thread(
                    () -> monitorCpuLoad(monitorCpu), "maze-solver-cpu-monitor");
            cpuMonitor.setDaemon(true);
            cpuMonitor.start();
        }

        Semaphore completionSignals = new Semaphore(0);
        PriorityCompletionService<SuiteGeneratedMapResult> mapCompletions =
                new PriorityCompletionService<>(executor, completionSignals);
        PriorityCompletionService<SolverCompletion> solverCompletions =
                new PriorityCompletionService<>(executor, completionSignals);
        Map<SuiteMapWork, SuiteGeneratedMapResult> readyMaps = new LinkedHashMap<>();
        Map<SuiteMapWork, MapState> mapStates = new LinkedHashMap<>();
        Map<SuiteMapWork, List<ExperimentResult>> orderedResults = new LinkedHashMap<>();
        long reservedBytes = 0L;
        int submittedMaps = 0;
        int mapTasksInFlight = 0;
        int solverTasksInFlight = 0;
        int completedSolves = 0;
        int completedMaps = 0;
        int totalSolves = totalRuns;
        long phaseStartNs = System.nanoTime();
        long baselineHeapUsed = getHeapUsedBytes();
        boolean solverPhaseStarted = false;
        try {
            System.out.printf(
                    "[ExperimentRunner] suite-wide scheduling: %d map/solver worker(s), "
                            + "queuedMaps=%d, heapUsed=%.1f MB, heapHeadroom=%.1f GB%n",
                    workers, workQueue.size(), getHeapUsedBytes() / 1048576.0,
                    getHeapHeadroomBytes() / 1073741824.0);
            while (completedSolves < totalSolves && !token.isCancelled()) {
                awaitIfPaused(token);
                long admissionHeadroom = (long) (getHeapHeadroomBytes()
                        * PREFETCH_HEADROOM_FRACTION);
                int mapAdmissionLimit = boundedWorkerQueueLimit(
                        workers, READY_MAP_QUEUE_MULTIPLIER, Integer.MAX_VALUE);
                while (submittedMaps < workQueue.size()
                        && mapTasksInFlight + readyMaps.size() < mapAdmissionLimit
                        && mapTasksInFlight < workers) {
                    SuiteMapWork work = workQueue.get(submittedMaps);
                    if (submittedMaps > 0
                            && effectiveReservedBytes(reservedBytes, baselineHeapUsed)
                                    + work.reservationBytes > admissionHeadroom) {
                        break;
                    }
                    submittedMaps++;
                    mapTasksInFlight++;
                    reservedBytes += work.reservationBytes;
                    mapCompletions.submit(() -> {
                        if (token.isCancelled()) {
                            return null;
                        }
                        awaitIfPaused(token);
                        int activeMaps = activeMapWorkerCount.incrementAndGet();
                        updatePeak(peakMapWorkerCount, activeMaps);
                        long startNs = System.nanoTime();
                        try {
                            Maze.GeneratedExperimentMap map = createMap(work.testCase, work.seed);
                            return new SuiteGeneratedMapResult(
                                    work, map, System.nanoTime() - startNs);
                        } catch (OutOfMemoryError error) {
                            throw memoryFailure(work.testCase, work.iteration, error);
                        } finally {
                            activeMapWorkerCount.decrementAndGet();
                        }
                    }, TaskPriority.MAP);
                }
                if (submittedMaps == 0 && !workQueue.isEmpty()) {
                    SuiteMapWork work = workQueue.get(submittedMaps);
                    if (work.reservationBytes > admissionHeadroom) {
                        throw new IllegalStateException(
                                "Map memory reservation exceeds available heap headroom: row="
                                        + work.testCase.getId()
                                        + ", iteration=" + work.iteration
                                        + ", estimated="
                                        + String.format("%.1f MB", work.reservationBytes / 1048576.0)
                                        + ", available="
                                        + String.format("%.1f MB", admissionHeadroom / 1048576.0));
                    }
                    submittedMaps++;
                    mapTasksInFlight++;
                    reservedBytes += work.reservationBytes;
                    mapCompletions.submit(() -> {
                        int activeMaps = activeMapWorkerCount.incrementAndGet();
                        updatePeak(peakMapWorkerCount, activeMaps);
                        long startNs = System.nanoTime();
                        try {
                            Maze.GeneratedExperimentMap map = createMap(work.testCase, work.seed);
                            return new SuiteGeneratedMapResult(
                                    work, map, System.nanoTime() - startNs);
                        } finally {
                            activeMapWorkerCount.decrementAndGet();
                        }
                    }, TaskPriority.MAP);
                }

                /*
                 * Ready maps must not wait behind a producer timeout. Only
                 * wait when there is no ready map and map generation is the
                 * only work that can make progress.
                 */
                if (readyMaps.isEmpty() && mapTasksInFlight > 0) {
                    completionSignals.acquire();
                }
                // Several workers can finish between coordinator wake-ups.
                // Drain the available batch before deciding whether to wait
                // again so completion signaling does not serialize scheduling.
                Future<SuiteGeneratedMapResult> mapFuture;
                while ((mapFuture = mapCompletions.poll()) != null) {
                    SuiteGeneratedMapResult generated = awaitSuiteMap(mapFuture);
                    if (generated != null && generated.map != null) {
                        mapTasksInFlight--;
                        readyMaps.put(generated.work, generated);
                        reportMapProgress(mapProgress, ++completedMaps, workQueue.size(),
                                reservedBytes,
                                (long) (getHeapHeadroomBytes()
                                        * PREFETCH_HEADROOM_FRACTION),
                                getHeapUsedBytes(), Runtime.getRuntime().maxMemory(),
                                readyMaps.size() + 1,
                                String.format(Locale.ROOT,
                                        "%-12s | %-32.32s | iteration %3d",
                                        "GENERATED", generated.work.testCase.getId(),
                                        generated.work.iteration));
                    }
                }

                long currentHeadroom = (long) (getHeapHeadroomBytes()
                        * PREFETCH_HEADROOM_FRACTION);
                boolean mapFrontierComplete = submittedMaps >= workQueue.size()
                                || (submittedMaps < workQueue.size()
                                        && effectiveReservedBytes(reservedBytes, baselineHeapUsed)
                                                + workQueue.get(submittedMaps).reservationBytes
                                                > currentHeadroom);
                int solverQueueLimit = boundedWorkerQueueLimit(
                        workers, SOLVER_QUEUE_MULTIPLIER, Integer.MAX_VALUE);
                if (!readyMaps.isEmpty()
                        && (mapFrontierComplete
                                || readyMaps.size() >= mapAdmissionLimit)
                        && solverTasksInFlight < solverQueueLimit) {
                    if (!solverPhaseStarted) {
                        solverPhaseStarted = true;
                        System.out.printf(
                                "[ExperimentRunner] map memory frontier complete: admitted=%d/%d, "
                                        + "reserved=%.1f MB, phaseTime=%.3f ms; starting solves%n",
                                submittedMaps, workQueue.size(), reservedBytes / 1048576.0,
                                (System.nanoTime() - phaseStartNs) / 1_000_000.0);
                    }
                    // Dispatch the whole admitted frontier without returning to
                    // the coordinator between individual maps.
                    while (!readyMaps.isEmpty()
                            && solverTasksInFlight < solverQueueLimit) {
                        SuiteGeneratedMapResult generated =
                                readyMaps.values().iterator().next();
                        int algorithmCount = generated.work.testCase.getAlgorithms().size();
                        if (solverTasksInFlight > 0
                                && solverTasksInFlight + algorithmCount > solverQueueLimit) {
                            break;
                        }
                        MapState state = new MapState();
                        mapStates.put(generated.work, state);
                        for (MazeSolver.Algorithm algorithm
                                : generated.work.testCase.getAlgorithms()) {
                            state.remainingSolves++;
                            solverTasksInFlight++;
                            solverCompletions.submit(() -> {
                                awaitIfPaused(token);
                                if (token.isCancelled()) {
                                    return null;
                                }
                                int activeSolvers = activeSolverWorkerCount.incrementAndGet();
                                updatePeak(peakSolverWorkerCount, activeSolvers);
                                try {
                                    return new SolverCompletion(
                                            generated.work, generated.work.iteration, algorithm,
                                            timedSolve(
                                                    suite.getName(), generated.work.testCase,
                                                    generated.work.iteration, generated.map,
                                                    algorithm, solveProgress));
                                } finally {
                                    activeSolverWorkerCount.decrementAndGet();
                                }
                            }, TaskPriority.SOLVER);
                        }
                        readyMaps.remove(generated.work);
                        reportMapProgress(mapProgress, completedMaps, workQueue.size(),
                                reservedBytes,
                                (long) (getHeapHeadroomBytes()
                                        * PREFETCH_HEADROOM_FRACTION),
                                getHeapUsedBytes(), Runtime.getRuntime().maxMemory(),
                                readyMaps.size(),
                                String.format(Locale.ROOT,
                                        "%-12s | %-32.32s | iteration %3d",
                                        "DISPATCHED", generated.work.testCase.getId(),
                                        generated.work.iteration));
                    }
                }

                // Process every completion already published by workers. This
                // reduces coordinator wake-ups without changing result order.
                Future<SolverCompletion> solverFuture;
                while ((solverFuture = solverCompletions.poll()) != null) {
                    solverTasksInFlight--;
                    SolverCompletion completion = awaitSolver(
                            solverFuture, workQueue.get(0).testCase, 0);
                    SuiteMapWork work = completion.work;
                    if (work != null) {
                        MapState state = mapStates.get(work);
                        state.remainingSolves--;
                        orderedResults.computeIfAbsent(work, ignored -> new ArrayList<>())
                                .add(completion.result);
                        int completed = completedRuns.incrementAndGet();
                        reportProgress(listener, completed, totalRuns, completion.result);
                        completedSolves++;
                        if (state.remainingSolves == 0) {
                            mapStates.remove(work);
                            reservedBytes -= work.reservationBytes;
                            reportMapProgress(mapProgress, completedMaps, workQueue.size(),
                                    reservedBytes,
                                    (long) (getHeapHeadroomBytes()
                                            * PREFETCH_HEADROOM_FRACTION),
                                    getHeapUsedBytes(), Runtime.getRuntime().maxMemory(),
                                    readyMaps.size(),
                                    String.format(Locale.ROOT,
                                            "%-12s | %-32.32s | iteration %3d",
                                            "SOLVED", work.testCase.getId(), work.iteration));
                        }
                    }
                }
            }
            List<ExperimentResult> ordered = new ArrayList<>();
            for (SuiteMapWork work : workQueue) {
                List<ExperimentResult> rowResults = orderedResults.get(work);
                if (rowResults == null) {
                    continue;
                }
                for (MazeSolver.Algorithm algorithm : work.testCase.getAlgorithms()) {
                    for (ExperimentResult result : rowResults) {
                        if (result.getAlgorithm() == algorithm) {
                            ordered.add(result);
                            break;
                        }
                    }
                }
            }
            return ordered;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Parallel experiment execution was interrupted.", exception);
        } finally {
            readyMaps.clear();
            mapStates.clear();
            orderedResults.clear();
            mapCompletions.clear();
            solverCompletions.clear();
            completionSignals.drainPermits();
            if (closeExecutor) {
                monitorCpu.set(false);
                shutdownExecutor(executor, cpuMonitor);
            }
        }
    }

    private static SuiteGeneratedMapResult awaitSuiteMap(
            Future<SuiteGeneratedMapResult> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Parallel suite map generation was interrupted.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("Parallel suite map generation failed.", cause);
        }
    }

    private static ExperimentResult timedSolve(
            String suiteName, TestSuite.TestCase testCase, int iteration,
            Maze.GeneratedExperimentMap map, MazeSolver.Algorithm algorithm,
            SolveProgressListener solveProgress) {
        return solve(suiteName, testCase, iteration, map, algorithm, solveProgress);
    }

    private static SolverCompletion awaitSolver(
            Future<SolverCompletion> future, TestSuite.TestCase testCase, int iteration) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Parallel solver execution was interrupted.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof OutOfMemoryError) {
                throw memoryFailure(testCase, iteration, (OutOfMemoryError) cause);
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("Parallel solver execution failed.", cause);
        }
    }

    private static final class MapState {
        private int remainingSolves;
    }

    private static final class SolverCompletion {
        private final SuiteMapWork work;
        private final int iteration;
        private final MazeSolver.Algorithm algorithm;
        private final ExperimentResult result;

        private SolverCompletion(
                int iteration, MazeSolver.Algorithm algorithm, ExperimentResult result) {
            this(null, iteration, algorithm, result);
        }

        private SolverCompletion(
                SuiteMapWork work, int iteration,
                MazeSolver.Algorithm algorithm, ExperimentResult result) {
            this.work = work;
            this.iteration = iteration;
            this.algorithm = algorithm;
            this.result = result;
        }
    }

    private static void reportProgress(
            ProgressListener listener, int completedRuns, int totalRuns, ExperimentResult result) {
        if (listener != null) {
            listener.onProgress(completedRuns, totalRuns, result);
        }
    }

    private static List<ExperimentResult> runMap(
            String suiteName, TestSuite.TestCase testCase, int iteration, long testCaseSeed,
            SolveProgressListener progressListener, Runnable mapGenerated) {
        Maze.GeneratedExperimentMap generated;
        int activeMaps = activeMapWorkerCount.incrementAndGet();
        updatePeak(peakMapWorkerCount, activeMaps);
        try {
            generated = createMap(testCase, testCaseSeed);
        } catch (OutOfMemoryError error) {
            throw memoryFailure(testCase, iteration, error);
        } finally {
            activeMapWorkerCount.decrementAndGet();
        }
        if (mapGenerated != null) {
            mapGenerated.run();
        }
        List<ExperimentResult> results = new ArrayList<>();
        for (MazeSolver.Algorithm algorithm : testCase.getAlgorithms()) {
            int activeSolvers = activeSolverWorkerCount.incrementAndGet();
            updatePeak(peakSolverWorkerCount, activeSolvers);
            try {
                results.add(solve(
                        suiteName, testCase, iteration, generated, algorithm, progressListener));
            } finally {
                activeSolverWorkerCount.decrementAndGet();
            }
        }
        return results;
    }

    private static void shutdownExecutor(ExecutorService executor, Thread cpuMonitor) {
        if (cpuMonitor != null) {
            cpuMonitor.interrupt();
            try {
                cpuMonitor.join(EXECUTOR_SHUTDOWN_TIMEOUT_MS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(
                    EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                System.err.println("[ExperimentRunner] worker executor did not stop within "
                        + EXECUTOR_SHUTDOWN_TIMEOUT_MS + " ms.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService newPriorityExecutor(int workers) {
        return new ThreadPoolExecutor(
                workers, workers, 0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<Runnable>());
    }

    private enum TaskPriority {
        MAP(0),
        SOLVER(1);

        private final int weight;

        TaskPriority(int weight) {
            this.weight = weight;
        }
    }

    private static final class PriorityCompletionService<V> {
        private final ExecutorService executor;
        private final Semaphore completionSignals;
        private final BlockingQueue<Future<V>> completions =
                new java.util.concurrent.LinkedBlockingQueue<>();

        private PriorityCompletionService(
                ExecutorService executor, Semaphore completionSignals) {
            this.executor = executor;
            this.completionSignals = completionSignals;
        }

        private Future<V> submit(Callable<V> callable, TaskPriority priority) {
            PriorityFutureTask<V> task = new PriorityFutureTask<>(
                    callable, priority, completions, completionSignals);
            executor.execute(task);
            return task;
        }

        private Future<V> poll() {
            return completions.poll();
        }

        private void clear() {
            completions.clear();
        }
    }

    private static final class PriorityFutureTask<V> extends FutureTask<V>
            implements Comparable<PriorityFutureTask<?>> {
        private static final AtomicLong SEQUENCE = new AtomicLong();
        private final TaskPriority priority;
        private final long sequence;
        private final BlockingQueue<Future<V>> completions;
        private final Semaphore completionSignals;

        private PriorityFutureTask(
                Callable<V> callable, TaskPriority priority,
                BlockingQueue<Future<V>> completions,
                Semaphore completionSignals) {
            super(callable);
            this.priority = priority;
            this.sequence = SEQUENCE.getAndIncrement();
            this.completions = completions;
            this.completionSignals = completionSignals;
        }

        @Override
        protected void done() {
            completions.add(this);
            completionSignals.release();
        }

        @Override
        public int compareTo(PriorityFutureTask<?> other) {
            int priorityComparison = Integer.compare(priority.weight, other.priority.weight);
            return priorityComparison != 0
                    ? priorityComparison
                    : Long.compare(sequence, other.sequence);
        }
    }

    private static int getMapGenerationWorkerCount(int computeWorkers, int prefetchedMaps) {
        int preferred = (int) Math.ceil(computeWorkers * MAP_WORKER_FRACTION);
        return Math.max(1, Math.min(computeWorkers - 1, Math.min(preferred, prefetchedMaps)));
    }

    private static int getMapGenerationWorkerCount(int computeWorkers) {
        int preferred = (int) Math.ceil(computeWorkers * MAP_WORKER_FRACTION);
        return Math.max(1, Math.min(computeWorkers - 1, preferred));
    }

    private static int boundedWorkerQueueLimit(
            int workers, int multiplier, int absoluteMaximum) {
        long requested = (long) workers * multiplier;
        return Math.max(workers, (int) Math.min(absoluteMaximum, requested));
    }

    private static long estimateMapBytes(TestSuite.TestCase testCase) {
        long cells = (long) testCase.getRows() * testCase.getColumns();
        /*
         * A reservation represents one retained map plus one active solver
         * state. Queued solver tasks do not allocate MazeSolver state until
         * they execute, so charging every selected algorithm here prevented
         * the producer from using the available heap.
         */
        long solverTables = cells * (Long.BYTES + Integer.BYTES);
        long mapAndActiveSolver = cells * ESTIMATED_BYTES_PER_MAP_CELL
                + solverTables + SOLVER_OVERHEAD_BYTES;
        return Math.max(SOLVER_OVERHEAD_BYTES,
                (long) Math.ceil(mapAndActiveSolver * MEMORY_SAFETY_FACTOR));
    }

    /**
     * Gets the configured worker capacity for the currently running suite.
     *
     * @return worker capacity, one for sequential mode, or zero while idle
     */
    public static int getParallelWorkerCount() {
        int processors = Runtime.getRuntime().availableProcessors();
        return processors <= 1 ? 1 : processors - 1;
    }

    /**
     * Selects workers conservatively from available heap and system CPU load.
     *
     * @return adaptive worker count for the next map
     */
    public static int getAdaptiveWorkerCount() {
        return getAdaptiveWorkerCount(getParallelWorkerCount());
    }

    private static int getAdaptiveWorkerCount(int maximumWorkers) {
        int workers = maximumWorkers;
        long headroom = getHeapHeadroomBytes();
        workers = Math.min(workers, (int) Math.max(1, headroom / SOLVER_OVERHEAD_BYTES));
        double cpuLoad = lastAdaptiveCpuLoad >= 0.0
                ? lastAdaptiveCpuLoad : currentCpuLoad();
        if (cpuLoad >= CPU_LOAD_LIMIT) {
            workers = Math.max(1, workers - 1);
        }
        return workers;
    }

    /**
     * Returns heap that can still be committed without exceeding {@code -Xmx}.
     *
     * <p>{@link Runtime#freeMemory()} is unused space inside the currently
     * committed heap, while the difference between maximum and total memory is
     * space the JVM may still commit. Both are available to the scheduler.
     *
     * @return approximate heap headroom in bytes
     */
    private static long getHeapHeadroomBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
    }

    private static long getHeapUsedBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /**
     * Returns the portion of the logical reservation that is not already
     * represented by observed heap growth. Heap headroom already excludes
     * observed allocations, so adding the full observed value would
     * double-count retained map and solver objects.
     */
    private static long effectiveReservedBytes(long reservedBytes, long baselineHeapUsed) {
        long observedMapUsage = Math.max(0L, getHeapUsedBytes() - baselineHeapUsed);
        return Math.max(0L, reservedBytes - observedMapUsage);
    }

    private static void monitorCpuLoad(AtomicBoolean running) {
        double peakLoad = -1.0;
        while (running.get()) {
            peakLoad = Math.max(peakLoad, currentCpuLoad());
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        lastAdaptiveCpuLoad = peakLoad;
    }

    private static double currentCpuLoad() {
        return OS_BEAN == null ? -1.0 : OS_BEAN.getCpuLoad();
    }

    /**
     * Gets the worker capacity currently assigned to the active suite.
     *
     * @return worker capacity, one for sequential mode, or zero while idle
     */
    public static int getActiveWorkerCount() {
        return activeWorkerCount;
    }

    /**
     * Gets the currently executing map-generation worker count.
     *
     * @return currently executing map-generation workers
     */
    public static int getActiveMapWorkerCount() {
        return activeMapWorkerCount.get();
    }

    /**
     * Gets the currently executing solver worker count.
     *
     * @return currently executing solver workers
     */
    public static int getActiveSolverWorkerCount() {
        return activeSolverWorkerCount.get();
    }

    /**
     * Returns and resets the maximum map-worker count observed since the
     * previous call.
     *
     * @return interval peak map-worker count
     */
    public static int getIntervalPeakMapWorkerCount() {
        synchronized (workerPeakLock) {
            int peak = peakMapWorkerCount.get();
            peakMapWorkerCount.set(activeMapWorkerCount.get());
            return peak;
        }
    }

    /**
     * Returns and resets the maximum solver-worker count observed since the
     * previous call.
     *
     * @return interval peak solver-worker count
     */
    public static int getIntervalPeakSolverWorkerCount() {
        synchronized (workerPeakLock) {
            int peak = peakSolverWorkerCount.get();
            peakSolverWorkerCount.set(activeSolverWorkerCount.get());
            return peak;
        }
    }

    private static void updatePeak(AtomicInteger peak, int active) {
        synchronized (workerPeakLock) {
            if (active > peak.get()) {
                peak.set(active);
            }
        }
    }

    private static IllegalStateException memoryFailure(
            TestSuite.TestCase testCase, int iteration, OutOfMemoryError error) {
        return new IllegalStateException(
                "Java heap space while processing test case '" + testCase.getId()
                        + "', iteration " + iteration
                        + ". Reduce map size or run the suite in batches.", error);
    }

    /**
     * Exercises the solver before measured work so class loading and JIT
     * compilation are less likely to skew the first reported result.
     *
     * @param suite suite whose selected algorithms should be warmed up
     * @param token cancellation token
     * @return false when cancellation was requested during warm-up
     */
    private static boolean warmUpJvm(TestSuite suite, CancellationToken token) {
        Set<MazeSolver.Algorithm> algorithms =
                EnumSet.noneOf(MazeSolver.Algorithm.class);
        for (TestSuite.TestCase testCase : suite.getTestCases()) {
            algorithms.addAll(testCase.getAlgorithms());
        }
        if (algorithms.isEmpty()) {
            return true;
        }

        TestSuite.TestCase warmupCase = new TestSuite.TestCase("jvm-warmup");
        warmupCase.setRows(20);
        warmupCase.setColumns(20);
        warmupCase.setBlockedPercent(25.0);
        warmupCase.setReachabilityMode(TestSuite.ReachabilityMode.GUARANTEED_SOLVABLE);
        warmupCase.setCostMode(TestSuite.CostMode.RANDOM);
        warmupCase.setMinimumCost(Maze.MIN_MOVEMENT_COST);
        warmupCase.setMaximumCost(5);
        warmupCase.setEndpointMode(TestSuite.EndpointMode.RANDOM);
        long warmupSeed = 0x4D415A455F574152L;
        warmupCase.setSeed(warmupSeed);
        Maze.GeneratedExperimentMap map = Maze.generateExperimentMap(warmupCase, warmupSeed);

        for (int round = 0; round < JVM_WARMUP_ROUNDS; round++) {
            for (MazeSolver.Algorithm algorithm : algorithms) {
                if (token.isCancelled()) {
                    return false;
                }
                warmupSolve(map.getMaze(), algorithm);
            }
        }
        return true;
    }

    private static void warmupSolve(Maze maze, MazeSolver.Algorithm algorithm) {
        MazeSolver solver = new MazeSolver();
        solver.setAlg(algorithm.name());
        solver.setMaze(maze);
        while (!solver.isFinished()) {
            solver.updateSolve();
        }
    }

    private static Maze.GeneratedExperimentMap createMap(
            TestSuite.TestCase testCase, long effectiveSeed) {
        if (testCase.getMapSource() != TestSuite.MapSource.SAVED_MAP) {
            return Maze.generateExperimentMap(testCase, effectiveSeed);
        }
        try {
            return Maze.GeneratedExperimentMap.fromSavedMap(
                    Maze.importFromText(testCase.getSavedMapText()), effectiveSeed);
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unable to load saved map for test case '" + testCase.getId() + "'.", ex);
        }
    }

    /**
     * Formats a compact terminal summary for completed results.
     *
     * @param results results to summarize
     * @return tabular summary text
     */
    public static String formatSummary(List<ExperimentResult> results) {
        if (results == null) {
            throw new IllegalArgumentException("Results cannot be null.");
        }
        StringBuilder summary = new StringBuilder();
        summary.append("Configuration,Iteration,Algorithm,Solved,Path Cost,")
                .append("Path Length,Nodes Explored,Runtime (ms),Map Source,Seed")
                .append(System.lineSeparator());
        for (ExperimentResult result : results) {
            summary.append(result.getConfigurationId()).append(',')
                    .append(result.getIteration()).append(',')
                    .append(result.getAlgorithm()).append(',')
                    .append(result.isSolved()).append(',')
                    .append(result.getPathCost()).append(',')
                    .append(result.getPathLength()).append(',')
                    .append(result.getNodesExplored()).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", result.getRuntimeMs())).append(',')
                    .append(result.getMapSource()).append(',')
                    .append(result.getSeedLabel())
                    .append(System.lineSeparator());
        }
        return summary.toString();
    }

    /**
     * Exports every map used by a suite, including one file per generated
     * iteration and one file per saved-map row.
     *
     * @param suite validated suite
     * @param directory parent directory in which the suite map folder is created
     * @throws IOException if a map cannot be written
     */
    public static void exportMaps(TestSuite suite, Path directory) throws IOException {
        if (suite == null || directory == null) {
            throw new IllegalArgumentException("Suite and map export directory are required.");
        }
        suite.validate();
        Path suiteDirectory = directory.resolve(sanitizeFileName(suite.getName()) + "-maps");
        Files.createDirectories(suiteDirectory);
        for (TestSuite.TestCase testCase : suite.getTestCases()) {
            Map<String, List<Integer>> uniqueMaps = new LinkedHashMap<>();
            long testCaseSeed = resolveTestCaseSeed(testCase);
            for (int iteration = 1; iteration <= testCase.getIterations(); iteration++) {
                Maze.GeneratedExperimentMap map = createMap(testCase, testCaseSeed);
                uniqueMaps.computeIfAbsent(map.getMaze().exportToText(),
                        ignored -> new ArrayList<>()).add(iteration);
            }
            int mapIndex = 1;
            for (Map.Entry<String, List<Integer>> entry : uniqueMaps.entrySet()) {
                String fileName = sanitizeFileName(testCase.getId())
                        + "-iterations-" + formatIterationGroups(entry.getValue());
                if (uniqueMaps.size() > 1) {
                    fileName += "-map-" + mapIndex;
                }
                Files.writeString(suiteDirectory.resolve(fileName + ".msmap"), entry.getKey());
                mapIndex++;
            }
        }
    }

    private static String formatIterationGroups(List<Integer> iterations) {
        StringBuilder groups = new StringBuilder();
        int start = iterations.get(0);
        int previous = start;
        for (int index = 1; index < iterations.size(); index++) {
            int current = iterations.get(index);
            if (current != previous + 1) {
                appendIterationGroup(groups, start, previous);
                start = current;
            }
            previous = current;
        }
        appendIterationGroup(groups, start, previous);
        return groups.toString();
    }

    private static void appendIterationGroup(StringBuilder output, int start, int end) {
        if (output.length() > 0) {
            output.append('_');
        }
        output.append(start);
        if (start != end) {
            output.append('-').append(end);
        }
    }

    private static ExperimentResult solve(
            String suiteName, TestSuite.TestCase testCase, int iteration,
            Maze.GeneratedExperimentMap generated, MazeSolver.Algorithm algorithm,
            SolveProgressListener progressListener) {
        MazeSolver solver = new MazeSolver();
        solver.setAlg(algorithm.name());
        solver.setMaze(generated.getMaze());
        long startNs = System.nanoTime();
        reportSolveProgress(
                progressListener, testCase, iteration, algorithm, solver, startNs, false);
        long lastProgressNs = startNs;
        while (!solver.isFinished()) {
            solver.updateSolve();
            long now = System.nanoTime();
            if (now - lastProgressNs >= LIVE_PROGRESS_INTERVAL_NS) {
                reportSolveProgress(
                        progressListener, testCase, iteration, algorithm, solver, startNs, false);
                lastProgressNs = now;
            }
        }
        reportSolveProgress(
                progressListener, testCase, iteration, algorithm, solver, startNs, true);

        boolean solved = solver.isSolved();
        int pathLength = solved ? Math.max(0, solver.getFinalPath().size() - 1) : -1;
        return new ExperimentResult(
                suiteName,
                testCase.getId(),
                iteration,
                generated.getEffectiveSeed(),
                testCase.getMapSource(),
                testCase.getSavedMapName(),
                generated.getMaze().getLength(),
                generated.getMaze().getWidth(),
                testCase.getBlockedPercent(),
                generated.getActualBlockedPercent(),
                generated.getBlockedCellCount(),
                testCase.getReachabilityMode(),
                generated.isSolvable(),
                testCase.getCostMode(),
                testCase.getMinimumCost(),
                testCase.getMaximumCost(),
                generated.getStartRow(),
                generated.getStartColumn(),
                generated.getGoalRow(),
                generated.getGoalColumn(),
                testCase.getEndpointMode() == TestSuite.EndpointMode.APPROXIMATE_DISTANCE
                        ? testCase.getTargetManhattanDistance() : -1,
                generated.getActualManhattanDistance(),
                algorithm,
                solved,
                pathLength,
                solved ? solver.getPathCost() : -1,
                solver.getVisitedCount(),
                solver.getSolveTimeNs());
    }

    private static void reportSolveProgress(
            SolveProgressListener listener, TestSuite.TestCase testCase, int iteration,
            MazeSolver.Algorithm algorithm, MazeSolver solver, long startNs, boolean finished) {
        if (listener != null) {
            listener.onProgress(new SolveProgress(
                    testCase.getId(), iteration, algorithm, solver.getVisitedCount(),
                    solver.isFinished() ? solver.getPathCost() : solver.getCurrentPathCost(),
                    System.nanoTime() - startNs, finished));
        }
    }

    private static String sanitizeFileName(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static int countRuns(TestSuite suite) {
        long count = 0;
        for (TestSuite.TestCase testCase : suite.getTestCases()) {
            count += (long) testCase.getIterations() * testCase.getAlgorithms().size();
        }
        if (count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Experiment contains too many algorithm runs.");
        }
        return (int) count;
    }

    private static int countMaps(TestSuite suite) {
        long count = 0;
        for (TestSuite.TestCase testCase : suite.getTestCases()) {
            count += testCase.getIterations();
        }
        if (count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Experiment contains too many maps.");
        }
        return (int) count;
    }

    /**
     * Resolves the seed used for every iteration of a test case.
     *
     * <p>The seed belongs to the test-case execution, not to an iteration.
     * This keeps manual seeds exact and resolves automatic seeds only once,
     * making them stable across all iterations.
     *
     * @param testCase test case whose map is being generated
     * @return effective map seed, or zero for an embedded saved map
     */
    private static long resolveTestCaseSeed(TestSuite.TestCase testCase) {
        if (testCase.getMapSource() == TestSuite.MapSource.SAVED_MAP) {
            return 0L;
        }
        return testCase.isAutomaticSeed() ? SEED_SOURCE.nextLong() : testCase.getSeed();
    }
}
