package experiments;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import analysis.ResultChart;
import analysis.ResultFiles;
import analysis.ResultQuery;
import analysis.ResultRecord;
import analysis.ResultStatistics;

/**
 * Headless command-line entry point for running and benchmarking experiment
 * suites without starting Swing.
 *
 * <p>Example:
 * {@code java -cp build\classes experiments.ExperimentCli --suite suite.mstests
 * --mode FIXED_PARALLEL --repeat 3 --csv results.csv}
 */
public final class ExperimentCli {
    private static final long DEFAULT_MONITOR_INTERVAL_MS = 1000L;

    private ExperimentCli() {
    }

    /**
     * Runs the command-line entry point.
     *
     * @param args command-line options
     */
    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--version")) {
            System.out.println("Maze-Solver " + app.Version.RELEASE);
            return;
        }
        try {
            run(args);
        } catch (IllegalArgumentException | IllegalStateException | IOException exception) {
            System.err.println("Experiment CLI error: " + exception.getMessage());
            System.err.println(usage());
            System.exit(2);
        }
    }

    private static void run(String[] args) throws IOException {
        Options options = Options.parse(args);
        if (options.help) {
            System.out.println(usage());
            return;
        }

        // Validate analysis controls before starting a potentially expensive suite.
        new ResultQuery(options.filters, options.sorting);
        options.chartSpec(); options.groups();
        if (options.resultsFile != null) {
            analyze(ResultFiles.load(options.resultsFile), options);
            return;
        }

        TestSuite suite = TestSuiteIO.load(options.suiteFile);
        if (options.mode != null) {
            suite.setExecutionMode(options.mode);
        }
        suite.validate();
        ResultRecord.Factory provenance = new ResultRecord.Factory(suite);

        AtomicBoolean cancelled = new AtomicBoolean();
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> cancelled.set(true), "maze-solver-cli-shutdown"));

        long totalRuns = totalRuns(suite);
        long totalMaps = totalMaps(suite);
        System.out.printf(Locale.ROOT,
                "suite=%s mode=%s repeats=%d plannedRuns=%d plannedMaps=%d%n",
                suite.getName(), suite.getExecutionMode(), options.repeats, totalRuns, totalMaps);

        long[] elapsedNanos = new long[options.repeats];
        List<ExperimentResult> lastResults = null;
        for (int repeat = 1; repeat <= options.repeats; repeat++) {
            if (cancelled.get()) {
                break;
            }
            RunMonitor monitor = new RunMonitor(
                    repeat, options.repeats, totalRuns, totalMaps,
                    options.monitorIntervalMs, options.quiet);
            Thread monitorThread = new Thread(monitor, "maze-solver-cli-monitor");
            monitorThread.setDaemon(true);
            monitorThread.start();
            long start = System.nanoTime();
            boolean runCompleted = false;
            try {
                lastResults = ExperimentRunner.run(
                        suite,
                        (completedRuns, total, result) ->
                                monitor.completedRuns.set(completedRuns),
                        cancelled::get,
                        null,
                        (generated, total, reserved, budget, heapUsed, maxHeap, loaded, message) -> {
                            monitor.generatedMaps.set(generated);
                            monitor.loadedMaps.set(loaded);
                        });
                runCompleted = true;
            } finally {
                if (runCompleted) {
                    elapsedNanos[repeat - 1] = System.nanoTime() - start;
                }
                monitor.stop();
                try {
                    monitorThread.join(Math.max(100L, options.monitorIntervalMs * 2L));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    cancelled.set(true);
                }
            }
            if (cancelled.get()) {
                System.out.println("cancelled=true");
                break;
            }
            System.out.printf(Locale.ROOT,
                    "repeat=%d/%d status=PASS results=%d elapsedMs=%.3f%n",
                    repeat, options.repeats, lastResults.size(),
                    elapsedNanos[repeat - 1] / 1_000_000.0);
        }

        int completedRepeats = completedRepeats(elapsedNanos);
        if (lastResults != null) { analyze(lastResults.stream().map(provenance::create).toList(), options); }
        if (completedRepeats > 0) {
            printSummary(elapsedNanos, completedRepeats);
        }
    }

    private static void analyze(List<ResultRecord> records, Options options) throws IOException {
        List<ResultRecord> selected = new ResultQuery(options.filters, options.sorting).apply(records);
        if (options.normalizedCsv != null && selected.stream().anyMatch(r -> r.mapId().isBlank())) {
            throw new IllegalArgumentException("Normalized CSV requires verified map identities; use --summary-csv for legacy results.");
        }
        if (options.chartFile != null && !options.chartFile.toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
            throw new IllegalArgumentException("Graph images must use the .png extension.");
        }
        var prepared = ResultStatistics.prepare(selected, records, options.outliers, options.outlierMetric);
        var summaries = ResultStatistics.summarize(selected, records, options.groups(), options.outliers, options.outlierMetric);
        System.out.printf(Locale.ROOT, "analysis=total:%d matching:%d normalizedRetained:%d excluded:%d outliers:%s%n",
                records.size(), selected.size(), prepared.rows().size(), prepared.excluded(), options.outliers);
        if (options.validate) { validate(records); }
        if (options.csvFile != null) {
            ResultFiles.save(records, options.csvFile); System.out.println("csv=" + options.csvFile.toAbsolutePath());
        }
        if (options.filteredCsv != null) {
            ResultFiles.save(selected, options.filteredCsv); System.out.println("filteredCsv=" + options.filteredCsv.toAbsolutePath());
        }
        if (options.summaryCsv != null) {
            ResultFiles.saveSummary(summaries, options.summaryCsv); System.out.println("summaryCsv=" + options.summaryCsv.toAbsolutePath());
        }
        if (options.normalizedCsv != null) {
            ResultFiles.saveSummary(ResultStatistics.summarize(selected, records,
                    List.of(ResultQuery.Field.MAP, ResultQuery.Field.ALGORITHM), options.outliers, options.outlierMetric),
                    options.normalizedCsv);
            System.out.println("normalizedCsv=" + options.normalizedCsv.toAbsolutePath());
        }
        if (options.chartFile != null) {
            ResultChart.exportView(selected, records, options.chartSpec(), options.chartView,
                    options.outliers, options.outlierMetric, "Filters: " + options.filters + "; sort: " + options.sorting,
                    options.chartFile, options.chartWidth, options.chartHeight);
            System.out.println("chart=" + options.chartFile.toAbsolutePath());
        }
    }

    private static void validate(List<ResultRecord> records) {
        var baselines = new ResultStatistics.Baselines(records);
        int pairs = 0;
        for (ResultRecord record : records) {
            ExperimentResult r = record.result();
            if (r.isSolved() ? r.getPathCost() < 0 || r.getPathLength() < 0
                    : r.getPathCost() != -1 || r.getPathLength() != -1) {
                throw new IllegalArgumentException("Inconsistent solved metrics: " + r.getConfigurationId());
            }
            Double excess = ResultStatistics.Metric.EXCESS_COST.value(record, baselines);
            if (excess != null) {
                pairs++;
                if (excess < -1e-9 || (r.getAlgorithm() == maze.search.MazeSolver.Algorithm.A_STAR
                        && Math.abs(excess) > 1e-9)) {
                    throw new IllegalArgumentException("UCS/A* optimality comparison failed: " + r.getConfigurationId());
                }
            }
        }
        System.out.println("validation=PASS records:" + records.size() + " verifiedCostPairs:" + pairs
                + " (measurement checks; does not reconstruct paths)");
    }

    private static int completedRepeats(long[] elapsedNanos) {
        int completed = 0;
        for (long elapsed : elapsedNanos) {
            if (elapsed > 0L) {
                completed++;
            }
        }
        return completed;
    }

    private static void printSummary(long[] elapsedNanos, int completedRepeats) {
        long min = Long.MAX_VALUE;
        long max = 0L;
        long total = 0L;
        for (int index = 0; index < completedRepeats; index++) {
            min = Math.min(min, elapsedNanos[index]);
            max = Math.max(max, elapsedNanos[index]);
            total += elapsedNanos[index];
        }
        System.out.printf(Locale.ROOT,
                "summary=repeats:%d minMs:%.3f avgMs:%.3f maxMs:%.3f%n",
                completedRepeats, min / 1_000_000.0,
                total / (double) completedRepeats / 1_000_000.0,
                max / 1_000_000.0);
    }

    private static long totalRuns(TestSuite suite) {
        long total = 0L;
        for (TestSuite.TestCase testCase : suite.getTestCases()) {
            total += (long) testCase.getIterations() * testCase.getAlgorithms().size();
        }
        return total;
    }

    private static long totalMaps(TestSuite suite) {
        long total = 0L;
        for (TestSuite.TestCase testCase : suite.getTestCases()) {
            total += testCase.getIterations();
        }
        return total;
    }

    private static String usage() {
        return String.join(System.lineSeparator(),
                "Usage: java -cp build\\classes experiments.ExperimentCli --suite FILE [options]",
                "  --suite FILE          .mstests suite to execute, OR --results FILE",
                "  --results FILE        analyze saved results CSV without rerunning solvers",
                "  --mode MODE           SEQUENTIAL, FIXED_PARALLEL, or ADAPTIVE_PARALLEL",
                "  --repeat N            execute the suite N times (default: 1)",
                "  --monitor-ms N        resource/progress sampling interval (default: 1000)",
                "  --csv FILE            export the final completed run to CSV",
                "  --filter EXPR         AND rules (;), OR values (|): algorithm=UCS|A_STAR;rows>=500",
                "  --sort KEYS           global numeric sort: map:asc,algorithm:asc,runtime_ms:asc",
                "  --filtered-csv FILE   export all matching iterations, in sorted order",
                "  --summary-csv FILE    export descriptive statistics and exclusion counts",
                "  --normalized-csv FILE export retained means/medians per map AND algorithm",
                "  --group-by FIELDS     summary groups (default: configuration,map,algorithm)",
                "  --chart FILE          export a labeled PNG (default: 1600x1000)",
                "  --chart-view VIEW     ITERATIONS, NORMALIZED, OVERLAY (default), BOTH",
                "  --normalize           shorthand for --chart-view NORMALIZED",
                "  --outliers POLICY     NONE or IQR (default: IQR for normalized summaries/views)",
                "  --outlier-metric NAME RUNTIME_MS (default), NODES, COST, LENGTH, etc.",
                "  --chart-type TYPE     BAR (default), LINE, SCATTER, BOX",
                "  --chart-x FIELD       X/group field (default: ALGORITHM); use numeric for LINE/SCATTER",
                "  --metric NAME         RUNTIME_MS, NODES, COST, LENGTH, SUCCESS, EXCESS_COST, NODE_REDUCTION",
                "  --chart-y NAME        alias for --metric (the Y axis)",
                "  --aggregate NAME      MEAN (default), MEDIAN, MIN, MAX for iteration views",
                "  --chart-order ORDER   AXIS, VALUE_ASC, VALUE_DESC (categories only)",
                "  --log-y               positive-only log10 Y axis",
                "  --error-bars          +/- sample SD on mean charts",
                "  --hide-legend         omit the algorithm/execution key from the PNG",
                "  --show-legend         include the key (default)",
                "  --chart-width N       per-panel width, 640..4096",
                "  --chart-height N      height, 480..4096 (BOTH doubles total width)",
                "  --preset FILE         load a .msview; subsequent flags override its settings",
                "  --validate            check measurement consistency and matched UCS/A* costs",
                "  --fields              print filter/sort/group field names",
                "  --quiet               suppress periodic monitor lines",
                "  --version             print the application version",
                "  --help                show this help");
    }

    private static final class RunMonitor implements Runnable {
        private final int repeat;
        private final int repeats;
        private final long totalRuns;
        private final long totalMaps;
        private final long intervalMs;
        private final boolean quiet;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicInteger completedRuns = new AtomicInteger();
        private final AtomicInteger generatedMaps = new AtomicInteger();
        private final AtomicInteger loadedMaps = new AtomicInteger();

        private RunMonitor(
                int repeat, int repeats, long totalRuns, long totalMaps,
                long intervalMs, boolean quiet) {
            this.repeat = repeat;
            this.repeats = repeats;
            this.totalRuns = totalRuns;
            this.totalMaps = totalMaps;
            this.intervalMs = intervalMs;
            this.quiet = quiet;
        }

        @Override
        public void run() {
            while (running.get()) {
                if (!quiet) {
                    printStatus();
                }
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!quiet) {
                printStatus();
            }
        }

        private void stop() {
            running.set(false);
        }

        private void printStatus() {
            Runtime runtime = Runtime.getRuntime();
            long used = runtime.totalMemory() - runtime.freeMemory();
            System.out.printf(Locale.ROOT,
                    "monitor=repeat:%d/%d runs:%d/%d maps:%d/%d loadedMaps:%d "
                            + "heapUsedMb:%.1f heapMaxMb:%.1f activeWorkers:%d "
                            + "mapWorkers:%d solverWorkers:%d peakMapWorkers:%d "
                            + "peakSolverWorkers:%d%n",
                    repeat, repeats, completedRuns.get(), totalRuns,
                    generatedMaps.get(), totalMaps, loadedMaps.get(),
                    used / 1048576.0, runtime.maxMemory() / 1048576.0,
                    ExperimentRunner.getActiveWorkerCount(),
                    ExperimentRunner.getActiveMapWorkerCount(),
                    ExperimentRunner.getActiveSolverWorkerCount(),
                    ExperimentRunner.getIntervalPeakMapWorkerCount(),
                    ExperimentRunner.getIntervalPeakSolverWorkerCount());
        }
    }

    private static final class Options {
        private Path suiteFile;
        private Path resultsFile, filteredCsv, summaryCsv, normalizedCsv, chartFile;
        private String filters = "", sorting = "", groupBy = "configuration,map,algorithm";
        private ResultChart.Type chartType = ResultChart.Type.BAR;
        private ResultChart.View chartView = ResultChart.View.OVERLAY;
        private ResultQuery.Field chartX = ResultQuery.Field.ALGORITHM;
        private ResultStatistics.Metric metric = ResultStatistics.Metric.RUNTIME_MS;
        private ResultStatistics.Metric outlierMetric = ResultStatistics.Metric.RUNTIME_MS;
        private ResultStatistics.Outliers outliers = ResultStatistics.Outliers.IQR;
        private ResultChart.Aggregate aggregate = ResultChart.Aggregate.MEAN;
        private ResultChart.Order chartOrder = ResultChart.Order.AXIS;
        private boolean logY, errorBars, validate;
        private boolean showLegend = true;
        private int chartWidth = 1600, chartHeight = 1000;
        private TestSuite.ExecutionMode mode;
        private int repeats = 1;
        private long monitorIntervalMs = DEFAULT_MONITOR_INTERVAL_MS;
        private Path csvFile;
        private boolean quiet;
        private boolean help;

        private static Options parse(String[] args) throws IOException {
            Options options = new Options();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                switch (argument) {
                    case "--fields":
                        System.out.println(java.util.Arrays.toString(ResultQuery.Field.values()));
                        options.help = true;
                        break;
                    case "--results": options.resultsFile = Paths.get(value(args, ++index, argument)); break;
                    case "--filter":
                        String rule = value(args, ++index, argument);
                        options.filters = options.filters.isBlank() ? rule : options.filters + ";" + rule;
                        break;
                    case "--sort": options.sorting = value(args, ++index, argument); break;
                    case "--group-by": options.groupBy = value(args, ++index, argument); break;
                    case "--filtered-csv": options.filteredCsv = Paths.get(value(args, ++index, argument)); break;
                    case "--summary-csv": options.summaryCsv = Paths.get(value(args, ++index, argument)); break;
                    case "--normalized-csv": options.normalizedCsv = Paths.get(value(args, ++index, argument)); break;
                    case "--chart": options.chartFile = Paths.get(value(args, ++index, argument)); break;
                    case "--chart-type": options.chartType = enumValue(ResultChart.Type.class, value(args, ++index, argument)); break;
                    case "--chart-view": options.chartView = enumValue(ResultChart.View.class, value(args, ++index, argument)); break;
                    case "--normalize": options.chartView = ResultChart.View.NORMALIZED; break;
                    case "--outliers": options.outliers = enumValue(ResultStatistics.Outliers.class, value(args, ++index, argument)); break;
                    case "--outlier-metric": options.outlierMetric = enumValue(ResultStatistics.Metric.class, value(args, ++index, argument)); break;
                    case "--chart-x": options.chartX = ResultQuery.Field.parse(value(args, ++index, argument)); break;
                    case "--metric":
                    case "--chart-y": options.metric = enumValue(ResultStatistics.Metric.class, value(args, ++index, argument)); break;
                    case "--aggregate": options.aggregate = enumValue(ResultChart.Aggregate.class, value(args, ++index, argument)); break;
                    case "--chart-order": options.chartOrder = enumValue(ResultChart.Order.class, value(args, ++index, argument)); break;
                    case "--log-y": options.logY = true; break;
                    case "--error-bars": options.errorBars = true; break;
                    case "--hide-legend": options.showLegend = false; break;
                    case "--show-legend": options.showLegend = true; break;
                    case "--validate": options.validate = true; break;
                    case "--chart-width": options.chartWidth = positiveInt(value(args, ++index, argument), argument); break;
                    case "--chart-height": options.chartHeight = positiveInt(value(args, ++index, argument), argument); break;
                    case "--preset": options.loadPreset(Paths.get(value(args, ++index, argument))); break;
                    case "--help":
                        options.help = true;
                        break;
                    case "--quiet":
                        options.quiet = true;
                        break;
                    case "--suite":
                        options.suiteFile = Paths.get(value(args, ++index, argument));
                        break;
                    case "--mode":
                        try {
                            options.mode = TestSuite.ExecutionMode.valueOf(
                                    value(args, ++index, argument).toUpperCase(Locale.ROOT));
                        } catch (IllegalArgumentException exception) {
                            throw new IllegalArgumentException("Invalid execution mode.", exception);
                        }
                        break;
                    case "--repeat":
                        options.repeats = positiveInt(value(args, ++index, argument), argument);
                        break;
                    case "--monitor-ms":
                        options.monitorIntervalMs = positiveLong(
                                value(args, ++index, argument), argument);
                        break;
                    case "--csv":
                        options.csvFile = Paths.get(value(args, ++index, argument));
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown option: " + argument);
                }
            }
            if (!options.help && ((options.suiteFile == null) == (options.resultsFile == null))) {
                throw new IllegalArgumentException("Specify exactly one of --suite or --results.");
            }
            return options;
        }

        private ResultChart.Spec chartSpec() {
            if (chartWidth < 640 || chartWidth > 4096 || chartHeight < 480 || chartHeight > 4096) {
                throw new IllegalArgumentException("Chart dimensions must be 640..4096 by 480..4096.");
            }
            ResultChart.Type effectiveType = chartView == ResultChart.View.OVERLAY
                    ? (chartX.isNumeric() ? ResultChart.Type.SCATTER : ResultChart.Type.BAR) : chartType;
            return new ResultChart.Spec(effectiveType, chartX, metric, aggregate, chartOrder, logY, errorBars, false, showLegend);
        }
        private List<ResultQuery.Field> groups() {
            return groupBy.isBlank() ? List.of() : java.util.Arrays.stream(groupBy.split(",", -1))
                    .map(ResultQuery.Field::parse).toList();
        }
        private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
            if (value == null) { throw new IllegalArgumentException("Missing " + type.getSimpleName() + " value."); }
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        }
        private void loadPreset(Path file) throws IOException {
            java.util.Properties p = new java.util.Properties();
            try (var reader = java.nio.file.Files.newBufferedReader(file)) { p.load(reader); }
            if (!p.getProperty("version", "").equals("1")) { throw new IOException("Unsupported view version."); }
            filters = p.getProperty("filters", ""); sorting = p.getProperty("sort", ""); groupBy = p.getProperty("groups", "");
            chartType = enumValue(ResultChart.Type.class, p.getProperty("type")); chartX = ResultQuery.Field.parse(p.getProperty("x"));
            metric = enumValue(ResultStatistics.Metric.class, p.getProperty("metric"));
            aggregate = enumValue(ResultChart.Aggregate.class, p.getProperty("aggregate"));
            chartOrder = enumValue(ResultChart.Order.class, p.getProperty("order"));
            chartView = enumValue(ResultChart.View.class, p.getProperty("view"));
            outliers = enumValue(ResultStatistics.Outliers.class, p.getProperty("outliers"));
            outlierMetric = enumValue(ResultStatistics.Metric.class, p.getProperty("outlierMetric"));
            logY = Boolean.parseBoolean(p.getProperty("logY")); errorBars = Boolean.parseBoolean(p.getProperty("errorBars"));
            showLegend = Boolean.parseBoolean(p.getProperty("showLegend", "true"));
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value.");
            }
            return args[index];
        }

        private static int positiveInt(String value, String option) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Report a consistent command-line validation message below.
            }
            throw new IllegalArgumentException(option + " must be a positive integer.");
        }

        private static long positiveLong(String value, String option) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed > 0L) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Report a consistent command-line validation message below.
            }
            throw new IllegalArgumentException(option + " must be a positive integer.");
        }
    }
}
