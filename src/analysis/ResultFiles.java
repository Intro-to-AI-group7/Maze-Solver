package analysis;

import experiments.ExperimentResult;
import experiments.TestSuite;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import maze.search.MazeSolver;

/** UTF-8 CSV persistence for reproducible analysis and report tables. */
public final class ResultFiles {
    private ResultFiles() { }
    private static final List<String> HEADER = List.of("Suite", "Configuration", "Iteration", "Map Source",
            "Seed", "Rows", "Columns", "Requested Blocked %", "Actual Blocked %", "Blocked Cells",
            "Reachability", "Generated Solvable", "Cost Mode", "Minimum Cost", "Maximum Cost", "Start Row",
            "Start Column", "Goal Row", "Goal Column", "Requested Manhattan Distance", "Actual Manhattan Distance",
            "Algorithm", "Solved", "Path Length", "Path Cost", "Nodes Explored", "Runtime (ns)", "Runtime (ms)",
            "Analysis Version", "Map Identity", "Execution Mode", "Symmetric Costs", "Endpoint Mode", "Heuristic");

    /** Writes all supplied rows, in their current order, including analysis provenance. */
    public static void save(List<ResultRecord> records, Path file) throws IOException {
        List<List<?>> rows = new ArrayList<>();
        rows.add(HEADER);
        for (ResultRecord record : records) {
            ExperimentResult r = record.result();
            rows.add(List.of(r.getSuiteName(), r.getConfigurationId(), r.getIteration(), r.getMapSource(),
                    r.getMapSource() == TestSuite.MapSource.SAVED_MAP
                            ? (r.getSavedMapName() == null ? "" : r.getSavedMapName()) : r.getEffectiveSeed(),
                    r.getRows(), r.getColumns(), r.getRequestedBlockedPercent(), r.getActualBlockedPercent(),
                    r.getBlockedCellCount(), r.getReachabilityMode(), r.isGeneratedMapSolvable(), r.getCostMode(),
                    r.getMinimumCost(), r.getMaximumCost(), r.getStartRow(), r.getStartColumn(), r.getGoalRow(),
                    r.getGoalColumn(), r.getRequestedManhattanDistance(), r.getActualManhattanDistance(),
                    r.getAlgorithm(), r.isSolved(), r.getPathLength(), r.getPathCost(), r.getNodesExplored(),
                    r.getRuntimeNs(), r.getRuntimeMs(), "1", record.mapId(), record.execution(),
                    record.symmetric(), record.endpoint(), record.heuristic()));
        }
        writeCsv(rows, file);
    }

    /** Loads analysis CSV or legacy application CSV; legacy provenance stays unknown. */
    public static List<ResultRecord> load(Path file) throws IOException {
        List<List<String>> rows = parseCsv(Files.readString(file, StandardCharsets.UTF_8));
        if (rows.isEmpty()) { throw new IOException("Results CSV has no header."); }
        Map<String, Integer> columns = new HashMap<>();
        for (int index = 0; index < rows.get(0).size(); index++) {
            String name = rows.get(0).get(index).replace("\uFEFF", "");
            if (columns.put(name, index) != null) { throw new IOException("Duplicate CSV column: " + name); }
        }
        for (String required : HEADER.subList(0, 28)) {
            if (!columns.containsKey(required)) { throw new IOException("Missing CSV column: " + required); }
        }
        List<ResultRecord> output = new ArrayList<>();
        for (int index = 1; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (row.size() != rows.get(0).size()) { throw new IOException("CSV field count differs at record " + (index + 1)); }
            try {
                String version = text(row, columns, "Analysis Version", "");
                if (!version.isEmpty() && !version.equals("1")) { throw new IllegalArgumentException("Unsupported analysis version."); }
                TestSuite.MapSource source = TestSuite.MapSource.valueOf(text(row, columns, "Map Source"));
                String seed = text(row, columns, "Seed");
                ExperimentResult r = new ExperimentResult(text(row, columns, "Suite"), text(row, columns, "Configuration"),
                        integer(row, columns, "Iteration"), source == TestSuite.MapSource.SAVED_MAP ? 0 : Long.parseLong(seed),
                        source, source == TestSuite.MapSource.SAVED_MAP ? seed : null,
                        integer(row, columns, "Rows"), integer(row, columns, "Columns"),
                        decimal(row, columns, "Requested Blocked %"), decimal(row, columns, "Actual Blocked %"),
                        integer(row, columns, "Blocked Cells"), TestSuite.ReachabilityMode.valueOf(text(row, columns, "Reachability")),
                        bool(row, columns, "Generated Solvable"), TestSuite.CostMode.valueOf(text(row, columns, "Cost Mode")),
                        integer(row, columns, "Minimum Cost"), integer(row, columns, "Maximum Cost"),
                        integer(row, columns, "Start Row"), integer(row, columns, "Start Column"),
                        integer(row, columns, "Goal Row"), integer(row, columns, "Goal Column"),
                        integer(row, columns, "Requested Manhattan Distance"), integer(row, columns, "Actual Manhattan Distance"),
                        MazeSolver.Algorithm.valueOf(text(row, columns, "Algorithm")), bool(row, columns, "Solved"),
                        integer(row, columns, "Path Length"), Long.parseLong(text(row, columns, "Path Cost")),
                        integer(row, columns, "Nodes Explored"), Long.parseLong(text(row, columns, "Runtime (ns)")));
                output.add(new ResultRecord(r, text(row, columns, "Map Identity", ""),
                        text(row, columns, "Execution Mode", "UNKNOWN"), text(row, columns, "Symmetric Costs", "UNKNOWN"),
                        text(row, columns, "Endpoint Mode", "UNKNOWN"), text(row, columns, "Heuristic", "UNKNOWN")));
            } catch (IllegalArgumentException ex) {
                throw new IOException("Invalid results CSV record " + (index + 1) + ": " + ex.getMessage(), ex);
            }
        }
        return List.copyOf(output);
    }

    /** Writes the grouped statistics used by the desktop summary view. */
    public static void saveSummary(List<ResultStatistics.Summary> summaries, Path file) throws IOException {
        List<List<?>> rows = new ArrayList<>();
        rows.add(List.of("Group (includes execution mode)", "Original Runs", "Excluded", "Retained Runs", "Distinct Maps", "Solved", "Success % (all iterations)", "Success % (retained)",
                "Runtime Mean (ms)", "Runtime Median (ms)", "Runtime Sample SD (ms)", "Runtime Min (ms)", "Runtime Max (ms)",
                "Nodes Mean", "Nodes Median", "Cost Samples", "Cost Mean", "Cost Median", "UCS Pairs", "Excess Cost Mean %", "Nodes Saved Mean %"));
        for (var s : summaries) {
            rows.add(List.of(s.group(), s.originalRuns(), s.excluded(), s.runs(), s.distinctMaps() < 0 ? "UNKNOWN" : s.distinctMaps(), s.solved(),
                    available(s.originalSuccessPercent()), available(s.successPercent()), available(s.runtime().mean()), available(s.runtime().median()),
                    available(s.runtime().stddev()), available(s.runtime().min()), available(s.runtime().max()),
                    available(s.nodes().mean()), available(s.nodes().median()), s.cost().count(), available(s.cost().mean()), available(s.cost().median()), s.excessCost().count(),
                    available(s.excessCost().mean()), available(s.nodeReduction().mean())));
        }
        writeCsv(rows, file);
    }
    /** Formats unavailable numeric measurements for CSV and the UI. */
    public static Object available(double value) { return Double.isFinite(value) ? value : "N/A"; }
    private static String text(List<String> row, Map<String, Integer> columns, String name) {
        return row.get(columns.get(name));
    }
    private static String text(List<String> row, Map<String, Integer> columns, String name, String fallback) {
        return columns.containsKey(name) ? text(row, columns, name) : fallback;
    }
    private static int integer(List<String> row, Map<String, Integer> columns, String name) {
        return Integer.parseInt(text(row, columns, name));
    }
    private static double decimal(List<String> row, Map<String, Integer> columns, String name) {
        return Double.parseDouble(text(row, columns, name));
    }
    private static boolean bool(List<String> row, Map<String, Integer> columns, String name) {
        String value = text(row, columns, name);
        if (!value.equals("true") && !value.equals("false")) { throw new IllegalArgumentException("Invalid boolean: " + name); }
        return Boolean.parseBoolean(value);
    }
    private static void writeCsv(List<List<?>> rows, Path file) throws IOException {
        StringBuilder output = new StringBuilder();
        for (List<?> row : rows) {
            for (int index = 0; index < row.size(); index++) {
                if (index > 0) { output.append(','); }
                output.append('"').append(row.get(index).toString().replace("\"", "\"\"")).append('"');
            }
            output.append("\r\n");
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, output, StandardCharsets.UTF_8);
    }
    private static List<List<String>> parseCsv(String text) throws IOException {
        if (text.startsWith("\uFEFF")) { text = text.substring(1); }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                else if (c == '"') { quoted = false; closedQuote = true; }
                else { field.append(c); }
            } else if (c == '"' && field.isEmpty() && !closedQuote) { quoted = true; }
            else if (c == ',' || c == '\n' || c == '\r') {
                row.add(field.toString()); field.setLength(0); closedQuote = false;
                if (c != ',') {
                    rows.add(List.copyOf(row)); row.clear();
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') { i++; }
                }
            } else {
                if (closedQuote || c == '"') { throw new IOException("Malformed CSV quoting."); }
                field.append(c);
            }
        }
        if (quoted) { throw new IOException("Unterminated CSV quote."); }
        if (!field.isEmpty() || !row.isEmpty() || closedQuote) { row.add(field.toString()); rows.add(List.copyOf(row)); }
        return rows;
    }
}
