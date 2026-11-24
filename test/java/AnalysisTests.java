package test;

import analysis.ResultQuery;
import analysis.ResultRecord;
import analysis.ResultStatistics;
import experiments.ExperimentResult;
import experiments.TestSuite;
import java.util.List;
import maze.search.MazeSolver;

/** Deterministic checks for the analysis pipeline, also callable without the GUI. */
public final class AnalysisTests {
    private AnalysisTests() { }
    /** Runs focused analysis checks. */
    public static void main(String[] args) throws Exception {
        run();
        if (args.length == 2 && args[0].equals("--artifacts")) {
            writeArtifacts(java.nio.file.Path.of(args[1]));
        } else if (args.length != 0) {
            throw new IllegalArgumentException("Usage: test.AnalysisTests [--artifacts DIRECTORY]");
        }
        System.out.println("All analysis checks passed.");
    }
    private static void writeArtifacts(java.nio.file.Path directory) throws Exception {
        List<ResultRecord> rows = new java.util.ArrayList<>();
        for (int algorithm = 0; algorithm < 2; algorithm++) {
            long[] times = algorithm == 0 ? new long[] {9, 10, 10, 11, 1000} : new long[] {99, 100, 100, 101, 1000};
            for (int iteration = 0; iteration < times.length; iteration++) {
                rows.add(sample(algorithm == 0 ? MazeSolver.Algorithm.UCS : MazeSolver.Algorithm.GREEDY,
                        algorithm == 0 ? 10 : 15, algorithm == 0 ? 100 : 20,
                        times[iteration] * 1_000_000, "validation-map", iteration + 1));
            }
        }
        analysis.ResultFiles.save(rows, directory.resolve("known-results.csv"));
        TestSuite suite = new TestSuite("analysis-smoke");
        for (int size : new int[] {20, 40, 60}) {
            TestSuite.TestCase row = new TestSuite.TestCase("size-" + size);
            row.setRows(size); row.setColumns(size); row.setBlockedPercent(20);
            row.setSeed(4200 + size); row.setIterations(10); suite.addTestCase(row);
        }
        experiments.TestSuiteIO.save(suite, directory.resolve("analysis-smoke.mstests"));
        for (var view : analysis.ResultChart.View.values()) {
            analysis.ResultChart.exportView(rows, rows,
                    new analysis.ResultChart.Spec(analysis.ResultChart.Type.BAR, ResultQuery.Field.ALGORITHM,
                            ResultStatistics.Metric.RUNTIME_MS, analysis.ResultChart.Aggregate.MEAN,
                            analysis.ResultChart.Order.AXIS, false, false), view, ResultStatistics.Outliers.IQR,
                    ResultStatistics.Metric.RUNTIME_MS, "Validation: UCS mean=10 ms; Greedy mean=100 ms after IQR removal.",
                    directory.resolve(view.name().toLowerCase(java.util.Locale.ROOT) + ".png"), 1600, 1000);
        }
        var legendSpec = new analysis.ResultChart.Spec(analysis.ResultChart.Type.BAR, ResultQuery.Field.ALGORITHM,
                ResultStatistics.Metric.RUNTIME_MS, analysis.ResultChart.Aggregate.MEAN,
                analysis.ResultChart.Order.AXIS, false, false, false, false);
        var hidden = analysis.ResultChart.render(rows, rows, legendSpec, "Legend test", 1000, 700);
        var shown = analysis.ResultChart.render(rows, rows, new analysis.ResultChart.Spec(
                analysis.ResultChart.Type.BAR, ResultQuery.Field.ALGORITHM, ResultStatistics.Metric.RUNTIME_MS,
                analysis.ResultChart.Aggregate.MEAN, analysis.ResultChart.Order.AXIS, false, false), "Legend test", 1000, 700);
        boolean legendDiffers = false;
        for (int y = 620; y < 690; y++) {
            for (int x = 0; x < 600; x++) {
                legendDiffers |= hidden.getRGB(x, y) != shown.getRGB(x, y);
            }
        }
        require(legendDiffers, "Legend visibility did not affect the image.");
        System.out.println("Validation artifacts: " + directory.toAbsolutePath());
    }
    /** Runs the analysis regression checks. */
    public static void run() throws Exception {
        ResultRecord ucs = sample(MazeSolver.Algorithm.UCS, 10, 100, 10_000_000, "map-a");
        ResultRecord greedy = sample(MazeSolver.Algorithm.GREEDY, 15, 20, 2_000_000, "map-a");
        ResultRecord failure = sample(MazeSolver.Algorithm.A_STAR, -1, 50, 3_000_000, "map-b");
        var rows = List.of(ucs, greedy, failure);
        var sorted = new ResultQuery("", "runtime_ms:asc").apply(rows);
        require(sorted.get(0) == greedy && sorted.get(2) == ucs, "Numeric global sorting failed.");
        require(new ResultQuery("", "cost:desc").apply(rows).get(2) == failure,
                "Missing costs must sort last even descending.");
        require(new ResultQuery("algorithm=UCS|GREEDY;nodes>=50", "").apply(rows).equals(List.of(ucs)),
                "OR values and AND rules did not combine correctly.");
        require(new ResultQuery("cost<0", "").apply(rows).isEmpty(), "Missing costs entered numeric filters.");
        var stats = ResultStatistics.Stats.of(List.of(1.0, 2.0, 3.0, 4.0));
        require(stats.mean() == 2.5 && stats.median() == 2.5
                && Math.abs(stats.stddev() - Math.sqrt(5.0 / 3)) < 1e-10, "Descriptive statistics wrong.");
        var summaries = ResultStatistics.summarize(List.of(greedy), rows, List.of(ResultQuery.Field.ALGORITHM));
        require(summaries.get(0).excessCost().mean() == 50
                && summaries.get(0).nodeReduction().mean() == 80, "Hidden UCS baseline was lost.");
        require(ResultStatistics.summarize(rows, rows, List.of()).stream().mapToInt(s -> s.cost().count()).sum() == 2,
                "Unsuccessful solves entered path-cost averages.");
        try { new ResultQuery("rows>=NaN", ""); throw new AssertionError("Invalid numeric filter accepted."); }
        catch (IllegalArgumentException expected) { /* Invalid values fail before querying. */ }
        testMapIdentity(ucs.result());
        testFilesAndCharts(rows);
        testNormalization();
        testCategoricalOverlayBars();
        testNumericOverlayTraces();
    }
    private static void testNumericOverlayTraces() {
        var first = sample(MazeSolver.Algorithm.A_STAR, 10, 100, 10_000_000, "same-map", 1);
        var second = sample(MazeSolver.Algorithm.A_STAR, 10, 100, 50_000_000, "same-map", 2);
        var otherMap = sample(MazeSolver.Algorithm.A_STAR, 10, 100, 50_000_000, "other-map", 2);
        var spec = new analysis.ResultChart.Spec(analysis.ResultChart.Type.SCATTER, ResultQuery.Field.RUNTIME_MS,
                ResultStatistics.Metric.NODES, analysis.ResultChart.Aggregate.MEAN,
                analysis.ResultChart.Order.AXIS, false, false);
        var matched = List.of(second, first);
        var separate = List.of(first, otherMap);
        var joined = analysis.ResultChart.renderView(matched, matched, spec, analysis.ResultChart.View.OVERLAY,
                ResultStatistics.Outliers.NONE, ResultStatistics.Metric.RUNTIME_MS, "", 1000, 700);
        var unjoined = analysis.ResultChart.renderView(separate, separate, spec, analysis.ResultChart.View.OVERLAY,
                ResultStatistics.Outliers.NONE, ResultStatistics.Metric.RUNTIME_MS, "", 1000, 700);
        boolean traceVisible = false;
        for (int y = 165; y < 535; y++) {
            traceVisible |= joined.getRGB(310, y) != unjoined.getRGB(310, y);
        }
        require(traceVisible, "Numeric overlays should connect raw points for one algorithm on the same map.");
        var returning = sample(MazeSolver.Algorithm.A_STAR, 10, 100, 10_000_000, "same-map", 3);
        var retraced = List.of(first, second, returning);
        var retracedImage = analysis.ResultChart.renderView(retraced, retraced, spec,
                analysis.ResultChart.View.OVERLAY, ResultStatistics.Outliers.NONE,
                ResultStatistics.Metric.RUNTIME_MS, "", 1000, 700);
        for (int y = 165; y < 535; y++) {
            require(joined.getRGB(310, y) == retracedImage.getRGB(310, y),
                    "Retracing the same numeric line should not darken it.");
        }
    }
    private static void testCategoricalOverlayBars() {
        var ucs = sample(MazeSolver.Algorithm.UCS, 10, 100, 10_000_000, "same-map", 1);
        var greedy = sample(MazeSolver.Algorithm.GREEDY, 15, 20, 50_000_000, "same-map", 1);
        var unmatched = sample(MazeSolver.Algorithm.GREEDY, 15, 20, 50_000_000, "same-map", 2);
        var spec = new analysis.ResultChart.Spec(analysis.ResultChart.Type.BAR, ResultQuery.Field.ALGORITHM,
                ResultStatistics.Metric.RUNTIME_MS, analysis.ResultChart.Aggregate.MEAN,
                analysis.ResultChart.Order.AXIS, false, false);
        var matchedRows = List.of(ucs, greedy);
        var unmatchedRows = List.of(ucs, unmatched);
        var matchedImage = analysis.ResultChart.renderView(matchedRows, matchedRows, spec,
                analysis.ResultChart.View.OVERLAY, ResultStatistics.Outliers.NONE,
                ResultStatistics.Metric.RUNTIME_MS, "", 1000, 700);
        var unmatchedImage = analysis.ResultChart.renderView(unmatchedRows, unmatchedRows, spec,
                analysis.ResultChart.View.OVERLAY, ResultStatistics.Outliers.NONE,
                ResultStatistics.Metric.RUNTIME_MS, "", 1000, 700);
        int meanColor = unmatchedImage.getRGB(323, 490);
        int rawColor = unmatchedImage.getRGB(370, 490);
        require(meanColor != rawColor && rawColor != java.awt.Color.WHITE.getRGB()
                && meanColor != java.awt.Color.WHITE.getRGB(), "Overlay did not draw both bars.");
        for (int y = 170; y < 530; y++) {
            require(matchedImage.getRGB(530, y) == unmatchedImage.getRGB(530, y),
                    "Matching iterations must not draw a line between bar groups.");
        }
    }
    private static void testNormalization() throws Exception {
        List<ResultRecord> records = new java.util.ArrayList<>();
        for (long time : new long[] {9, 10, 10, 11, 1000}) {
            records.add(sample(MazeSolver.Algorithm.UCS, 10, 100, time * 1_000_000, "same-map"));
        }
        for (long time : new long[] {99, 100, 100, 101, 1000}) {
            records.add(sample(MazeSolver.Algorithm.GREEDY, 15, 20, time * 1_000_000, "same-map"));
        }
        var trimmed = ResultStatistics.prepare(records, records,
                ResultStatistics.Outliers.IQR, ResultStatistics.Metric.RUNTIME_MS);
        require(trimmed.excluded() == 2 && records.size() == 10 && trimmed.rows().size() == 8,
                "Outlier filtering mutated raw results or pooled algorithms.");
        var summaries = ResultStatistics.summarize(records, records, List.of(ResultQuery.Field.MAP,
                ResultQuery.Field.ALGORITHM), ResultStatistics.Outliers.IQR, ResultStatistics.Metric.RUNTIME_MS);
        require(summaries.size() == 2 && summaries.get(0).runtime().mean() == 10
                && summaries.get(1).runtime().mean() == 100 && summaries.get(0).excluded() == 1,
                "Typical values were not calculated separately for each algorithm on the same map.");
        require(ResultStatistics.summarize(records, records, List.of(ResultQuery.Field.MAP),
                ResultStatistics.Outliers.IQR, ResultStatistics.Metric.RUNTIME_MS).size() == 2,
                "Changing grouping pooled different algorithms on one map.");
        var baselines = new ResultStatistics.Baselines(records);
        List<ResultRecord> unequal = new java.util.ArrayList<>(records.subList(0, 4));
        unequal.add(sample(MazeSolver.Algorithm.UCS, 10, 100, 30_000_000, "other-map"));
        require(ResultStatistics.normalizedStats(unequal, ResultStatistics.Metric.RUNTIME_MS, baselines).mean() == 20,
                "Maps with more iterations were weighted more heavily.");
        for (var view : analysis.ResultChart.View.values()) {
            var spec = new analysis.ResultChart.Spec(analysis.ResultChart.Type.BAR, ResultQuery.Field.ALGORITHM,
                    ResultStatistics.Metric.RUNTIME_MS, analysis.ResultChart.Aggregate.MEAN,
                    analysis.ResultChart.Order.AXIS, false, false);
            var image = analysis.ResultChart.renderView(records, records, spec, view, ResultStatistics.Outliers.IQR,
                    ResultStatistics.Metric.RUNTIME_MS, "Same-map per-algorithm validation", 1000, 700);
            require(image.getWidth() == (view == analysis.ResultChart.View.BOTH ? 2000 : 1000),
                    "Raw/normalized/overlay rendering failed: " + view);
        }
    }
    private static void testFilesAndCharts(List<ResultRecord> rows) throws Exception {
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("maze-analysis-check-");
        try {
            java.nio.file.Path csv = directory.resolve("results.csv");
            analysis.ResultFiles.save(rows, csv);
            List<ResultRecord> reloaded = analysis.ResultFiles.load(csv);
            require(reloaded.size() == rows.size() && reloaded.get(0).result().getSuiteName()
                    .equals(rows.get(0).result().getSuiteName()) && reloaded.get(0).mapId().equals("map-a"),
                    "Quoted/multiline CSV or provenance round trip failed.");
            for (var type : analysis.ResultChart.Type.values()) {
                java.nio.file.Path png = directory.resolve(type + ".png");
                var spec = new analysis.ResultChart.Spec(type,
                        type == analysis.ResultChart.Type.LINE || type == analysis.ResultChart.Type.SCATTER
                                ? ResultQuery.Field.NODES : ResultQuery.Field.ALGORITHM,
                        ResultStatistics.Metric.RUNTIME_MS, analysis.ResultChart.Aggregate.MEAN,
                        analysis.ResultChart.Order.AXIS, false, true);
                analysis.ResultChart.export(reloaded, reloaded, spec, "Test filter", png, 1000, 700);
                var image = javax.imageio.ImageIO.read(png.toFile());
                require(image != null && image.getWidth() == 1000 && image.getHeight() == 700,
                        "Chart PNG dimensions or encoding wrong: " + type);
                require(image.getRGB(110, 165) != java.awt.Color.WHITE.getRGB(), "Chart axes were not drawn.");
            }
            experiments.TestSuiteIO.exportCsv(rows.stream().map(ResultRecord::result).toList(), csv);
            var legacy = analysis.ResultFiles.load(csv);
            require(legacy.get(0).mapId().isBlank() && legacy.get(0).execution().equals("UNKNOWN"),
                    "Legacy CSV invented map identity or execution mode.");
            require(ResultStatistics.summarize(legacy, legacy, List.of()).get(0).excessCost().count() == 0,
                    "Legacy CSV allowed unsafe UCS pairing.");
            java.nio.file.Files.writeString(csv, "\"unterminated");
            try { analysis.ResultFiles.load(csv); throw new AssertionError("Malformed CSV accepted."); }
            catch (java.io.IOException expected) { /* Expected rejection. */ }
        } finally {
            try (var files = java.nio.file.Files.list(directory)) {
                for (var file : files.toList()) { java.nio.file.Files.delete(file); }
            }
            java.nio.file.Files.delete(directory);
        }
    }
    private static void testMapIdentity(ExperimentResult result) {
        TestSuite suite = new TestSuite("fixture");
        TestSuite.TestCase row = new TestSuite.TestCase("condition");
        suite.addTestCase(row);
        var factory = new ResultRecord.Factory(suite);
        String before = factory.create(result).mapId();
        row.setMaximumCost(99);
        require(factory.create(result).mapId().equals(before), "Run provenance changed after editing settings.");
        require(!new ResultRecord.Factory(suite).create(result).mapId().equals(before),
                "Map identity ignored movement-cost settings.");
    }
    static ResultRecord sample(MazeSolver.Algorithm algorithm, long cost, int nodes, long time, String map) {
        return sample(algorithm, cost, nodes, time, map, 1);
    }
    static ResultRecord sample(MazeSolver.Algorithm algorithm, long cost, int nodes, long time, String map, int iteration) {
        var r = new ExperimentResult("fixture, \"quoted\"\nname", "condition", iteration, 42,
                TestSuite.MapSource.MANUAL_SEED, null, 100, 100, 50, 50, 4802,
                TestSuite.ReachabilityMode.NATURAL, !map.equals("map-b"), TestSuite.CostMode.RANDOM,
                1, 10, 1, 1, 98, 98, -1, 194, algorithm, cost >= 0,
                cost >= 0 ? 5 : -1, cost, nodes, time);
        return new ResultRecord(r, map, "SEQUENTIAL", "true", "OPPOSITE_CORNERS", "MANHATTAN");
    }
    static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
