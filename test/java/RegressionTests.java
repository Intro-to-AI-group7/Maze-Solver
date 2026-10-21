package test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import experiments.ExperimentResult;
import experiments.ExperimentRunner;
import experiments.TestSuite;
import experiments.TestSuiteIO;
import maze.map.Maze;
import maze.search.MazeSolver;

/**
 * Dependency-free regression checks for the weighted solver and experiment workflow.
 *
 * <p>Compile to {@code build\classes}, then run with
 * {@code java -cp build\classes test.RegressionTests}.
 */
public final class RegressionTests {
    private RegressionTests() {
    }

    /**
     * Runs the dependency-free regression checks.
     *
     * @param args command-line arguments; currently ignored
     * @throws Exception if a temporary fixture or persistence check fails
     */
    public static void main(String[] args) throws Exception {
        testWeightedMapRoundTrip();
        testAllAlgorithmsOnGeneratedMap();
        testSolverStepTimingCanBeDisabled();
        testGeneratedMapConfigurationValidation();
        testReproducibleExperimentRuns();
        testSequentialAndParallelExperimentRunsMatch();
        testSuiteWideParallelQueuePreservesRowOrder();
        testParallelMapGenerationUsesMultipleWorkers();
        testAdaptiveExperimentRunCompletes();
        testSuitePersistenceAndCsvExport();
        testEmbeddedSavedMap();
        testUniqueMapExport();
        testCancellationPreservesCompletedResults();
        System.out.println("All Maze-Solver regression tests passed.");
    }

    private static void testWeightedMapRoundTrip() throws Exception {
        Maze maze = Maze.importFromFile("test/resources/maps/test_weighted_100x100.msmap");
        Path file = Files.createTempFile("maze-solver-weighted-", ".msmap");
        maze.exportToFile(file.toString());
        Maze reloaded = Maze.importFromFile(file.toString());
        require(maze.getLength() == reloaded.getLength(), "Weighted map row count changed.");
        require(maze.getWidth() == reloaded.getWidth(), "Weighted map column count changed.");
        boolean comparedCost = false;
        for (Maze.Dir direction : Maze.Dir.values()) {
            Maze.Node neighbor = maze.getStart().getNeighbor(direction);
            if (neighbor != null) {
                Maze.Node reloadedNeighbor = reloaded.getStart().getNeighbor(direction);
                require(maze.getMoveCost(maze.getStart(), neighbor)
                        == reloaded.getMoveCost(reloaded.getStart(), reloadedNeighbor),
                        "Weighted map edge cost changed.");
                comparedCost = true;
                break;
            }
        }
        require(comparedCost, "Weighted fixture has no adjacent start node.");
        Files.deleteIfExists(file);
    }

    private static void testAllAlgorithmsOnGeneratedMap() {
        TestSuite.TestCase testCase = baseCase("all-algorithms");
        Maze.GeneratedExperimentMap generated = Maze.generateExperimentMap(testCase, 42L);
        for (MazeSolver.Algorithm algorithm : MazeSolver.Algorithm.values()) {
            MazeSolver solver = solve(generated.getMaze(), algorithm);
            require(solver.isSolved(), algorithm + " failed on a guaranteed-solvable map.");
            require(solver.getPathCost() > 0, algorithm + " returned an invalid path cost.");
        }
    }

    private static void testSolverStepTimingCanBeDisabled() {
        TestSuite.TestCase testCase = baseCase("timing-mode");
        Maze maze = Maze.generateExperimentMap(testCase, 123L).getMaze();

        MazeSolver timedSolver = solve(maze, MazeSolver.Algorithm.BFS);
        require(timedSolver.getSolveTimeNs() > 0,
                "Default solver timing did not record search-step time.");

        MazeSolver untimedSolver = new MazeSolver();
        untimedSolver.setAlg(MazeSolver.Algorithm.BFS.name());
        untimedSolver.setMaze(maze);
        untimedSolver.setStepTimingEnabled(false);
        while (!untimedSolver.isFinished()) {
            untimedSolver.updateSolve();
        }
        require(untimedSolver.getSolveTimeNs() == 0,
                "Disabled solver timing recorded per-step time.");
    }

    private static void testGeneratedMapConfigurationValidation() {
        TestSuite.TestCase testCase = baseCase("configuration-validation");
        testCase.setRows(20);
        testCase.setColumns(24);
        testCase.setBlockedPercent(20.0);
        testCase.setEndpointMode(TestSuite.EndpointMode.FIXED);
        testCase.setStartRow(1);
        testCase.setStartColumn(1);
        testCase.setGoalRow(18);
        testCase.setGoalColumn(22);
        testCase.setMinimumCost(3);
        testCase.setMaximumCost(7);
        testCase.setSameCostBothDirections(false);

        Maze.GeneratedExperimentMap generated =
                Maze.generateExperimentMap(testCase, 9876L);
        Maze maze = generated.getMaze();
        int interiorCells = (testCase.getRows() - 2) * (testCase.getColumns() - 2);
        int expectedBlocked = (int) Math.round(
                interiorCells * testCase.getBlockedPercent() / 100.0);

        require(maze.getLength() == testCase.getRows()
                && maze.getWidth() == testCase.getColumns(),
                "Generated map dimensions did not match the test case.");
        require(generated.getBlockedCellCount() == expectedBlocked,
                "Generated map did not honor the blocked-cell percentage.");
        require(generated.getStartRow() == testCase.getStartRow()
                && generated.getStartColumn() == testCase.getStartColumn()
                && generated.getGoalRow() == testCase.getGoalRow()
                && generated.getGoalColumn() == testCase.getGoalColumn(),
                "Generated map did not honor fixed endpoints.");
        require(generated.isSolvable(), "Guaranteed-solvable map was not connected.");
        require(maze.validateNeighborConnections(0).isValid(),
                "Generated map had invalid neighbor connections.");

        boolean foundMinimumCost = false;
        boolean foundMaximumCost = false;
        for (int row = 0; row < maze.getLength(); row++) {
            for (int col = 0; col < maze.getWidth(); col++) {
                Maze.Node node = maze.getNode(row, col);
                if (node == null) {
                    continue;
                }
                for (Maze.Dir direction : Maze.Dir.values()) {
                    int cost = node.getMoveCost(direction);
                    if (cost == testCase.getMinimumCost()) {
                        foundMinimumCost = true;
                    }
                    if (cost == testCase.getMaximumCost()) {
                        foundMaximumCost = true;
                    }
                    require(cost == 0
                            || (cost >= testCase.getMinimumCost()
                            && cost <= testCase.getMaximumCost()),
                            "Generated movement cost was outside the configured range.");
                }
            }
        }
        require(foundMinimumCost && foundMaximumCost,
                "Generated map did not exercise the configured movement-cost range.");
    }

    private static void testReproducibleExperimentRuns() {
        TestSuite suite = new TestSuite("reproducibility");
        TestSuite.TestCase testCase = baseCase("repeatable");
        testCase.setIterations(3);
        testCase.setAlgorithms(EnumSet.of(MazeSolver.Algorithm.UCS, MazeSolver.Algorithm.A_STAR));
        suite.addTestCase(testCase);
        List<ExperimentResult> first = ExperimentRunner.run(suite);
        List<ExperimentResult> second = ExperimentRunner.run(suite);
        require(first.size() == 6 && second.size() == 6, "Unexpected reproducibility result count.");
        for (int index = 0; index < first.size(); index++) {
            ExperimentResult left = first.get(index);
            ExperimentResult right = second.get(index);
            require(left.getEffectiveSeed() == right.getEffectiveSeed()
                    && left.getEffectiveSeed() == testCase.getSeed()
                    && left.getStartRow() == right.getStartRow()
                    && left.getGoalColumn() == right.getGoalColumn()
                    && left.isSolved() == right.isSolved()
                    && left.getPathCost() == right.getPathCost(),
                    "Seeded experiment results were not reproducible.");
        }

        require(first.get(0).getEffectiveSeed() == first.get(2).getEffectiveSeed()
                && first.get(0).getEffectiveSeed() == first.get(4).getEffectiveSeed(),
                "Manual seed changed between iterations.");

        TestSuite automaticSuite = new TestSuite("automatic-seed");
        TestSuite.TestCase automaticCase = baseCase("automatic");
        automaticCase.setAutomaticSeed(true);
        automaticCase.setIterations(3);
        automaticSuite.addTestCase(automaticCase);
        List<ExperimentResult> automaticResults = ExperimentRunner.run(automaticSuite);
        require(automaticResults.get(0).getEffectiveSeed()
                == automaticResults.get(1).getEffectiveSeed()
                && automaticResults.get(1).getEffectiveSeed()
                == automaticResults.get(2).getEffectiveSeed(),
                "Automatic seed changed between iterations.");
    }

    private static void testSequentialAndParallelExperimentRunsMatch() {
        TestSuite sequentialSuite = new TestSuite("execution-mode");
        TestSuite.TestCase sequentialCase = baseCase("same-results");
        sequentialCase.setIterations(2);
        sequentialCase.setAlgorithms(EnumSet.allOf(MazeSolver.Algorithm.class));
        sequentialSuite.addTestCase(sequentialCase);

        TestSuite parallelSuite = new TestSuite("execution-mode");
        TestSuite.TestCase parallelCase = baseCase("same-results");
        parallelCase.setIterations(2);
        parallelCase.setAlgorithms(EnumSet.allOf(MazeSolver.Algorithm.class));
        parallelSuite.setExecutionMode(TestSuite.ExecutionMode.FIXED_PARALLEL);
        parallelSuite.addTestCase(parallelCase);

        List<ExperimentResult> sequential = ExperimentRunner.run(sequentialSuite);
        List<ExperimentResult> parallel = ExperimentRunner.run(parallelSuite);
        require(sequential.size() == parallel.size(),
                "Execution modes returned different result counts.");
        for (int index = 0; index < sequential.size(); index++) {
            ExperimentResult left = sequential.get(index);
            ExperimentResult right = parallel.get(index);
            require(left.getConfigurationId().equals(right.getConfigurationId())
                    && left.getIteration() == right.getIteration()
                    && left.getAlgorithm() == right.getAlgorithm()
                    && left.isSolved() == right.isSolved()
                    && left.getPathCost() == right.getPathCost()
                    && left.getNodesExplored() == right.getNodesExplored(),
                    "Parallel execution changed deterministic solver results.");
        }
    }

    private static void testAdaptiveExperimentRunCompletes() {
        TestSuite suite = new TestSuite("adaptive-execution");
        TestSuite.TestCase testCase = baseCase("adaptive-row");
        testCase.setIterations(2);
        testCase.setAlgorithms(EnumSet.of(
                MazeSolver.Algorithm.UCS, MazeSolver.Algorithm.A_STAR));
        suite.setExecutionMode(TestSuite.ExecutionMode.ADAPTIVE_PARALLEL);
        suite.addTestCase(testCase);

        List<ExperimentResult> results = ExperimentRunner.run(suite);
        require(results.size() == 4, "Adaptive execution returned an unexpected result count.");
        require(results.get(0).getIteration() == 1
                && results.get(2).getIteration() == 2,
                "Adaptive execution changed deterministic iteration ordering.");
        require(ExperimentRunner.getActiveWorkerCount() == 0,
                "Adaptive execution did not reset worker state after completion.");
    }

    private static void testParallelMapGenerationUsesMultipleWorkers() throws Exception {
        TestSuite suite = new TestSuite("parallel-map-workers");
        TestSuite.TestCase testCase = baseCase("parallel-map-row");
        testCase.setRows(220);
        testCase.setColumns(220);
        testCase.setIterations(12);
        testCase.setAlgorithms(EnumSet.of(MazeSolver.Algorithm.GREEDY));
        suite.setExecutionMode(TestSuite.ExecutionMode.FIXED_PARALLEL);
        suite.addTestCase(testCase);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                List<ExperimentResult> results = ExperimentRunner.run(suite);
                require(results.size() == 12,
                        "Parallel map worker test returned an unexpected result count.");
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "regression-parallel-map-workers");
        runner.start();
        int peakMapWorkers = 0;
        while (runner.isAlive()) {
            peakMapWorkers = Math.max(
                    peakMapWorkers, ExperimentRunner.getActiveMapWorkerCount());
            Thread.sleep(1L);
        }
        runner.join();
        if (failure.get() != null) {
            throw new AssertionError(
                    "Parallel map worker test failed.", failure.get());
        }
        require(peakMapWorkers > 1,
                "Parallel scheduler never ran more than one map worker.");
    }

    private static void testSuiteWideParallelQueuePreservesRowOrder() {
        TestSuite suite = new TestSuite("suite-wide-queue");
        TestSuite.TestCase first = baseCase("first-row");
        first.setIterations(2);
        first.setAlgorithms(EnumSet.of(MazeSolver.Algorithm.BFS));
        TestSuite.TestCase second = baseCase("second-row");
        second.setIterations(2);
        second.setAlgorithms(EnumSet.of(MazeSolver.Algorithm.UCS));
        suite.setExecutionMode(TestSuite.ExecutionMode.FIXED_PARALLEL);
        suite.addTestCase(first);
        suite.addTestCase(second);

        List<ExperimentResult> results = ExperimentRunner.run(suite);
        require(results.size() == 4, "Suite-wide queue returned an unexpected result count.");
        require(results.get(0).getConfigurationId().equals("first-row")
                && results.get(1).getConfigurationId().equals("first-row")
                && results.get(2).getConfigurationId().equals("second-row")
                && results.get(3).getConfigurationId().equals("second-row"),
                "Suite-wide queue changed test-case result ordering.");
    }

    private static void testSuitePersistenceAndCsvExport() throws Exception {
        TestSuite suite = new TestSuite("persistence");
        suite.addTestCase(baseCase("saved-row"));
        Path directory = Files.createTempDirectory("maze-solver-regression-");
        Path suiteFile = directory.resolve("suite.mstests");
        Path csvFile = directory.resolve("results.csv");
        TestSuiteIO.save(suite, suiteFile);
        TestSuite loaded = TestSuiteIO.load(suiteFile);
        require(!loaded.getTestCases().get(0).isAutomaticSeed(),
                "Saving a suite did not materialize its automatic seed.");
        List<ExperimentResult> results = ExperimentRunner.run(loaded);
        TestSuiteIO.exportCsv(results, csvFile);
        String csv = Files.readString(csvFile);
        require(csv.startsWith("\"Suite\",\"Configuration\"")
                && csv.contains("\"Algorithm\""), "CSV schema is incomplete.");
    }

    private static void testCancellationPreservesCompletedResults() {
        TestSuite suite = new TestSuite("cancellation");
        TestSuite.TestCase testCase = baseCase("cancel-row");
        testCase.setIterations(20);
        testCase.setAlgorithms(EnumSet.of(MazeSolver.Algorithm.UCS));
        suite.addTestCase(testCase);
        final int[] completed = {0};
        List<ExperimentResult> results = ExperimentRunner.run(suite,
                (count, total, result) -> completed[0] = count,
                () -> completed[0] >= 1);
        require(results.size() == 1, "Cancellation did not retain completed results.");
    }

    private static void testUniqueMapExport() throws Exception {
        String mapText = Files.readString(
                Path.of("test/resources/maps/test_weighted_100x100.msmap"));
        TestSuite suite = new TestSuite("export-suite");
        TestSuite.TestCase testCase = baseCase("saved-row");
        testCase.setSavedMapText(mapText);
        testCase.setSavedMapName("source.msmap");
        testCase.setIterations(3);
        suite.addTestCase(testCase);

        Path parent = Files.createTempDirectory("maze-solver-map-export-");
        ExperimentRunner.exportMaps(suite, parent);
        Path exportDirectory = parent.resolve("export-suite-maps");
        require(Files.isDirectory(exportDirectory), "Suite map export folder was not created.");
        List<Path> files;
        try (java.util.stream.Stream<Path> stream = Files.list(exportDirectory)) {
            files = stream.toList();
        }
        require(files.size() == 1, "Repeated iterations produced duplicate map exports.");
        require(files.get(0).getFileName().toString().contains("iterations-1-3"),
                "Export filename did not preserve the iteration group.");
        require(Files.readString(files.get(0)).equals(mapText),
                "Exported map content changed.");
    }

    private static void testEmbeddedSavedMap() throws Exception {
        String mapText = Files.readString(
                Path.of("test/resources/maps/test_weighted_100x100.msmap"));
        TestSuite suite = new TestSuite("embedded-map");
        TestSuite.TestCase testCase = baseCase("embedded-row");
        testCase.setSavedMapText(mapText);
        testCase.setSavedMapName("test_weighted_100x100.msmap");
        testCase.setIterations(3);
        suite.addTestCase(testCase);
        List<ExperimentResult> results = ExperimentRunner.run(suite);
        require(results.size() == 3 && results.get(0).getRows() == 100
                && results.get(0).getColumns() == 100,
                "Embedded saved map was not executed with its stored dimensions.");
        for (ExperimentResult result : results) {
            require(result.getMapSource() == TestSuite.MapSource.SAVED_MAP
                    && result.getSeedLabel().equals("test_weighted_100x100.msmap")
                    && result.getEffectiveSeed() == 0L,
                    "Saved-map results exposed a generated seed.");
        }

        Path file = Files.createTempFile("maze-solver-embedded-", ".mstests");
        TestSuiteIO.save(suite, file);
        TestSuite loaded = TestSuiteIO.load(file);
        require(loaded.getTestCases().get(0).getMapSource() == TestSuite.MapSource.SAVED_MAP
                && loaded.getTestCases().get(0).getSavedMapText().equals(mapText)
                && loaded.getTestCases().get(0).getSavedMapName()
                        .equals("test_weighted_100x100.msmap"),
                "Embedded map did not survive suite persistence.");
    }

    private static TestSuite.TestCase baseCase(String id) {
        TestSuite.TestCase testCase = new TestSuite.TestCase(id);
        testCase.setRows(12);
        testCase.setColumns(12);
        testCase.setBlockedPercent(20.0);
        testCase.setReachabilityMode(TestSuite.ReachabilityMode.GUARANTEED_SOLVABLE);
        testCase.setCostMode(TestSuite.CostMode.RANDOM);
        testCase.setMinimumCost(1);
        testCase.setMaximumCost(5);
        testCase.setEndpointMode(TestSuite.EndpointMode.RANDOM);
        testCase.setAutomaticSeed(false);
        testCase.setSeed(12345L);
        testCase.setIterations(1);
        testCase.setAlgorithms(EnumSet.of(MazeSolver.Algorithm.UCS));
        return testCase;
    }

    private static MazeSolver solve(Maze maze, MazeSolver.Algorithm algorithm) {
        MazeSolver solver = new MazeSolver();
        solver.setAlg(algorithm.name());
        solver.setMaze(maze);
        while (!solver.isFinished()) {
            solver.updateSolve();
        }
        return solver;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
