package analysis;

import experiments.ExperimentResult;
import experiments.TestSuite;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** One measurement with immutable provenance for matched-map comparisons. */
public record ResultRecord(ExperimentResult result, String mapId, String execution,
        String symmetric, String endpoint, String heuristic) {
    /** Creates a record; blank map identities intentionally cannot be paired. */
    public ResultRecord {
        java.util.Objects.requireNonNull(result);
        java.util.Objects.requireNonNull(mapId);
        java.util.Objects.requireNonNull(execution);
        java.util.Objects.requireNonNull(symmetric);
        java.util.Objects.requireNonNull(endpoint);
        java.util.Objects.requireNonNull(heuristic);
    }

    /** Captures generation settings before execution, independent of later UI edits. */
    public static final class Factory {
        private final Map<String, Settings> settings = new HashMap<>();
        private final String execution;

        /** Snapshots the suite's settings without changing automatic seeds. */
        public Factory(TestSuite suite) {
            execution = suite.getExecutionMode().name();
            for (TestSuite.TestCase row : suite.getTestCases()) {
                String signature = row.getMapSource() == TestSuite.MapSource.SAVED_MAP
                        ? "saved:" + hash(row.getSavedMapText().replace("\r\n", "\n"))
                        : String.join(":", "generator-v1", "" + row.getRows(),
                                "" + row.getColumns(), "" + row.getBlockedPercent(),
                                row.getReachabilityMode().name(), row.getCostMode().name(),
                                "" + row.getMinimumCost(), "" + row.getMaximumCost(),
                                "" + row.isSameCostBothDirections(), row.getEndpointMode().name(),
                                "" + row.getStartRow(), "" + row.getStartColumn(),
                                "" + row.getGoalRow(), "" + row.getGoalColumn(),
                                "" + row.getTargetManhattanDistance(), "" + row.getDistanceTolerance());
                settings.put(row.getId(), new Settings(signature,
                        row.getMapSource() == TestSuite.MapSource.SAVED_MAP ? "FROM_MAP"
                                : Boolean.toString(row.isSameCostBothDirections()),
                        row.getMapSource() == TestSuite.MapSource.SAVED_MAP ? "FROM_MAP"
                                : row.getEndpointMode().name()));
            }
        }

        /** Attaches the actual effective seed and the captured settings. */
        public ResultRecord create(ExperimentResult result) {
            Settings row = settings.get(result.getConfigurationId());
            if (row == null) {
                throw new IllegalArgumentException("Missing configuration: " + result.getConfigurationId());
            }
            String identity = hash(row.signature + (result.getMapSource() == TestSuite.MapSource.SAVED_MAP
                    ? "" : ":" + result.getEffectiveSeed()));
            String heuristic = switch (result.getAlgorithm()) {
                case A_STAR, GREEDY -> "MANHATTAN";
                default -> "NONE";
            };
            return new ResultRecord(result, identity, execution, row.symmetric, row.endpoint, heuristic);
        }

        private record Settings(String signature, String symmetric, String endpoint) { }
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
