package analysis;

import experiments.ExperimentResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Shared typed filtering and global sorting, independent of table pagination. */
public final class ResultQuery {
    /** Fields available to both the desktop analysis and command line. */
    public enum Field implements TableQuery.Column<ResultRecord> {
        SUITE(false), CONFIGURATION(false), MAP(false), ALGORITHM(false), SOLVED(false),
        ROWS(true), COLUMNS(true), CELLS(true), BLOCKED(true), ACTUAL_BLOCKED(true),
        COST_MODE(false), MIN_COST(true), MAX_COST(true), DISTANCE(true),
        SEED(true), ITERATION(true), RUNTIME_MS(true), COST(true), LENGTH(true),
        NODES(true), SOURCE(false), EXECUTION(false), SYMMETRIC(false), ENDPOINT(false), HEURISTIC(false);

        private final boolean numeric;
        Field(boolean numeric) { this.numeric = numeric; }
        /** Whether this field supports numeric ranges. */
        public boolean isNumeric() { return numeric; }
        /** Reads a typed value; unavailable solution metrics return null. */
        public Object value(ResultRecord record) {
            ExperimentResult r = record.result();
            return switch (this) {
                case SUITE -> r.getSuiteName();
                case CONFIGURATION -> r.getConfigurationId();
                case MAP -> record.mapId().isBlank() ? "UNKNOWN" : record.mapId();
                case ALGORITHM -> r.getAlgorithm().name();
                case SOLVED -> Boolean.toString(r.isSolved());
                case ROWS -> r.getRows();
                case COLUMNS -> r.getColumns();
                case CELLS -> (long) r.getRows() * r.getColumns();
                case BLOCKED -> r.getRequestedBlockedPercent();
                case ACTUAL_BLOCKED -> r.getActualBlockedPercent();
                case COST_MODE -> r.getCostMode().name();
                case MIN_COST -> r.getMinimumCost();
                case MAX_COST -> r.getMaximumCost();
                case DISTANCE -> r.getActualManhattanDistance();
                case SEED -> r.getMapSource() == experiments.TestSuite.MapSource.SAVED_MAP
                        ? null : r.getEffectiveSeed();
                case ITERATION -> r.getIteration();
                case RUNTIME_MS -> r.getRuntimeMs();
                case COST -> r.isSolved() ? r.getPathCost() : null;
                case LENGTH -> r.isSolved() ? r.getPathLength() : null;
                case NODES -> r.getNodesExplored();
                case SOURCE -> r.getMapSource().name();
                case EXECUTION -> record.execution();
                case SYMMETRIC -> record.symmetric();
                case ENDPOINT -> record.endpoint();
                case HEURISTIC -> record.heuristic();
            };
        }
        /** Parses a field name, case-insensitively. */
        public static Field parse(String name) {
            if (name == null) { throw new IllegalArgumentException("Missing analysis field name."); }
            try {
                return valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Unknown analysis field: " + name, ex);
            }
        }
    }

    private final TableQuery<ResultRecord, Field> query;

    /** Parses semicolon-separated AND rules; pipe-separated values mean OR. */
    public ResultQuery(String filters, String sorting) {
        query = new TableQuery<>(filters, sorting, Field::parse);
    }

    /** Tests one observation against all filters. */
    public boolean matches(ResultRecord record) { return query.matches(record); }
    /** Immutable sorted selection over the entire source, before pagination. */
    public List<ResultRecord> apply(List<ResultRecord> source) {
        List<ResultRecord> selected = new ArrayList<>();
        for (ResultRecord record : source) {
            if (Thread.currentThread().isInterrupted()) { throw new java.util.concurrent.CancellationException(); }
            if (matches(record)) { selected.add(record); }
        }
        if (!query.sorts().isEmpty()) { selected.sort(comparator()); }
        return List.copyOf(selected);
    }
    /** Sorts nulls last in either direction; equal rows retain arrival order. */
    public Comparator<ResultRecord> comparator() {
        var sorts = query.sorts();
        return (first, second) -> {
            for (var sort : sorts) {
                Object a = sort.field().value(first);
                Object b = sort.field().value(second);
                if (a == null || b == null) {
                    if (a != b) { return a == null ? 1 : -1; }
                    continue;
                }
                int result = compareSortValues(a, b);
                if (result != 0) { return sort.descending() ? -result : result; }
            }
            return 0;
        };
    }

    private static int compareSortValues(Object first, Object second) {
        // Each field returns a consistent numeric type. Keep integer seeds and
        // path costs exact, including values beyond double's integer precision.
        if (first instanceof Double a && second instanceof Double b) {
            return Double.compare(a, b);
        }
        if (first instanceof Number a && second instanceof Number b) {
            return Long.compare(a.longValue(), b.longValue());
        }
        return first.toString().compareToIgnoreCase(second.toString());
    }
}
