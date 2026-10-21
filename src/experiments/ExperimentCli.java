package experiments;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
        try {
            run(args);
        } catch (IllegalArgumentException | IOException exception) {
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

        TestSuite suite = TestSuiteIO.load(options.suiteFile);
        if (options.mode != null) {
            suite.setExecutionMode(options.mode);
        }
        suite.validate();

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
        if (lastResults != null && options.csvFile != null) {
            TestSuiteIO.exportCsv(lastResults, options.csvFile);
            System.out.println("csv=" + options.csvFile.toAbsolutePath());
        }
        if (completedRepeats > 0) {
            printSummary(elapsedNanos, completedRepeats);
        }
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
                "  --suite FILE          .mstests suite to execute (required)",
                "  --mode MODE           SEQUENTIAL, FIXED_PARALLEL, or ADAPTIVE_PARALLEL",
                "  --repeat N            execute the suite N times (default: 1)",
                "  --monitor-ms N        resource/progress sampling interval (default: 1000)",
                "  --csv FILE            export the final completed run to CSV",
                "  --quiet               suppress periodic monitor lines",
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
        private TestSuite.ExecutionMode mode;
        private int repeats = 1;
        private long monitorIntervalMs = DEFAULT_MONITOR_INTERVAL_MS;
        private Path csvFile;
        private boolean quiet;
        private boolean help;

        private static Options parse(String[] args) {
            Options options = new Options();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                switch (argument) {
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
            if (!options.help && options.suiteFile == null) {
                throw new IllegalArgumentException("--suite is required.");
            }
            return options;
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
