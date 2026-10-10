package test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

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
        testReproducibleExperimentRuns();
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
