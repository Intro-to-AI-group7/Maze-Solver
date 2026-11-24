package analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import maze.search.MazeSolver;

/** Aggregation and paired UCS comparisons used by reports and charts. */
public final class ResultStatistics {
    private ResultStatistics() { }

    /** Optional removal of observations beyond 1.5 times the interquartile range. */
    public enum Outliers { NONE, IQR }
    /** Retained observations plus the number excluded; source measurements are untouched. */
    public record Prepared(List<ResultRecord> rows, int excluded) { }

    /** Trims separately within each exact map/algorithm/execution group, never between maps.
     * Groups need at least four eligible values. Missing values and unknown map identities remain.
     */
    public static Prepared prepare(List<ResultRecord> selected, List<ResultRecord> all,
            Outliers policy, Metric metric) {
        if (policy == Outliers.NONE) { return new Prepared(List.copyOf(selected), 0); }
        Baselines baselines = new Baselines(all);
        Map<String, List<ResultRecord>> groups = new LinkedHashMap<>();
        for (ResultRecord r : selected) {
            if (!r.mapId().isBlank()) {
                groups.computeIfAbsent(r.mapId() + ":" + r.result().getAlgorithm() + ":" + r.execution(),
                        ignored -> new ArrayList<>()).add(r);
            }
        }
        java.util.Set<ResultRecord> removed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (List<ResultRecord> rows : groups.values()) {
            Stats stats = stats(rows, metric, baselines);
            if (stats.count() < 4) { continue; }
            double iqr = stats.q3() - stats.q1();
            double lower = stats.q1() - 1.5 * iqr, upper = stats.q3() + 1.5 * iqr;
            for (ResultRecord r : rows) {
                Double value = metric.value(r, baselines);
                if (value != null && (value < lower || value > upper)) { removed.add(r); }
            }
        }
        return new Prepared(selected.stream().filter(r -> !removed.contains(r)).toList(), removed.size());
    }

    /** Supported plotted measurements; cost metrics omit unsuccessful runs. */
    public enum Metric {
        RUNTIME_MS("Runtime (ms)"), NODES("Nodes explored"), COST("Path cost"),
        LENGTH("Path length (moves)"), SUCCESS("Success (%)"),
        EXCESS_COST("Excess cost vs UCS (%)"), NODE_REDUCTION("Nodes saved vs UCS (%)");
        private final String label;
        Metric(String label) { this.label = label; }
        @Override public String toString() { return label; }
        /** Reads a measurement, or null if no valid solution/baseline exists. */
        public Double value(ResultRecord record, Baselines baselines) {
            var r = record.result();
            return switch (this) {
                case RUNTIME_MS -> r.getRuntimeMs();
                case NODES -> (double) r.getNodesExplored();
                case COST -> r.isSolved() ? (double) r.getPathCost() : null;
                case LENGTH -> r.isSolved() ? (double) r.getPathLength() : null;
                case SUCCESS -> r.isSolved() ? 100.0 : 0.0;
                case EXCESS_COST -> {
                    ResultRecord base = baselines.get(record);
                    yield base != null && base.result().isSolved() && r.isSolved()
                            && base.result().getPathCost() > 0
                            ? 100.0 * (r.getPathCost() / (double) base.result().getPathCost() - 1) : null;
                }
                case NODE_REDUCTION -> {
                    ResultRecord base = baselines.get(record);
                    yield base != null && base.result().isSolved() && r.isSolved()
                            && base.result().getNodesExplored() > 0
                            ? 100.0 * (1 - r.getNodesExplored() / (double) base.result().getNodesExplored()) : null;
                }
            };
        }
    }

    /** Baselines come from all observations, including hidden UCS rows. */
    public static final class Baselines {
        private final Map<String, ResultRecord> ucs = new HashMap<>();
        /** Builds a lookup only for observations with a verified map identity. */
        public Baselines(List<ResultRecord> all) {
            for (ResultRecord record : all) {
                if (!record.mapId().isBlank() && record.result().getAlgorithm() == MazeSolver.Algorithm.UCS) {
                    ucs.putIfAbsent(key(record), record);
                }
            }
        }
        private static String key(ResultRecord r) { return r.execution() + ":" + r.mapId(); }
        private ResultRecord get(ResultRecord r) { return r.mapId().isBlank() ? null : ucs.get(key(r)); }
    }

    /** Descriptive statistics; standard deviation is the sample statistic (n-1). */
    public record Stats(int count, double mean, double median, double stddev,
            double min, double max, double q1, double q3) {
        /** Returns NaN for unavailable statistics; a singleton has no sample SD. */
        public static Stats of(List<Double> values) {
            double[] sorted = values.stream().filter(java.util.Objects::nonNull)
                    .mapToDouble(Double::doubleValue).filter(Double::isFinite).sorted().toArray();
            int n = sorted.length;
            if (n == 0) { return new Stats(0, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN); }
            double mean = Arrays.stream(sorted).average().orElseThrow();
            double sumSquares = 0;
            for (double value : sorted) { sumSquares += (value - mean) * (value - mean); }
            return new Stats(n, mean, percentile(sorted, .5),
                    n > 1 ? Math.sqrt(sumSquares / (n - 1)) : Double.NaN,
                    sorted[0], sorted[n - 1], percentile(sorted, .25), percentile(sorted, .75));
        }
        private static double percentile(double[] sorted, double p) {
            double index = (sorted.length - 1) * p;
            int low = (int) index;
            return sorted[low] + (sorted[(int) Math.ceil(index)] - sorted[low]) * (index - low);
        }
    }

    /** One grouped row. Algorithm and execution mode are always included. */
    public record Summary(String group, int runs, long distinctMaps, int solved,
            Stats runtime, Stats nodes, Stats cost, Stats excessCost, Stats nodeReduction,
            int originalRuns, int excluded, int originalSolved) {
        /** Percentage of runs that found a solution, including failures in the denominator. */
        public double successPercent() { return 100.0 * solved / runs; }
        /** Success rate of all selected iterations, before outlier exclusions. */
        public double originalSuccessPercent() { return 100.0 * originalSolved / originalRuns; }
    }

    /** Aggregates a filtered selection, using the complete data for paired baselines. */
    public static List<Summary> summarize(List<ResultRecord> selected,
            List<ResultRecord> all, List<ResultQuery.Field> fields) {
        return summarize(selected, all, fields, Outliers.NONE, Metric.RUNTIME_MS);
    }

    /** Reports original/retained counts and statistics after optional within-map trimming. */
    public static List<Summary> summarize(List<ResultRecord> selected,
            List<ResultRecord> all, List<ResultQuery.Field> fields, Outliers outliers, Metric outlierMetric) {
        Prepared preparedAll = prepare(selected, all, outliers, outlierMetric);
        java.util.Set<ResultRecord> retained = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        retained.addAll(preparedAll.rows());
        Map<List<Object>, List<ResultRecord>> groups = new LinkedHashMap<>();
        for (ResultRecord r : selected) {
            List<Object> key = new ArrayList<>();
            for (ResultQuery.Field field : fields) { key.add(field.value(r)); }
            if (!fields.contains(ResultQuery.Field.ALGORITHM)) { key.add(r.result().getAlgorithm().name()); }
            if (!fields.contains(ResultQuery.Field.EXECUTION)) { key.add(r.execution()); }
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(r);
        }
        Baselines baselines = new Baselines(all);
        List<Summary> output = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            List<ResultRecord> rows = entry.getValue().stream().filter(retained::contains).toList();
            String group = entry.getKey().stream().map(value -> value == null ? "N/A" : value.toString())
                    .collect(java.util.stream.Collectors.joining(" / "));
            long maps = rows.stream().anyMatch(r -> r.mapId().isBlank()) ? -1
                    : rows.stream().map(ResultRecord::mapId).distinct().count();
            output.add(new Summary(group, rows.size(), maps,
                    (int) rows.stream().filter(r -> r.result().isSolved()).count(),
                    stats(rows, Metric.RUNTIME_MS, baselines), stats(rows, Metric.NODES, baselines),
                    stats(rows, Metric.COST, baselines), stats(rows, Metric.EXCESS_COST, baselines),
                    stats(rows, Metric.NODE_REDUCTION, baselines), entry.getValue().size(), entry.getValue().size() - rows.size(),
                    (int) entry.getValue().stream().filter(r -> r.result().isSolved()).count()));
        }
        return List.copyOf(output);
    }

    /** Computes one metric for a collection; missing values do not enter averages. */
    public static Stats stats(List<ResultRecord> rows, Metric metric, Baselines baselines) {
        return Stats.of(rows.stream().map(r -> metric.value(r, baselines)).toList());
    }

    /** Gives each map/algorithm/execution group one mean value rather than weighting
     * maps with more timing repetitions more heavily. Unknown identities stay separate.
     */
    public static Stats normalizedStats(List<ResultRecord> rows, Metric metric, Baselines baselines) {
        Map<String, List<Double>> values = new LinkedHashMap<>();
        int unknown = 0;
        for (ResultRecord r : rows) {
            String key = (r.mapId().isBlank() ? "unknown-" + unknown++ : r.mapId())
                    + ":" + r.result().getAlgorithm() + ":" + r.execution();
            Double value = metric.value(r, baselines);
            if (value != null) { values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value); }
        }
        return Stats.of(values.values().stream().map(v -> Stats.of(v).mean()).toList());
    }
}
