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

import maze.map.Maze;
import maze.search.MazeSolver;

/**
 * Executes configured experiment rows without Swing or rendering overhead.
 *
 * <p>One map is generated per row iteration and reused by every selected
 * algorithm. Solver timing therefore excludes map generation, result
 * construction, callbacks, and console output.
 */
public final class ExperimentRunner {
    private static final int JVM_WARMUP_ROUNDS = 3;
    private static final SecureRandom SEED_SOURCE = new SecureRandom();

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

    /** Allows a caller to stop a long-running experiment. */
    public interface CancellationToken {
        /**
         * Indicates whether execution should stop before the next algorithm.
         *
         * @return true when execution should be cancelled
         */
        boolean isCancelled();
    }

    /** Token that never cancels execution. */
    public static final CancellationToken NEVER_CANCEL = () -> false;

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
        return run(suite, null, NEVER_CANCEL);
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
        if (suite == null) {
            throw new IllegalArgumentException("Experiment suite cannot be null.");
        }
        suite.validate();
        CancellationToken token = cancellation == null ? NEVER_CANCEL : cancellation;
        if (!warmUpJvm(suite, token)) {
            return Collections.emptyList();
        }
        int totalRuns = countRuns(suite);
        int completedRuns = 0;
        List<ExperimentResult> results = new ArrayList<>();

        for (TestSuite.TestCase testCase : suite.getTestCases()) {
            long testCaseSeed = resolveTestCaseSeed(testCase);
            for (int iteration = 1; iteration <= testCase.getIterations(); iteration++) {
                if (token.isCancelled()) {
                    return Collections.unmodifiableList(results);
                }

                Maze.GeneratedExperimentMap generated = createMap(testCase, testCaseSeed);

                for (MazeSolver.Algorithm algorithm : testCase.getAlgorithms()) {
                    if (token.isCancelled()) {
                        return Collections.unmodifiableList(results);
                    }

                    ExperimentResult result = solve(
                            suite.getName(), testCase, iteration, generated, algorithm);
                    results.add(result);
                    completedRuns++;
                    if (listener != null) {
                        listener.onProgress(completedRuns, totalRuns, result);
                    }
                }
            }
        }
        return Collections.unmodifiableList(results);
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
            Maze.GeneratedExperimentMap generated, MazeSolver.Algorithm algorithm) {
        MazeSolver solver = new MazeSolver();
        solver.setAlg(algorithm.name());
        solver.setMaze(generated.getMaze());
        while (!solver.isFinished()) {
            solver.updateSolve();
        }

        boolean solved = solver.isSolved();
        int pathLength = solved ? Math.max(0, solver.getFinalPath().size() - 1) : -1;
        return new ExperimentResult(
                suiteName,
                testCase.getId(),
                iteration,
                generated.getEffectiveSeed(),
                testCase.getMapSource(),
                testCase.getSavedMapName(),
                generated.getMaze().exportToText(),
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
