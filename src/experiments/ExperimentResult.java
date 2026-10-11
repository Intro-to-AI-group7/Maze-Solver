package experiments;

import maze.search.MazeSolver;
import maze.map.Maze;

/**
 * Immutable measurements from one algorithm run on one generated map.
 */
public final class ExperimentResult {
    private final String suiteName;
    private final String configurationId;
    private final int iteration;
    private final long effectiveSeed;
    private final TestSuite.MapSource mapSource;
    private final String savedMapName;
    private final int rows;
    private final int columns;
    private final double requestedBlockedPercent;
    private final double actualBlockedPercent;
    private final int blockedCellCount;
    private final TestSuite.ReachabilityMode reachabilityMode;
    private final boolean generatedMapSolvable;
    private final TestSuite.CostMode costMode;
    private final int minimumCost;
    private final int maximumCost;
    private final int startRow;
    private final int startColumn;
    private final int goalRow;
    private final int goalColumn;
    private final int requestedManhattanDistance;
    private final int actualManhattanDistance;
    private final MazeSolver.Algorithm algorithm;
    private final boolean solved;
    private final int pathLength;
    private final long pathCost;
    private final int nodesExplored;
    private final long runtimeNs;

    /**
     * Creates a result and validates fields that must never be negative.
     *
     * @param suiteName suite that produced the result
     * @param configurationId test-row identifier
     * @param iteration one-based iteration number
     * @param effectiveSeed seed used for this generated map
     * @param mapSource source used to obtain the map
     * @param savedMapName saved-map filename, or null for generated maps
     * @param rows total map row count
     * @param columns total map column count
     * @param requestedBlockedPercent requested interior blocked percentage
     * @param actualBlockedPercent actual interior blocked percentage
     * @param blockedCellCount actual blocked interior-cell count
     * @param reachabilityMode configured reachability policy
     * @param generatedMapSolvable whether the generated map has a route
     * @param costMode configured movement-cost mode
     * @param minimumCost inclusive configured minimum movement cost
     * @param maximumCost inclusive configured maximum movement cost
     * @param startRow start row used by the solvers
     * @param startColumn start column used by the solvers
     * @param goalRow goal row used by the solvers
     * @param goalColumn goal column used by the solvers
     * @param requestedManhattanDistance requested endpoint distance, or -1 when not applicable
     * @param actualManhattanDistance actual endpoint Manhattan distance
     * @param algorithm algorithm measured by this result
     * @param solved whether the algorithm found a route
     * @param pathLength number of moves in the returned route, or -1 when unsolved
     * @param pathCost total route cost, or -1 when unsolved
     * @param nodesExplored number of finalized/visited nodes
     * @param runtimeNs solver-only runtime in nanoseconds
     * @throws IllegalArgumentException if required text, modes, ranges, or metrics are invalid
     */
    public ExperimentResult(
            String suiteName,
            String configurationId,
            int iteration,
            long effectiveSeed,
            TestSuite.MapSource mapSource,
            String savedMapName,
            int rows,
            int columns,
            double requestedBlockedPercent,
            double actualBlockedPercent,
            int blockedCellCount,
            TestSuite.ReachabilityMode reachabilityMode,
            boolean generatedMapSolvable,
            TestSuite.CostMode costMode,
            int minimumCost,
            int maximumCost,
            int startRow,
            int startColumn,
            int goalRow,
            int goalColumn,
            int requestedManhattanDistance,
            int actualManhattanDistance,
            MazeSolver.Algorithm algorithm,
            boolean solved,
            int pathLength,
            long pathCost,
            int nodesExplored,
            long runtimeNs) {
        this.suiteName = requireText(suiteName, "Suite name");
        this.configurationId = requireText(configurationId, "Configuration id");
        if (iteration < 1) {
            throw new IllegalArgumentException("Iteration must be positive.");
        }
        if (rows < 1 || columns < 1) {
            throw new IllegalArgumentException("Rows and columns must be positive.");
        }
        if (Double.isNaN(requestedBlockedPercent) || Double.isInfinite(requestedBlockedPercent)
                || requestedBlockedPercent < 0.0 || requestedBlockedPercent > 100.0
                || Double.isNaN(actualBlockedPercent) || Double.isInfinite(actualBlockedPercent)
                || actualBlockedPercent < 0.0 || actualBlockedPercent > 100.0) {
            throw new IllegalArgumentException("Blocked percentages must be between 0 and 100.");
        }
        if (blockedCellCount < 0 || actualManhattanDistance < 0
                || (requestedManhattanDistance < -1)) {
            throw new IllegalArgumentException("Result counts and distances cannot be negative.");
        }
        if (mapSource == null || reachabilityMode == null || costMode == null
                || algorithm == null) {
            throw new IllegalArgumentException("Result mode and algorithm values are required.");
        }
        if (minimumCost < Maze.MIN_MOVEMENT_COST
                || maximumCost > Maze.MAX_MOVEMENT_COST
                || minimumCost > maximumCost) {
            throw new IllegalArgumentException("Result cost range is invalid.");
        }
        if (pathLength < -1 || pathCost < -1 || nodesExplored < 0 || runtimeNs < 0) {
            throw new IllegalArgumentException("Result metrics cannot be negative.");
        }
        this.iteration = iteration;
        this.effectiveSeed = effectiveSeed;
        this.mapSource = mapSource;
        this.savedMapName = savedMapName;
        this.rows = rows;
        this.columns = columns;
        this.requestedBlockedPercent = requestedBlockedPercent;
        this.actualBlockedPercent = actualBlockedPercent;
        this.blockedCellCount = blockedCellCount;
        this.reachabilityMode = reachabilityMode;
        this.generatedMapSolvable = generatedMapSolvable;
        this.costMode = costMode;
        this.minimumCost = minimumCost;
        this.maximumCost = maximumCost;
        this.startRow = startRow;
        this.startColumn = startColumn;
        this.goalRow = goalRow;
        this.goalColumn = goalColumn;
        this.requestedManhattanDistance = requestedManhattanDistance;
        this.actualManhattanDistance = actualManhattanDistance;
        this.algorithm = algorithm;
        this.solved = solved;
        this.pathLength = pathLength;
        this.pathCost = pathCost;
        this.nodesExplored = nodesExplored;
        this.runtimeNs = runtimeNs;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " cannot be blank.");
        }
        return value.trim();
    }

    /** Gets the suite name.
     * @return suite name
     */
    public String getSuiteName() { return suiteName; }
    /** Gets the test-row identifier.
     * @return test-row identifier
     */
    public String getConfigurationId() { return configurationId; }
    /** Gets the one-based iteration number.
     * @return one-based iteration number
     */
    public int getIteration() { return iteration; }
    /** Gets the effective map-generation seed.
     * @return effective map-generation seed
     */
    public long getEffectiveSeed() { return effectiveSeed; }
    /** Gets the map source used for this result.
     * @return map source
     */
    public TestSuite.MapSource getMapSource() { return mapSource; }
    /** Gets the embedded saved-map filename.
     * @return filename, or null when unavailable
     */
    public String getSavedMapName() { return savedMapName; }
    /** Gets a display value for the seed column.
     * @return seed value, or {@code N/A} for an embedded saved map
     */
    public String getSeedLabel() {
        return mapSource == TestSuite.MapSource.SAVED_MAP
                ? (savedMapName == null || savedMapName.isEmpty()
                        ? "N/A (saved map)" : savedMapName)
                : Long.toString(effectiveSeed);
    }
    /** Gets the total map row count.
     * @return total map row count
     */
    public int getRows() { return rows; }
    /** Gets the total map column count.
     * @return total map column count
     */
    public int getColumns() { return columns; }
    /** Gets the requested interior blocked percentage.
     * @return requested interior blocked percentage
     */
    public double getRequestedBlockedPercent() { return requestedBlockedPercent; }
    /** Gets the actual interior blocked percentage.
     * @return actual interior blocked percentage
     */
    public double getActualBlockedPercent() { return actualBlockedPercent; }
    /** Gets the actual blocked interior-cell count.
     * @return actual blocked interior-cell count
     */
    public int getBlockedCellCount() { return blockedCellCount; }
    /** Gets the configured reachability policy.
     * @return configured reachability policy
     */
    public TestSuite.ReachabilityMode getReachabilityMode() { return reachabilityMode; }
    /** Indicates whether the generated map contains a route.
     * @return true when the generated map contains a route
     */
    public boolean isGeneratedMapSolvable() { return generatedMapSolvable; }
    /** Gets the configured movement-cost mode.
     * @return configured movement-cost mode
     */
    public TestSuite.CostMode getCostMode() { return costMode; }
    /** Gets the inclusive minimum movement cost.
     * @return inclusive minimum movement cost
     */
    public int getMinimumCost() { return minimumCost; }
    /** Gets the inclusive maximum movement cost.
     * @return inclusive maximum movement cost
     */
    public int getMaximumCost() { return maximumCost; }
    /** Gets the start row.
     * @return start row
     */
    public int getStartRow() { return startRow; }
    /** Gets the start column.
     * @return start column
     */
    public int getStartColumn() { return startColumn; }
    /** Gets the goal row.
     * @return goal row
     */
    public int getGoalRow() { return goalRow; }
    /** Gets the goal column
     * @return goal column
     */
    public int getGoalColumn() { return goalColumn; }
    /** Gets the requested Manhattan distance.
     * @return requested distance, or -1 when not applicable
     */
    public int getRequestedManhattanDistance() { return requestedManhattanDistance; }
    /** Gets the actual endpoint Manhattan distance.
     * @return actual endpoint Manhattan distance
     */
    public int getActualManhattanDistance() { return actualManhattanDistance; }
    /** Gets the measured algorithm.
     * @return measured algorithm
     */
    public MazeSolver.Algorithm getAlgorithm() { return algorithm; }
    /** Indicates whether the algorithm found a route.
     * @return true when the algorithm found a route
     */
    public boolean isSolved() { return solved; }
    /** Gets the route length.
     * @return route length in moves, or -1 when unsolved
     */
    public int getPathLength() { return pathLength; }
    /** Gets the total route cost.
     * @return total route cost, or -1 when unsolved
     */
    public long getPathCost() { return pathCost; }
    /** Gets the number of finalized or visited nodes.
     * @return number of finalized or visited nodes
     */
    public int getNodesExplored() { return nodesExplored; }
    /** Gets solver-only runtime in nanoseconds.
     * @return runtime in nanoseconds
     */
    public long getRuntimeNs() { return runtimeNs; }

    /**
     * Gets runtime in milliseconds for display and CSV convenience.
     *
     * @return runtime in milliseconds
     */
    public double getRuntimeMs() {
        return runtimeNs / 1_000_000.0;
    }
}
