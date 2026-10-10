package experiments;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.StringJoiner;

import app.Version;
import maze.map.Maze;
import maze.search.MazeSolver;

/**
 * Persistence and reporting utilities for experiment suites and results.
 *
 * <p>Suite files are versioned UTF-8 properties files with the {@code .mstests}
 * extension. Result CSV files use
 * RFC 4180-style quoting so suite names, identifiers, and paths remain valid
 * when they contain commas or line breaks.
 */
public final class TestSuiteIO {
    /** Extension used for editable experiment suite files. */
    public static final String SUITE_EXTENSION = ".mstests";
    private static final String FORMAT = "maze-solver-test-suite";
    /** Current serialized suite schema version. */
    public static final String SUITE_FORMAT_VERSION = "1";
    private static final String VERSION_PROPERTY = "version";
    private static final String APPLICATION_VERSION_PROPERTY = "applicationVersion";
    private static final SecureRandom SEED_SOURCE = new SecureRandom();

    private TestSuiteIO() {
    }

    /**
     * Saves a suite and materializes automatic seeds for reproducibility.
     *
     * @param suite suite to save
     * @param file destination file
     * @throws IOException if the file cannot be written
     */
    public static void save(TestSuite suite, Path file) throws IOException {
        requireSuiteAndFile(suite, file);
        requireExtension(file, SUITE_EXTENSION, "test-suite");
        suite.validate();
        Properties properties = new Properties();
        properties.setProperty("format", FORMAT);
        properties.setProperty(VERSION_PROPERTY, SUITE_FORMAT_VERSION);
        properties.setProperty(APPLICATION_VERSION_PROPERTY, Version.RELEASE);
        properties.setProperty("name", suite.getName());
        properties.setProperty("automaticExport", Boolean.toString(suite.isAutomaticExport()));
        properties.setProperty("exportDirectory",
                suite.getExportDirectory() == null ? "" : suite.getExportDirectory());
        properties.setProperty("rowCount", Integer.toString(suite.getTestCases().size()));

        for (int index = 0; index < suite.getTestCases().size(); index++) {
            TestSuite.TestCase row = suite.getTestCases().get(index);
            if (row.getMapSource() == TestSuite.MapSource.AUTOMATIC_SEED) {
                row.setSeed(SEED_SOURCE.nextLong());
                row.setMapSource(TestSuite.MapSource.MANUAL_SEED);
            }
            writeRow(properties, index, row);
        }

        createParentDirectory(file);
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            properties.store(writer, "Maze Solver experiment suite");
        }
    }

    /**
     * Loads and validates a previously saved suite.
     *
     * @param file suite file
     * @return loaded suite
     * @throws IOException if the file cannot be read or is malformed
     */
    public static TestSuite load(Path file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("Suite file cannot be null.");
        }
        requireExtension(file, SUITE_EXTENSION, "test-suite");
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        requireProperty(properties, "format");
        if (!FORMAT.equals(properties.getProperty("format"))) {
            throw new IOException("Unsupported test-suite format.");
        }
        if (!SUITE_FORMAT_VERSION.equals(properties.getProperty(VERSION_PROPERTY))) {
            throw new IOException("Unsupported test-suite version.");
        }

        TestSuite suite = new TestSuite(requireProperty(properties, "name"));
        suite.setAutomaticExport(parseBoolean(properties, "automaticExport"));
        String exportDirectory = properties.getProperty("exportDirectory", "");
        suite.setExportDirectory(exportDirectory.isEmpty() ? null : exportDirectory);
        int rowCount = parseInt(properties, "rowCount");
        if (rowCount < 1) {
            throw new IOException("Suite must contain at least one row.");
        }
        for (int index = 0; index < rowCount; index++) {
            suite.addTestCase(readRow(properties, index));
        }
        try {
            suite.validate();
        } catch (IllegalArgumentException ex) {
            throw new IOException("Loaded suite failed validation: " + ex.getMessage(), ex);
        }
        return suite;
    }

    /**
     * Exports results to a UTF-8 CSV file.
     *
     * @param results completed results
     * @param file destination CSV file
     * @throws IOException if the file cannot be written
     */
    public static void exportCsv(List<ExperimentResult> results, Path file) throws IOException {
        if (results == null || file == null) {
            throw new IllegalArgumentException("Results and CSV file are required.");
        }
        createParentDirectory(file);
        StringBuilder csv = new StringBuilder();
        appendCsvRow(csv, "Suite", "Configuration", "Iteration", "Map Source", "Seed",
                "Rows", "Columns",
                "Requested Blocked %", "Actual Blocked %", "Blocked Cells", "Reachability",
                "Generated Solvable", "Cost Mode", "Minimum Cost", "Maximum Cost",
                "Start Row", "Start Column", "Goal Row", "Goal Column",
                "Requested Manhattan Distance", "Actual Manhattan Distance", "Algorithm",
                "Solved", "Path Length", "Path Cost", "Nodes Explored", "Runtime (ns)",
                "Runtime (ms)");
        for (ExperimentResult result : results) {
            appendCsvRow(csv, result.getSuiteName(), result.getConfigurationId(),
                    result.getIteration(), result.getMapSource(), result.getSeedLabel(),
                    result.getRows(),
                    result.getColumns(), result.getRequestedBlockedPercent(),
                    result.getActualBlockedPercent(), result.getBlockedCellCount(),
                    result.getReachabilityMode(), result.isGeneratedMapSolvable(),
                    result.getCostMode(), result.getMinimumCost(), result.getMaximumCost(),
                    result.getStartRow(), result.getStartColumn(), result.getGoalRow(),
                    result.getGoalColumn(), result.getRequestedManhattanDistance(),
                    result.getActualManhattanDistance(), result.getAlgorithm(),
                    result.isSolved(), result.getPathLength(), result.getPathCost(),
                    result.getNodesExplored(), result.getRuntimeNs(), result.getRuntimeMs());
        }
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Returns the current application directory for exports and file chooser defaults.
     *
     * @return the current application directory
     */
    public static Path defaultExportDirectory() {
        return Paths.get("").toAbsolutePath();
    }

    private static void requireExtension(Path file, String extension, String description)
            throws IOException {
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(extension)) {
            throw new IOException("Invalid " + description + " extension. Expected "
                    + extension + ".");
        }
    }

    private static void writeRow(Properties properties, int index, TestSuite.TestCase row) {
        String prefix = "row." + index + ".";
        properties.setProperty(prefix + "id", row.getId());
        properties.setProperty(prefix + "rows", Integer.toString(row.getRows()));
        properties.setProperty(prefix + "columns", Integer.toString(row.getColumns()));
        properties.setProperty(prefix + "blockedPercent", Double.toString(row.getBlockedPercent()));
        properties.setProperty(prefix + "reachability", row.getReachabilityMode().name());
        properties.setProperty(prefix + "costMode", row.getCostMode().name());
        properties.setProperty(prefix + "minimumCost", Integer.toString(row.getMinimumCost()));
        properties.setProperty(prefix + "maximumCost", Integer.toString(row.getMaximumCost()));
        properties.setProperty(prefix + "sameCostBothDirections",
                Boolean.toString(row.isSameCostBothDirections()));
        properties.setProperty(prefix + "endpointMode", row.getEndpointMode().name());
        properties.setProperty(prefix + "startRow", Integer.toString(row.getStartRow()));
        properties.setProperty(prefix + "startColumn", Integer.toString(row.getStartColumn()));
        properties.setProperty(prefix + "goalRow", Integer.toString(row.getGoalRow()));
        properties.setProperty(prefix + "goalColumn", Integer.toString(row.getGoalColumn()));
        properties.setProperty(prefix + "targetDistance",
                Integer.toString(row.getTargetManhattanDistance()));
        properties.setProperty(prefix + "distanceTolerance",
                Integer.toString(row.getDistanceTolerance()));
        properties.setProperty(prefix + "seed", Long.toString(row.getSeed()));
        properties.setProperty(prefix + "mapSource", row.getMapSource().name());
        if (row.getSavedMapText() != null) {
            properties.setProperty(prefix + "savedMap",
                    Base64.getEncoder().encodeToString(
                            row.getSavedMapText().getBytes(StandardCharsets.UTF_8)));
        }
        if (row.getSavedMapName() != null) {
            properties.setProperty(prefix + "savedMapName", row.getSavedMapName());
        }
        properties.setProperty(prefix + "iterations", Integer.toString(row.getIterations()));
        StringJoiner algorithms = new StringJoiner(",");
        for (MazeSolver.Algorithm algorithm : row.getAlgorithms()) {
            algorithms.add(algorithm.name());
        }
        properties.setProperty(prefix + "algorithms", algorithms.toString());
    }

    private static TestSuite.TestCase readRow(Properties properties, int index) throws IOException {
        String prefix = "row." + index + ".";
        TestSuite.TestCase row = new TestSuite.TestCase(requireProperty(properties, prefix + "id"));
        row.setRows(parseInt(properties, prefix + "rows"));
        row.setColumns(parseInt(properties, prefix + "columns"));
        row.setBlockedPercent(parseDouble(properties, prefix + "blockedPercent"));
        row.setReachabilityMode(parseEnum(properties, prefix + "reachability",
                TestSuite.ReachabilityMode.class));
        row.setCostMode(parseEnum(properties, prefix + "costMode", TestSuite.CostMode.class));
        row.setMinimumCost(parseInt(properties, prefix + "minimumCost"));
        row.setMaximumCost(parseInt(properties, prefix + "maximumCost"));
        row.setSameCostBothDirections(parseBoolean(properties, prefix + "sameCostBothDirections"));
        row.setEndpointMode(parseEnum(properties, prefix + "endpointMode", TestSuite.EndpointMode.class));
        row.setStartRow(parseInt(properties, prefix + "startRow"));
        row.setStartColumn(parseInt(properties, prefix + "startColumn"));
        row.setGoalRow(parseInt(properties, prefix + "goalRow"));
        row.setGoalColumn(parseInt(properties, prefix + "goalColumn"));
        row.setTargetManhattanDistance(parseInt(properties, prefix + "targetDistance"));
        row.setDistanceTolerance(parseInt(properties, prefix + "distanceTolerance"));
        row.setSeed(parseLong(properties, prefix + "seed"));
        String source = properties.getProperty(prefix + "mapSource");
        if (source == null) {
            row.setAutomaticSeed(parseBoolean(properties, prefix + "automaticSeed"));
        } else {
            TestSuite.MapSource mapSource = parseEnum(properties, prefix + "mapSource",
                    TestSuite.MapSource.class);
            row.setMapSource(mapSource);
            if (mapSource == TestSuite.MapSource.SAVED_MAP) {
                String encoded = requireProperty(properties, prefix + "savedMap");
                try {
                    row.setSavedMapText(new String(Base64.getDecoder().decode(encoded),
                            StandardCharsets.UTF_8));
                } catch (IllegalArgumentException ex) {
                    throw new IOException("Invalid embedded map for " + prefix + "savedMap.", ex);
                }
                row.setSavedMapName(properties.getProperty(prefix + "savedMapName"));
            }
        }
        row.setIterations(parseInt(properties, prefix + "iterations"));
        String algorithmText = requireProperty(properties, prefix + "algorithms");
        Set<MazeSolver.Algorithm> algorithms = java.util.EnumSet.noneOf(MazeSolver.Algorithm.class);
        for (String value : algorithmText.split(",")) {
            try {
                algorithms.add(MazeSolver.Algorithm.valueOf(value));
            } catch (IllegalArgumentException ex) {
                throw new IOException("Invalid algorithm in " + prefix + "algorithms.", ex);
            }
        }
        row.setAlgorithms(algorithms);
        return row;
    }

    private static <T extends Enum<T>> T parseEnum(
            Properties properties, String key, Class<T> type) throws IOException {
        try {
            return Enum.valueOf(type, requireProperty(properties, key));
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid value for " + key + ".", ex);
        }
    }

    private static int parseInt(Properties properties, String key) throws IOException {
        try {
            return Integer.parseInt(requireProperty(properties, key));
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid integer for " + key + ".", ex);
        }
    }

    private static long parseLong(Properties properties, String key) throws IOException {
        try {
            return Long.parseLong(requireProperty(properties, key));
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid long for " + key + ".", ex);
        }
    }

    private static double parseDouble(Properties properties, String key) throws IOException {
        try {
            return Double.parseDouble(requireProperty(properties, key));
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid decimal for " + key + ".", ex);
        }
    }

    private static boolean parseBoolean(Properties properties, String key) throws IOException {
        String value = requireProperty(properties, key);
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IOException("Invalid boolean for " + key + ".");
        }
        return Boolean.parseBoolean(value);
    }

    private static String requireProperty(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("Missing property: " + key);
        }
        return value;
    }

    private static void appendCsvRow(StringBuilder output, Object... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                output.append(',');
            }
            String value = values[index] == null ? "" : values[index].toString();
            output.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        output.append(System.lineSeparator());
    }

    private static void requireSuiteAndFile(TestSuite suite, Path file) {
        if (suite == null || file == null) {
            throw new IllegalArgumentException("Suite and destination file are required.");
        }
    }

    private static void createParentDirectory(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
