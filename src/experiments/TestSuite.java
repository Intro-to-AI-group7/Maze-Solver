package experiments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import maze.search.MazeSolver;
import maze.map.Maze;

/**
 * Configuration model for repeatable maze-solving experiments.
 *
 * <p>A suite contains ordered test rows. Each row describes one condition and
 * uses the same iteration count for every selected algorithm. Generation and
 * execution are intentionally handled by {@code ExperimentRunner}, not by
 * this configuration class.
 */
public final class TestSuite {

    /** Source used to obtain the map for an experiment row. */
    public enum MapSource {
        /** Generate one random seed for the test-case execution or save. */
        AUTOMATIC_SEED,
        /** Generate maps from the explicitly entered seed. */
        MANUAL_SEED,
        /** Reuse the embedded serialized map. */
        SAVED_MAP
    }

    /** Movement-cost distribution used by an experiment row. */
    public enum CostMode {
        /** Assigns the same unit cost to every enabled movement. */
        UNIFORM,
        /** Assigns seeded positive costs within the configured range. */
        RANDOM
    }

    /** Whether generated maps must contain a route between their endpoints. */
    public enum ReachabilityMode {
        /** Regenerates or repairs maps until a route exists. */
        GUARANTEED_SOLVABLE,
        /** Intentionally generates a map with no route between endpoints. */
        GUARANTEED_UNSOLVABLE,
        /** Leaves connectivity to the seeded random generation process. */
        NATURAL
    }

    /** How an experiment row chooses its start and goal coordinates. */
    public enum EndpointMode {
        /** Uses manually validated start and goal coordinates. */
        FIXED,
        /** Selects endpoints using the iteration's seeded random source. */
        RANDOM,
        /** Selects endpoints near a requested Manhattan distance. */
        APPROXIMATE_DISTANCE
    }

    private String name;
    private final List<TestCase> testCases = new ArrayList<>();
    private boolean automaticExport;
    private String exportDirectory;

    /**
     * Creates an empty suite with default export settings.
     *
     * @param name suite name
     */
    public TestSuite(String name) {
        setName(name);
    }

    /**
     * Gets the suite name.
     *
     * @return suite name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the suite name.
     *
     * @param name non-blank suite name
     * @throws IllegalArgumentException if the name is blank
     */
    public void setName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Suite name cannot be blank.");
        }
        this.name = name.trim();
    }

    /**
     * Adds a validated test case to the suite.
     *
     * @param testCase row to add
     */
    public void addTestCase(TestCase testCase) {
        if (testCase == null) {
            throw new IllegalArgumentException("Test case cannot be null.");
        }
        testCase.validate();
        for (TestCase existing : testCases) {
            if (existing.getId().equals(testCase.getId())) {
                throw new IllegalArgumentException("Duplicate test case id: " + testCase.getId());
            }
        }
        testCases.add(testCase);
    }

    /**
     * Removes a test case by index.
     *
     * @param index zero-based row index
     * @return removed test case
     */
    public TestCase removeTestCase(int index) {
        return testCases.remove(index);
    }

    /**
     * Gets the suite rows as an unmodifiable view.
     *
     * @return ordered test rows
     */
    public List<TestCase> getTestCases() {
        return Collections.unmodifiableList(testCases);
    }

    /**
     * Indicates whether results should be exported automatically after a run.
     *
     * @return true when automatic export is enabled
     */
    public boolean isAutomaticExport() {
        return automaticExport;
    }

    /**
     * Enables or disables automatic result export.
     *
     * @param automaticExport export setting
     */
    public void setAutomaticExport(boolean automaticExport) {
        this.automaticExport = automaticExport;
    }

    /**
     * Gets the configured automatic-export directory.
     *
     * @return directory path, or null when the default should be selected
     */
    public String getExportDirectory() {
        return exportDirectory;
    }

    /**
     * Sets the automatic-export directory.
     *
     * @param exportDirectory directory path, or null to use the default
     */
    public void setExportDirectory(String exportDirectory) {
        if (exportDirectory != null && exportDirectory.trim().isEmpty()) {
            throw new IllegalArgumentException("Export directory cannot be blank.");
        }
        this.exportDirectory = exportDirectory == null ? null : exportDirectory.trim();
    }

    /**
     * Validates the complete suite.
     *
     * @throws IllegalArgumentException if the suite has no valid test rows
     */
    public void validate() {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Suite name cannot be blank.");
        }
        if (testCases.isEmpty()) {
            throw new IllegalArgumentException("Suite must contain at least one test case.");
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < testCases.size(); i++) {
            try {
                if (!ids.add(testCases.get(i).getId())) {
                    throw new IllegalArgumentException(
                            "Duplicate test case id: " + testCases.get(i).getId());
                }
                testCases.get(i).validate();
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Invalid test case " + (i + 1) + ": "
                        + ex.getMessage(), ex);
            }
        }
    }

    /**
     * Describes one row in a test suite.
     *
     * <p>The setters intentionally allow temporary invalid values so a future
     * editor can populate a row before calling {@link #validate()}. A row must
     * be validated before it is added to a suite or executed.
     */
    public static final class TestCase {
        private String id = "test-1";
        private int rows = 20;
        private int columns = 20;
        private double blockedPercent = 25.0;
        private ReachabilityMode reachabilityMode = ReachabilityMode.GUARANTEED_SOLVABLE;
        private CostMode costMode = CostMode.RANDOM;
        private int minimumCost = Maze.MIN_MOVEMENT_COST;
        private int maximumCost = 5;
        private boolean sameCostBothDirections = true;
        private EndpointMode endpointMode = EndpointMode.RANDOM;
        private int startRow = 1;
        private int startColumn = 1;
        private int goalRow = 18;
        private int goalColumn = 18;
        private int targetManhattanDistance = 34;
        private int distanceTolerance = 1;
        private long seed;
        private MapSource mapSource = MapSource.AUTOMATIC_SEED;
        private String savedMapText;
        private String savedMapName;
        private int iterations = 10;
        private final Set<MazeSolver.Algorithm> algorithms =
                new LinkedHashSet<>(EnumSet.of(
                        MazeSolver.Algorithm.UCS,
                        MazeSolver.Algorithm.GREEDY,
                        MazeSolver.Algorithm.A_STAR));

        /**
         * Creates a test case with proposal-oriented defaults.
         */
        public TestCase() {
        }

        /**
         * Creates a test case with an explicit identifier.
         *
         * @param id row identifier
         */
        public TestCase(String id) {
            setId(id);
        }

        /**
         * Returns the stable identifier for this test row.
         *
         * @return stable row identifier
         */
        public String getId() {
            return id;
        }

        /**
         * Sets the stable row identifier.
         *
         * @param id non-blank identifier
         */
        public void setId(String id) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("Test case id cannot be blank.");
            }
            this.id = id.trim();
        }

        /**
         * Returns the total row count, including the blocked outside border.
         *
         * @return total row count
         */
        public int getRows() {
            return rows;
        }

        /**
         * Sets the total row count, including the outside border.
         *
         * @param rows total row count
         */
        public void setRows(int rows) {
            this.rows = rows;
        }

        /**
         * Returns the total column count, including the blocked outside border.
         *
         * @return total column count
         */
        public int getColumns() {
            return columns;
        }

        /**
         * Sets the total column count, including the outside border.
         *
         * @param columns total column count
         */
        public void setColumns(int columns) {
            this.columns = columns;
        }

        /**
         * Returns the requested blocked percentage among interior cells only.
         *
         * @return interior blocked percentage
         */
        public double getBlockedPercent() {
            return blockedPercent;
        }

        /**
         * Sets the requested blocked percentage among interior cells.
         *
         * @param blockedPercent interior blocked percentage
         */
        public void setBlockedPercent(double blockedPercent) {
            this.blockedPercent = blockedPercent;
        }

        /**
         * Returns the required connectivity policy for generated maps.
         *
         * @return connectivity policy
         */
        public ReachabilityMode getReachabilityMode() {
            return reachabilityMode;
        }

        /**
         * Sets the required connectivity policy for generated maps.
         *
         * @param reachabilityMode connectivity policy
         */
        public void setReachabilityMode(ReachabilityMode reachabilityMode) {
            this.reachabilityMode = requireNonNull(reachabilityMode, "Reachability mode");
        }

        /**
         * Returns the movement-cost distribution mode.
         *
         * @return movement-cost mode
         */
        public CostMode getCostMode() {
            return costMode;
        }

        /**
         * Sets the movement-cost distribution mode.
         *
         * @param costMode movement-cost mode
         */
        public void setCostMode(CostMode costMode) {
            this.costMode = requireNonNull(costMode, "Cost mode");
        }

        /**
         * Returns the inclusive minimum generated movement cost.
         *
         * @return minimum movement cost
         */
        public int getMinimumCost() {
            return minimumCost;
        }

        /**
         * Sets the inclusive minimum generated movement cost.
         *
         * @param minimumCost minimum movement cost
         */
        public void setMinimumCost(int minimumCost) {
            this.minimumCost = minimumCost;
        }

        /**
         * Returns the inclusive maximum generated movement cost.
         *
         * @return maximum movement cost
         */
        public int getMaximumCost() {
            return maximumCost;
        }

        /**
         * Sets the inclusive maximum generated movement cost.
         *
         * @param maximumCost maximum movement cost
         */
        public void setMaximumCost(int maximumCost) {
            this.maximumCost = maximumCost;
        }

        /**
         * Returns whether reverse directions share one generated cost.
         *
         * @return whether reverse directions share a cost
         */
        public boolean isSameCostBothDirections() {
            return sameCostBothDirections;
        }

        /**
         * Sets whether reverse directions share one generated cost.
         *
         * @param sameCostBothDirections reverse-direction cost policy
         */
        public void setSameCostBothDirections(boolean sameCostBothDirections) {
            this.sameCostBothDirections = sameCostBothDirections;
        }

        /**
         * Returns the endpoint-selection policy.
         *
         * @return endpoint-selection policy
         */
        public EndpointMode getEndpointMode() {
            return endpointMode;
        }

        /**
         * Sets the endpoint-selection policy.
         *
         * @param endpointMode endpoint-selection policy
         */
        public void setEndpointMode(EndpointMode endpointMode) {
            this.endpointMode = requireNonNull(endpointMode, "Endpoint mode");
        }

        /**
         * Returns the fixed start row when endpoint mode is FIXED.
         *
         * @return fixed start row
         */
        public int getStartRow() {
            return startRow;
        }

        /**
         * Sets the fixed start row.
         *
         * @param startRow fixed start row
         */
        public void setStartRow(int startRow) {
            this.startRow = startRow;
        }

        /**
         * Returns the fixed start column when endpoint mode is FIXED.
         *
         * @return fixed start column
         */
        public int getStartColumn() {
            return startColumn;
        }

        /**
         * Sets the fixed start column.
         *
         * @param startColumn fixed start column
         */
        public void setStartColumn(int startColumn) {
            this.startColumn = startColumn;
        }

        /**
         * Returns the fixed goal row when endpoint mode is FIXED.
         *
         * @return fixed goal row
         */
        public int getGoalRow() {
            return goalRow;
        }

        /**
         * Sets the fixed goal row.
         *
         * @param goalRow fixed goal row
         */
        public void setGoalRow(int goalRow) {
            this.goalRow = goalRow;
        }

        /**
         * Returns the fixed goal column when endpoint mode is FIXED.
         *
         * @return fixed goal column
         */
        public int getGoalColumn() {
            return goalColumn;
        }

        /**
         * Sets the fixed goal column.
         *
         * @param goalColumn fixed goal column
         */
        public void setGoalColumn(int goalColumn) {
            this.goalColumn = goalColumn;
        }

        /**
         * Returns the requested Manhattan distance for approximate endpoints.
         *
         * @return target Manhattan distance
         */
        public int getTargetManhattanDistance() {
            return targetManhattanDistance;
        }

        /**
         * Sets the requested Manhattan distance for approximate endpoints.
         *
         * @param targetManhattanDistance target Manhattan distance
         */
        public void setTargetManhattanDistance(int targetManhattanDistance) {
            this.targetManhattanDistance = targetManhattanDistance;
        }

        /**
         * Returns the allowed absolute distance error.
         *
         * @return distance tolerance
         */
        public int getDistanceTolerance() {
            return distanceTolerance;
        }

        /**
         * Sets the allowed absolute distance error.
         *
         * @param distanceTolerance distance tolerance
         */
        public void setDistanceTolerance(int distanceTolerance) {
            this.distanceTolerance = distanceTolerance;
        }

        /**
         * Returns the persisted base seed.
         *
         * @return base seed
         */
        public long getSeed() {
            return seed;
        }

        /**
         * Sets an explicit seed and switches the row to manual seed mode.
         *
         * @param seed base seed for deterministic generation
         */
        public void setSeed(long seed) {
            this.seed = seed;
            this.mapSource = MapSource.MANUAL_SEED;
        }

        /**
         * Returns whether the seed may be generated by the suite editor.
         *
         * @return whether automatic seed selection is enabled
         */
        public boolean isAutomaticSeed() {
            return mapSource == MapSource.AUTOMATIC_SEED;
        }

        /**
         * Sets whether the row uses automatic seed selection.
         *
         * @param automaticSeed automatic seed setting
         */
        public void setAutomaticSeed(boolean automaticSeed) {
            this.mapSource = automaticSeed ? MapSource.AUTOMATIC_SEED : MapSource.MANUAL_SEED;
        }

        /**
         * Returns the selected map source.
         *
         * @return selected map source
         */
        public MapSource getMapSource() {
            return mapSource;
        }

        /**
         * Sets the selected map source.
         *
         * @param mapSource selected map source
         */
        public void setMapSource(MapSource mapSource) {
            if (mapSource == null) {
                throw new IllegalArgumentException("Map source cannot be null.");
            }
            this.mapSource = mapSource;
        }

        /**
         * Returns the embedded saved map text.
         *
         * @return saved map text, or null when not selected
         */
        public String getSavedMapText() {
            return savedMapText;
        }

        /**
         * Returns the imported saved-map filename.
         *
         * @return saved-map filename, or null when unavailable
         */
        public String getSavedMapName() {
            return savedMapName;
        }

        /**
         * Sets the complete embedded saved-map contents.
         *
         * @param savedMapText complete map file contents
         */
        public void setSavedMapText(String savedMapText) {
            if (savedMapText == null || savedMapText.trim().isEmpty()) {
                throw new IllegalArgumentException("Saved map contents cannot be blank.");
            }
            this.savedMapText = savedMapText;
            this.mapSource = MapSource.SAVED_MAP;
        }

        /**
         * Sets the imported saved-map filename.
         *
         * @param savedMapName imported saved-map filename
         */
        public void setSavedMapName(String savedMapName) {
            if (savedMapName != null && savedMapName.trim().isEmpty()) {
                throw new IllegalArgumentException("Saved map name cannot be blank.");
            }
            this.savedMapName = savedMapName == null ? null : savedMapName.trim();
        }

        /**
         * Returns the shared iteration count for all selected algorithms.
         *
         * @return iteration count
         */
        public int getIterations() {
            return iterations;
        }

        /**
         * Sets the shared iteration count for all selected algorithms.
         *
         * @param iterations iteration count
         */
        public void setIterations(int iterations) {
            this.iterations = iterations;
        }

        /**
         * Replaces the selected algorithms.
         *
         * @param algorithms non-empty algorithm collection
         */
        public void setAlgorithms(Set<MazeSolver.Algorithm> algorithms) {
            if (algorithms == null) {
                throw new IllegalArgumentException("Algorithms cannot be null.");
            }
            this.algorithms.clear();
            this.algorithms.addAll(algorithms);
        }

        /**
         * Gets selected algorithms in insertion order.
         *
         * @return unmodifiable algorithm set
         */
        public Set<MazeSolver.Algorithm> getAlgorithms() {
            return Collections.unmodifiableSet(algorithms);
        }

        /**
         * Validates all values in this row.
         *
         * @throws IllegalArgumentException if a value is invalid
         */
        public void validate() {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("Test case id cannot be blank.");
            }
            if (rows < 3 || columns < 3 || (rows - 2) * (columns - 2) < 2) {
                throw new IllegalArgumentException(
                        "Maze must have at least two interior cells for distinct endpoints.");
            }
            if (Double.isNaN(blockedPercent) || Double.isInfinite(blockedPercent)
                    || blockedPercent < 0.0 || blockedPercent > 100.0) {
                throw new IllegalArgumentException("Blocked percentage must be between 0 and 100.");
            }
            int interiorCells = (rows - 2) * (columns - 2);
            int requestedBlocked = (int) Math.round(interiorCells * blockedPercent / 100.0);
            if (requestedBlocked > interiorCells - 2) {
                throw new IllegalArgumentException(
                        "Blocked percentage must leave at least two interior cells for distinct endpoints.");
            }
            if (reachabilityMode == null) {
                throw new IllegalArgumentException("Reachability mode is required.");
            }
            if (costMode == null) {
                throw new IllegalArgumentException("Cost mode is required.");
            }
            if (minimumCost < Maze.MIN_MOVEMENT_COST
                    || maximumCost > Maze.MAX_MOVEMENT_COST
                    || minimumCost > maximumCost) {
                throw new IllegalArgumentException(
                        "Cost range must satisfy " + Maze.MIN_MOVEMENT_COST + " <= minimum <= maximum <= "
                                + Maze.MAX_MOVEMENT_COST + ".");
            }
            if (costMode == CostMode.UNIFORM && (minimumCost != Maze.MIN_MOVEMENT_COST
                    || maximumCost != Maze.MIN_MOVEMENT_COST)) {
                throw new IllegalArgumentException("Uniform cost mode requires a cost of 1.");
            }
            if (endpointMode == null) {
                throw new IllegalArgumentException("Endpoint mode is required.");
            }
            if (endpointMode == EndpointMode.FIXED) {
                validateInteriorCoordinate(startRow, startColumn, "Start");
                validateInteriorCoordinate(goalRow, goalColumn, "Goal");
                if (startRow == goalRow && startColumn == goalColumn) {
                    throw new IllegalArgumentException("Start and goal must be different.");
                }
                if (mapSource == null) {
                    throw new IllegalArgumentException("Map source is required.");
                }
                if (mapSource == MapSource.SAVED_MAP
                        && (savedMapText == null || savedMapText.trim().isEmpty())) {
                    throw new IllegalArgumentException("Saved-map rows require embedded map contents.");
                }
                if (reachabilityMode == ReachabilityMode.GUARANTEED_UNSOLVABLE
                        && Math.abs(startRow - goalRow) + Math.abs(startColumn - goalColumn) <= 1) {
                    throw new IllegalArgumentException(
                            "Guaranteed-unsolvable fixed endpoints cannot be adjacent.");
                }
            } else if (endpointMode == EndpointMode.APPROXIMATE_DISTANCE) {
                int maximumDistance = (rows - 2) + (columns - 2);
                if (targetManhattanDistance < 1 || targetManhattanDistance > maximumDistance) {
                    throw new IllegalArgumentException(
                            "Target Manhattan distance must be between 1 and " + maximumDistance + ".");
                }
                if (distanceTolerance < 0 || distanceTolerance > maximumDistance) {
                    throw new IllegalArgumentException("Distance tolerance is outside the maze limits.");
                }
            }
            if (iterations < 1) {
                throw new IllegalArgumentException("Iterations must be positive.");
            }
            if (algorithms.isEmpty()) {
                throw new IllegalArgumentException("At least one algorithm must be selected.");
            }
            for (MazeSolver.Algorithm algorithm : algorithms) {
                if (algorithm == null) {
                    throw new IllegalArgumentException("Selected algorithms cannot contain null.");
                }
            }
        }

        private void validateInteriorCoordinate(int row, int column, String label) {
            if (row < 1 || row >= rows - 1 || column < 1 || column >= columns - 1) {
                throw new IllegalArgumentException(label
                        + " coordinates must be inside the maze interior.");
            }
        }

        private static <T> T requireNonNull(T value, String label) {
            if (value == null) {
                throw new IllegalArgumentException(label + " cannot be null.");
            }
            return value;
        }
    }
}
