package maze.map;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import experiments.TestSuite;

/**
 * Core maze model storing occupancy, neighbor links, and start/goal nodes.
 *
 * <p>The maze uses a rectangular grid of {@code Node} objects. Traversable
 * cells contain a Node reference and blocked cells are {@code null}. Neighbor
 * links represent geometric adjacency between traversable cells, while each
 * direction has a movement cost. Enabled movement costs are positive integers
 * from {@link #MIN_MOVEMENT_COST} through {@link #MAX_MOVEMENT_COST}; zero is
 * reserved for disabled or non-existent movement. Direction order is always
 * {@code [NORTH, EAST, SOUTH, WEST]}.
 *
 * <p>Example import format for {@link #importFromFile(String)} is:
 * row count, column count, {@code start: row,col}, {@code end: row,col},
 * then one layout row per maze row using {@code 1} (path) and {@code 0} (wall).
 * An optional {@code WEIGHTS} section may follow the layout. It contains one
 * comma-separated four-value row for every grid coordinate in row-major order.
 * Values use {@code [N,E,S,W]} order; {@code 0} means no permitted movement
 * and positive integer values are movement costs. If the section is absent, adjacent
 * traversable cells default to cost {@code 1}.
 * Example:
 * <pre>{@code
 * 4
 * 5
 * start: 3,0
 * end: 0,4
 * 11111
 * 10001
 * 10101
 * 11111
 * }</pre>
 *
 * <p>Typical implementation flow:
 * <pre>{@code
 * Maze maze = Maze.importFromFile("test/resources/maps/test_346x346.msmap");
 * Maze.NeighborValidationResult vr = maze.validateNeighborConnections(25);
 * if (!vr.isValid()) {
 *     for (String issue : vr.getIssues()) {
 *         System.out.println(issue);
 *     }
 * }
 * maze.exportToFile("out.msmap");
 * }</pre>
 */
public class Maze {
    /** Maximum seeded samples before approximate-distance fallback search. */
    private static final int APPROXIMATE_DISTANCE_RANDOM_SAMPLES = 5000;
    /** Extension used for editable maze map files. */
    public static final String MAP_EXTENSION = ".msmap";

    /** Minimum allowed positive movement cost. */
    public static final int MIN_MOVEMENT_COST = 1;

    /** Maximum allowed movement cost for all maps and generated mazes. */
    public static final int MAX_MOVEMENT_COST = 100;

    /** Cost value reserved for a disabled or non-existent movement direction. */
    public static final int DISABLED_MOVEMENT_COST = 0;

    /**
     * Cardinal directions used for indexing node neighbors.
     */
    public enum Dir {
        /** Direction toward the previous row. */
        NORTH(0),
        /** Direction toward the next column. */
        EAST(1),
        /** Direction toward the next row. */
        SOUTH(2),
        /** Direction toward the previous column. */
        WEST(3);
        private final int idx;

        /**
         * Creates a direction enum value with a fixed neighbor-array index.
         *
         * @param idx index used in a node's neighbor array
         */
        Dir(int idx) {
            this.idx = idx;
        }

        /**
         * Gets the neighbor-array index associated with this direction.
         *
         * @return zero-based index for this direction in Node.neighbors
         */
        public int idx() {
            return idx;
        }
    }

    /**
     * Represents a single cell in the maze grid.
     * A null value in the neighbors array indicates either a wall
     * or an out-of-bounds direction.
     */

    public static class Node {
        /** Zero-based row coordinate. */
        public final int row;
        /** Zero-based column coordinate. */
        public final int col;
        /** Whether this node is the maze goal. */
        public boolean isGoal;
        
        // Neighbor references: [N, E, S, W] (null if wall or out of bounds)
        /** Adjacent nodes in {@code [NORTH, EAST, SOUTH, WEST]} order. */
        public Node[] neighbors;
        // Movement costs: [N, E, S, W] (0 means movement is disabled)
        private int[] moveCosts;
    
        /**
         * Creates a non-goal node at the given position.
         *
         * @param row node row coordinate
         * @param col node column coordinate
         */
        public Node(int row, int col) {
            this(row, col, false);
        }

        /**
         * Creates a node at the given position with an explicit goal flag.
         *
         * @param row node row coordinate
         * @param col node column coordinate
         * @param isGoal true if this node is a goal node, false otherwise
         */
        public Node(int row, int col, boolean isGoal) {
            this.row = row;
            this.col = col;
            this.isGoal = isGoal;
            this.neighbors = new Node[4]; // [N, E, S, W]
            this.moveCosts = new int[4];
        }

        /**
         * Retrieves the neighboring node in the given direction.
         *
         * @param d direction to inspect
         * @return adjacent node, or null if blocked/out of bounds
         */
        public Node getNeighbor(Dir d) { return neighbors[d.idx()]; }

        /**
         * Assigns the neighboring node in the given direction.
         *
         * @param d direction to assign
         * @param n adjacent node to store, or null
         */
        public void setNeighbor(Dir d, Node n) { neighbors[d.idx()] = n; }

        /**
         * Gets the movement cost in a direction.
         *
         * @param d direction to inspect
         * @return zero when movement is disabled, otherwise a cost in the
         *         range {@link Maze#MIN_MOVEMENT_COST}..{@link Maze#MAX_MOVEMENT_COST}
         */
        public int getMoveCost(Dir d) { return moveCosts[d.idx()]; }

        /**
         * Sets the movement cost in a direction.
         *
         * @param d direction to update
         * @param cost zero to disable movement, or a positive integer movement cost
         * @throws IllegalArgumentException if cost is outside 0..MAX_MOVEMENT_COST
         */
        public void setMoveCost(Dir d, int cost) {
            if (cost < DISABLED_MOVEMENT_COST || cost > MAX_MOVEMENT_COST) {
                throw new IllegalArgumentException(
                        "Movement cost must be 0 or an integer from "
                                + MIN_MOVEMENT_COST + " to " + MAX_MOVEMENT_COST + ".");
            }
            moveCosts[d.idx()] = cost;
        }

    }
    

    // Maze grid and dimensions
    private Node[][] grid;
    private int length;
    private int width;
    private Node start;
    private Node goal;
    
    /**
     * Initializes a maze with fixed dimensions.
        * <p>A new maze starts empty: all cells are {@code null} (blocked) until
        * paths are created explicitly via {@link #createCell(int, int, int)} or
        * populated through {@link #importFromFile(String)}.
     *
     * @param length total number of rows in the maze
     * @param width total number of columns in the maze
     */
    public Maze(int length, int width) {
        this.length = length;
        this.width = width;
        this.grid = new Node[length][width];
    }


    /**
     * Marks a cell as occupied and creates a node at that coordinate.
        *
        * <p>Establishes bidirectional neighbor links immediately: this node is linked
     * to existing occupied neighbors, and those neighbors are updated to point back.
     * The {@code direction} parameter is currently reserved and ignored.
     *
     * @param row row index of the cell
     * @param col column index of the cell
     * @param direction reserved parameter for future directional creation logic
     */
    public void createCell(int row, int col, int direction) {
        if (isInBounds(row, col)) {
            Node node = new Node(row, col);
            grid[row][col] = node;
            
            // Link to existing neighbors and update their reverse links
            linkToNeighbor(node, row - 1, col, Dir.NORTH, Dir.SOUTH);
            linkToNeighbor(node, row, col + 1, Dir.EAST, Dir.WEST);
            linkToNeighbor(node, row + 1, col, Dir.SOUTH, Dir.NORTH);
            linkToNeighbor(node, row, col - 1, Dir.WEST, Dir.EAST);
        }
    }

    /**
     * Helper method to establish bidirectional neighbor link.
     * Links the new node in a given direction and updates the neighbor's reverse link.
     *
     * @param newNode the newly created node
     * @param neighborRow row of the potential neighbor
     * @param neighborCol column of the potential neighbor
     * @param forwardDir direction from new node to neighbor
     * @param reverseDir direction from neighbor back to new node
     */
    private void linkToNeighbor(Node newNode, int neighborRow, int neighborCol, Dir forwardDir, Dir reverseDir) {
        if (isInBounds(neighborRow, neighborCol) && grid[neighborRow][neighborCol] != null) {
            Node neighbor = grid[neighborRow][neighborCol];
            newNode.setNeighbor(forwardDir, neighbor);
            newNode.setMoveCost(forwardDir, 1);
            neighbor.setNeighbor(reverseDir, newNode);
            neighbor.setMoveCost(reverseDir, 1);
        }
    }   

    /**
     * Import a maze from text using this format:
     * line 1: row count
     * line 2: column count
     * line 3: start: row,col
     * line 4: end: row,col
     * line 5+: layout rows of 1s and 0s
     * optional: WEIGHTS followed by length * width rows of N,E,S,W costs
    *
    * <p>Cells marked {@code 1} are created through
    * {@link #createCell(int, int, int)} so neighbor links are built incrementally
    * and remain bidirectionally consistent.
    *
    * @param filePath path to the maze text file
    * @return newly created Maze instance populated from file data
    * @throws IOException if file reading fails
    * @throws IllegalArgumentException if file contents are invalid
     */
    public static Maze importFromFile(String filePath) throws IOException {
        return MazeFileIO.importFromFile(filePath);
    }

    /**
     * Imports a maze from the complete text representation.
     *
     * @param contents map file contents
     * @return imported maze
     * @throws IOException if the text cannot be read
     */
    public static Maze importFromText(String contents) throws IOException {
        return MazeFileIO.importFromText(contents);
    }

    /**
     * Builds a random, guaranteed-solvable maze with the given dimensions.
     *
      * <p>The generated maze keeps the outer border blocked and carves interior
      * corridors using a depth-first backtracking strategy to produce a classic
      * maze appearance. For even dimensions, a follow-up pass extends corridors
      * into the final interior row/column so right/bottom edges are not left as
      * persistent solid strips.
     *
     * @param length total number of rows (minimum 3)
     * @param width total number of columns (minimum 3)
     * @return newly generated solvable maze
     * @throws IllegalArgumentException if dimensions are smaller than 3
     */
    public static Maze generateRandomMaze(int length, int width) {
        return generateRandomMaze(length, width, false, 1, 1, true);
    }

    /**
     * Builds a random, guaranteed-solvable maze with optional directional weights.
     *
     * <p>When weighting is enabled, each permitted connection receives a random
     * positive integer cost in the inclusive range {@code [minWeight, maxWeight]}.
     * The five-argument overload uses the same cost in both directions by default.
     *
     * @param length total number of rows (minimum 3)
     * @param width total number of columns (minimum 3)
     * @param weighted whether to assign random movement costs
     * @param minWeight inclusive minimum movement cost
     * @param maxWeight inclusive maximum movement cost
     * @return newly generated solvable maze
     * @throws IllegalArgumentException if dimensions or weight bounds are invalid
     */
    public static Maze generateRandomMaze(
            int length, int width, boolean weighted, int minWeight, int maxWeight) {
        return generateRandomMaze(length, width, weighted, minWeight, maxWeight, true);
    }

    /**
     * Builds a random maze with optional weighted movement and reverse-edge matching.
     *
     * @param length total number of rows (minimum 3)
     * @param width total number of columns (minimum 3)
     * @param weighted whether to assign random movement costs
     * @param minWeight inclusive minimum movement cost
     * @param maxWeight inclusive maximum movement cost
     * @param sameCostBothDirections whether each connection uses one cost in both directions
     * @return newly generated solvable maze
     * @throws IllegalArgumentException if dimensions or weight bounds are invalid
     */
    public static Maze generateRandomMaze(
            int length, int width, boolean weighted, int minWeight, int maxWeight,
            boolean sameCostBothDirections) {
        if (length < 3 || width < 3) {
            throw new IllegalArgumentException("Random maze dimensions must be at least 3x3.");
        }
        if (minWeight < MIN_MOVEMENT_COST
                || maxWeight > MAX_MOVEMENT_COST
                || minWeight > maxWeight) {
            throw new IllegalArgumentException(
                    "Random maze weights must satisfy "
                            + MIN_MOVEMENT_COST + " <= minimum <= maximum <= "
                            + MAX_MOVEMENT_COST + ".");
        }

        final int startRow = 1;
        final int startCol = 1;
        final int goalRow = length - 2;
        final int goalCol = width - 2;

        Random random = new Random();
        boolean[][] open = new boolean[length][width];

        carveTraditionalMaze(open, startRow, startCol, random);
        extendEvenInteriorEdges(open, random);
        connectGoalToMaze(open, goalRow, goalCol, random);

        Maze maze = new Maze(length, width);
        for (int r = 0; r < length; r++) {
            for (int c = 0; c < width; c++) {
                if (open[r][c]) {
                    maze.createCell(r, c, 0);
                }
            }
        }

        maze.setStart(startRow, startCol);
        maze.setGoal(goalRow, goalCol);
        if (weighted) {
            assignRandomMovementCosts(
                    maze, minWeight, maxWeight, sameCostBothDirections, random);
        }
        return maze;
    }

    /**
     * Generates a seeded experiment map from a validated test configuration.
     *
     * <p>The outside border is always blocked and is excluded from the
     * requested density calculation. The generated map uses one effective
     * seed for its layout, endpoint selection, and movement costs. Algorithms
     * should run against the returned maze without regenerating it.
     *
     * @param testCase validated experiment configuration
     * @param effectiveSeed seed resolved for the containing test-case row
     * @return generated map and metadata used to describe the instance
     * @throws IllegalArgumentException if the configuration is invalid or a
     *         valid endpoint pair cannot be selected
     */
    public static GeneratedExperimentMap generateExperimentMap(
            TestSuite.TestCase testCase, long effectiveSeed) {
        if (testCase == null) {
            throw new IllegalArgumentException("Experiment test case cannot be null.");
        }
        testCase.validate();

        Random random = new Random(effectiveSeed);
        int length = testCase.getRows();
        int width = testCase.getColumns();
        final int maxAttempts = 200;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            boolean[][] open = createDensityLayout(testCase, random);
            int[][] endpoints = selectEndpoints(testCase, open, random);
            if (endpoints == null) {
                continue;
            }

            int startRow = endpoints[0][0];
            int startCol = endpoints[0][1];
            int goalRow = endpoints[1][0];
            int goalCol = endpoints[1][1];
            boolean connected = areConnected(open, startRow, startCol, goalRow, goalCol);

            if (testCase.getReachabilityMode() == TestSuite.ReachabilityMode.GUARANTEED_SOLVABLE
                    && !connected) {
                if (attempt == maxAttempts - 1) {
                    carveDirectConnection(open, startRow, startCol, goalRow, goalCol);
                    connected = true;
                } else {
                    continue;
                }
            }

            if (testCase.getReachabilityMode() == TestSuite.ReachabilityMode.GUARANTEED_UNSOLVABLE) {
                isolateGoal(open, goalRow, goalCol, startRow, startCol);
                connected = areConnected(open, startRow, startCol, goalRow, goalCol);
                if (connected) {
                    continue;
                }
            }

            Maze maze = createMazeFromLayout(open);
            maze.setStart(startRow, startCol);
            maze.setGoal(goalRow, goalCol);
            assignExperimentCosts(maze, testCase, random);

            int interiorCells = (length - 2) * (width - 2);
            int blockedCells = countBlockedInterior(open);
            double blockedPercent = 100.0 * blockedCells / interiorCells;
            int actualDistance = Math.abs(startRow - goalRow) + Math.abs(startCol - goalCol);
            return new GeneratedExperimentMap(
                    maze, effectiveSeed, blockedCells, blockedPercent, connected,
                    startRow, startCol, goalRow, goalCol, actualDistance);
        }

        throw new IllegalArgumentException(
                "Unable to generate an experiment map for test case '" + testCase.getId() + "'.");
    }

    private static boolean[][] createDensityLayout(TestSuite.TestCase testCase, Random random) {
        int length = testCase.getRows();
        int width = testCase.getColumns();
        int interiorCells = (length - 2) * (width - 2);
        TestSuite.EndpointMode endpointMode = testCase.getEndpointMode();
        boolean[][] open = new boolean[length][width];
        List<int[]> candidates = new ArrayList<>(interiorCells);

        for (int row = 1; row < length - 1; row++) {
            for (int col = 1; col < width - 1; col++) {
                open[row][col] = true;
                candidates.add(new int[] {row, col});
            }
        }

        if (endpointMode == TestSuite.EndpointMode.FIXED) {
            int startRow = testCase.getStartRow();
            int startColumn = testCase.getStartColumn();
            int goalRow = testCase.getGoalRow();
            int goalColumn = testCase.getGoalColumn();
            candidates.removeIf(coordinate ->
                    (coordinate[0] == startRow && coordinate[1] == startColumn)
                    || (coordinate[0] == goalRow && coordinate[1] == goalColumn));
        }

        Collections.shuffle(candidates, random);
        int requestedBlocked = (int) Math.round(
                interiorCells * testCase.getBlockedPercent() / 100.0);
        for (int index = 0; index < requestedBlocked && index < candidates.size(); index++) {
            int[] coordinate = candidates.get(index);
            open[coordinate[0]][coordinate[1]] = false;
        }
        if (endpointMode == TestSuite.EndpointMode.OPPOSITE_CORNERS) {
            open[1][1] = true;
            open[length - 2][width - 2] = true;
        }
        return open;
    }

    private static int[][] selectEndpoints(
            TestSuite.TestCase testCase, boolean[][] open, Random random) {
        if (testCase.getEndpointMode() == TestSuite.EndpointMode.FIXED) {
            if (testCase.getReachabilityMode()
                    == TestSuite.ReachabilityMode.GUARANTEED_UNSOLVABLE
                    && Math.abs(testCase.getStartRow() - testCase.getGoalRow())
                            + Math.abs(testCase.getStartColumn() - testCase.getGoalColumn()) <= 1) {
                return null;
            }
            return new int[][] {
                    {testCase.getStartRow(), testCase.getStartColumn()},
                    {testCase.getGoalRow(), testCase.getGoalColumn()}
            };
        }

        List<int[]> openCells = getOpenInteriorCells(open);
        if (openCells.size() < 2) {
            return null;
        }
        if (testCase.getEndpointMode() == TestSuite.EndpointMode.RANDOM) {
            int[] start = openCells.get(random.nextInt(openCells.size()));
            List<int[]> reachable = getReachableCells(open, start[0], start[1]);
            if (testCase.getReachabilityMode() == TestSuite.ReachabilityMode.GUARANTEED_SOLVABLE
                    && reachable.size() > 1) {
                int[] goal = reachable.get(random.nextInt(reachable.size()));
                if (sameCoordinate(start, goal)) {
                    goal = reachable.get(1);
                }
                return new int[][] {start, goal};
            }
            int[] goal = openCells.get(random.nextInt(openCells.size()));
            return sameCoordinate(start, goal) ? null : new int[][] {start, goal};
        }

        if (testCase.getEndpointMode() == TestSuite.EndpointMode.OPPOSITE_CORNERS) {
            return new int[][] {
                    {1, 1},
                    {open.length - 2, open[0].length - 2}
            };
        }

        int target = testCase.getTargetManhattanDistance();
        int tolerance = testCase.getDistanceTolerance();
        int[] bestStart = null;
        int[] bestGoal = null;
        int bestDifference = Integer.MAX_VALUE;
        for (int attempt = 0; attempt < APPROXIMATE_DISTANCE_RANDOM_SAMPLES; attempt++) {
            int[] start = openCells.get(random.nextInt(openCells.size()));
            List<int[]> goals = openCells;
            if (goals.size() < 2) {
                continue;
            }
            int[] goal = goals.get(random.nextInt(goals.size()));
            if (sameCoordinate(start, goal)) {
                continue;
            }
            int distance = manhattanDistance(start, goal);
            if (testCase.getReachabilityMode()
                    == TestSuite.ReachabilityMode.GUARANTEED_UNSOLVABLE && distance <= 1) {
                continue;
            }
            int difference = Math.abs(distance - target);
            if (difference < bestDifference) {
                bestStart = start;
                bestGoal = goal;
                bestDifference = difference;
            }
            if (difference <= tolerance) {
                return new int[][] {start, goal};
            }
        }
        if (bestDifference <= tolerance) {
            return new int[][] {bestStart, bestGoal};
        }
        /*
         * Random sampling is normally much faster, but it can miss a valid
         * pair on dense or highly constrained layouts. The deterministic
         * fallback makes endpoint selection depend on the layout rather than
         * on another probabilistic failure.
         */
        return findDistancePair(open, target, tolerance);
    }

    /**
     * Finds the first open pair in row-major start-cell order whose Manhattan
     * distance is within the requested tolerance.
     *
     * <p>The search checks coordinate offsets instead of comparing every pair
     * of open cells. This keeps the fallback bounded by the requested distance
     * range and avoids allocating a candidate array for every offset.
     *
     * @param open layout whose interior cells are candidates
     * @param target requested Manhattan distance
     * @param tolerance inclusive distance tolerance
     * @return a matching pair, or null when no pair exists
     */
    private static int[][] findDistancePair(boolean[][] open, int target, int tolerance) {
        List<int[]> openCells = getOpenInteriorCells(open);
        int minimumDistance = Math.max(1, target - tolerance);
        int maximumDistance = target + tolerance;
        for (int[] start : openCells) {
            for (int distance = minimumDistance; distance <= maximumDistance; distance++) {
                for (int rowDelta = 0; rowDelta <= distance; rowDelta++) {
                    int columnDelta = distance - rowDelta;
                    int[][] pair = matchingPair(
                            open, start, start[0] + rowDelta, start[1] + columnDelta);
                    if (pair != null) {
                        return pair;
                    }
                    pair = matchingPair(
                            open, start, start[0] + rowDelta, start[1] - columnDelta);
                    if (pair != null) {
                        return pair;
                    }
                    pair = matchingPair(
                            open, start, start[0] - rowDelta, start[1] + columnDelta);
                    if (pair != null) {
                        return pair;
                    }
                    pair = matchingPair(
                            open, start, start[0] - rowDelta, start[1] - columnDelta);
                    if (pair != null) {
                        return pair;
                    }
                }
            }
        }
        return null;
    }

    private static int[][] matchingPair(
            boolean[][] open, int[] start, int goalRow, int goalColumn) {
        if (goalRow < 1 || goalRow >= open.length - 1
                || goalColumn < 1 || goalColumn >= open[0].length - 1
                || !open[goalRow][goalColumn]
                || (start[0] == goalRow && start[1] == goalColumn)) {
            return null;
        }
        return new int[][] {start, new int[] {goalRow, goalColumn}};
    }

    private static Maze createMazeFromLayout(boolean[][] open) {
        Maze maze = new Maze(open.length, open[0].length);
        for (int row = 0; row < open.length; row++) {
            for (int col = 0; col < open[row].length; col++) {
                if (open[row][col]) {
                    maze.createCell(row, col, 0);
                }
            }
        }
        return maze;
    }

    private static void assignExperimentCosts(
            Maze maze, TestSuite.TestCase testCase, Random random) {
        if (testCase.getCostMode() == TestSuite.CostMode.UNIFORM) {
            return;
        }
        assignRandomMovementCosts(
                maze, testCase.getMinimumCost(), testCase.getMaximumCost(),
                testCase.isSameCostBothDirections(), random);
    }

    private static List<int[]> getOpenInteriorCells(boolean[][] open) {
        List<int[]> cells = new ArrayList<>();
        for (int row = 1; row < open.length - 1; row++) {
            for (int col = 1; col < open[row].length - 1; col++) {
                if (open[row][col]) {
                    cells.add(new int[] {row, col});
                }
            }
        }
        return cells;
    }

    private static List<int[]> getReachableCells(boolean[][] open, int startRow, int startCol) {
        List<int[]> reachable = new ArrayList<>();
        boolean[][] visited = new boolean[open.length][open[0].length];
        List<int[]> queue = new ArrayList<>();
        queue.add(new int[] {startRow, startCol});
        visited[startRow][startCol] = true;
        for (int index = 0; index < queue.size(); index++) {
            int[] current = queue.get(index);
            reachable.add(current);
            for (Dir direction : Dir.values()) {
                int row = current[0] + rowDelta(direction);
                int col = current[1] + colDelta(direction);
                if (row >= 0 && row < open.length && col >= 0 && col < open[0].length
                        && open[row][col] && !visited[row][col]) {
                    visited[row][col] = true;
                    queue.add(new int[] {row, col});
                }
            }
        }
        return reachable;
    }

    private static boolean areConnected(
            boolean[][] open, int startRow, int startCol, int goalRow, int goalCol) {
        for (int[] coordinate : getReachableCells(open, startRow, startCol)) {
            if (coordinate[0] == goalRow && coordinate[1] == goalCol) {
                return true;
            }
        }
        return false;
    }

    private static void carveDirectConnection(
            boolean[][] open, int startRow, int startCol, int goalRow, int goalCol) {
        int row = startRow;
        int col = startCol;
        open[row][col] = true;
        while (row != goalRow) {
            row += Integer.signum(goalRow - row);
            open[row][col] = true;
        }
        while (col != goalCol) {
            col += Integer.signum(goalCol - col);
            open[row][col] = true;
        }
    }

    private static void isolateGoal(
            boolean[][] open, int goalRow, int goalCol, int startRow, int startCol) {
        for (Dir direction : Dir.values()) {
            int row = goalRow + rowDelta(direction);
            int col = goalCol + colDelta(direction);
            if (row >= 1 && row < open.length - 1 && col >= 1 && col < open[0].length - 1
                    && !(row == startRow && col == startCol)) {
                open[row][col] = false;
            }
        }
    }

    private static int countBlockedInterior(boolean[][] open) {
        int blocked = 0;
        for (int row = 1; row < open.length - 1; row++) {
            for (int col = 1; col < open[row].length - 1; col++) {
                if (!open[row][col]) {
                    blocked++;
                }
            }
        }
        return blocked;
    }

    private static boolean sameCoordinate(int[] first, int[] second) {
        return first[0] == second[0] && first[1] == second[1];
    }

    private static int manhattanDistance(int[] first, int[] second) {
        return Math.abs(first[0] - second[0]) + Math.abs(first[1] - second[1]);
    }

    /**
     * Metadata for one generated experiment map.
     *
     * <p>The wrapped maze is shared read-only by the solver jobs for one
     * iteration. Each job creates its own {@code MazeSolver}, so search state
     * is never shared between algorithms.
     */
    public static final class GeneratedExperimentMap {
        private final Maze maze;
        private final long effectiveSeed;
        private final int blockedCellCount;
        private final double actualBlockedPercent;
        private final boolean solvable;
        private final int startRow;
        private final int startColumn;
        private final int goalRow;
        private final int goalColumn;
        private final int actualManhattanDistance;

        private GeneratedExperimentMap(
                Maze maze, long effectiveSeed, int blockedCellCount,
                double actualBlockedPercent, boolean solvable,
                int startRow, int startColumn, int goalRow, int goalColumn,
                int actualManhattanDistance) {
            this.maze = maze;
            this.effectiveSeed = effectiveSeed;
            this.blockedCellCount = blockedCellCount;
            this.actualBlockedPercent = actualBlockedPercent;
            this.solvable = solvable;
            this.startRow = startRow;
            this.startColumn = startColumn;
            this.goalRow = goalRow;
            this.goalColumn = goalColumn;
            this.actualManhattanDistance = actualManhattanDistance;
        }

        /**
         * Wraps an imported maze as an experiment map without regenerating it.
         *
         * @param maze imported maze to execute
         * @param seed effective seed associated with the experiment row
         * @return generated-map wrapper containing the imported maze
         */
        public static GeneratedExperimentMap fromSavedMap(Maze maze, long seed) {
            int blocked = 0;
            for (int row = 1; row < maze.getLength() - 1; row++) {
                for (int col = 1; col < maze.getWidth() - 1; col++) {
                    if (!maze.isOccupied(row, col)) {
                        blocked++;
                    }
                }
            }
            int interior = Math.max(1, (maze.getLength() - 2) * (maze.getWidth() - 2));
            int startRow = maze.getStart().row;
            int startColumn = maze.getStart().col;
            int goalRow = maze.getGoal().row;
            int goalColumn = maze.getGoal().col;
            return new GeneratedExperimentMap(maze, seed, blocked,
                    100.0 * blocked / interior, true, startRow, startColumn,
                    goalRow, goalColumn,
                    Math.abs(startRow - goalRow) + Math.abs(startColumn - goalColumn));
        }

        /**
         * Gets the generated maze containing the selected endpoints and costs.
         *
         * @return generated maze
         */
        public Maze getMaze() { return maze; }
        /**
         * Gets the effective seed used to generate this map.
         *
         * @return effective seed
         */
        public long getEffectiveSeed() { return effectiveSeed; }
        /**
         * Gets the blocked interior-cell count.
         *
         * @return blocked interior-cell count
         */
        public int getBlockedCellCount() { return blockedCellCount; }
        /**
         * Gets the actual blocked percentage among interior cells.
         *
         * @return actual blocked percentage
         */
        public double getActualBlockedPercent() { return actualBlockedPercent; }
        /**
         * Indicates whether the selected endpoints are connected.
         *
         * @return true when the generated map has a route
         */
        public boolean isSolvable() { return solvable; }
        /**
         * Gets the selected start row.
         *
         * @return start row
         */
        public int getStartRow() { return startRow; }
        /**
         * Gets the selected start column.
         *
         * @return start column
         */
        public int getStartColumn() { return startColumn; }
        /**
         * Gets the selected goal row.
         *
         * @return goal row
         */
        public int getGoalRow() { return goalRow; }
        /**
         * Gets the selected goal column.
         *
         * @return goal column
         */
        public int getGoalColumn() { return goalColumn; }
        /**
         * Gets the actual Manhattan distance between endpoints.
         *
         * @return actual Manhattan distance
         */
        public int getActualManhattanDistance() { return actualManhattanDistance; }
    }

    /**
     * Assigns random integer costs to all permitted directional connections.
     *
     * <p>Each geometric connection is processed once from its north or west
     * endpoint. When reverse costs are matched, the same integer is written to
     * both directions. Otherwise each direction receives its own integer.
     *
     * @param maze maze whose permitted connections should be weighted
     * @param minWeight inclusive minimum movement cost
     * @param maxWeight inclusive maximum movement cost
      * @param sameCostBothDirections whether reverse directions share one cost
      * @param random random source
      */
     private static void assignRandomMovementCosts(
             Maze maze, int minWeight, int maxWeight,
             boolean sameCostBothDirections, Random random) {
         for (int row = 0; row < maze.length; row++) {
             for (int col = 0; col < maze.width; col++) {
                 Node node = maze.grid[row][col];
                 if (node == null) {
                     continue;
                 }

                 if (node.getNeighbor(Dir.EAST) != null) {
                     assignConnectionCosts(
                             node, Dir.EAST, minWeight, maxWeight,
                             sameCostBothDirections, random);
                 }
                 if (node.getNeighbor(Dir.SOUTH) != null) {
                     assignConnectionCosts(
                             node, Dir.SOUTH, minWeight, maxWeight,
                             sameCostBothDirections, random);
                 }
             }
         }
     }

     private static void assignConnectionCosts(
             Node node, Dir direction, int minWeight, int maxWeight,
             boolean sameCostBothDirections, Random random) {
         Node neighbor = node.getNeighbor(direction);
         int cost = nextRandomWeight(minWeight, maxWeight, random);
         node.setMoveCost(direction, cost);

         if (!sameCostBothDirections) {
             neighbor.setMoveCost(opposite(direction),
                     nextRandomWeight(minWeight, maxWeight, random));
         } else {
             neighbor.setMoveCost(opposite(direction), cost);
         }
     }

     private static int nextRandomWeight(int minWeight, int maxWeight, Random random) {
         return minWeight + random.nextInt(maxWeight - minWeight + 1);
     }

    /**
     * Carves a traditional maze by depth-first backtracking over interior cells.
     *
     * @param open mutable occupancy matrix
     * @param startRow carve start row (typically 1)
     * @param startCol carve start column (typically 1)
     * @param random random source
     */
    private static void carveTraditionalMaze(boolean[][] open, int startRow, int startCol, Random random) {
        List<int[]> stack = new ArrayList<>();
        stack.add(new int[] { startRow, startCol });
        open[startRow][startCol] = true;

        int[][] directions = new int[][] {
            { -2, 0 },
            { 0, 2 },
            { 2, 0 },
            { 0, -2 }
        };

        while (!stack.isEmpty()) {
            int[] current = stack.get(stack.size() - 1);
            int row = current[0];
            int col = current[1];

            List<int[]> candidates = new ArrayList<>();
            for (int i = 0; i < directions.length; i++) {
                int nRow = row + directions[i][0];
                int nCol = col + directions[i][1];
                if (nRow <= 0 || nRow >= open.length - 1 || nCol <= 0 || nCol >= open[0].length - 1) {
                    continue;
                }
                if (!open[nRow][nCol]) {
                    candidates.add(new int[] { nRow, nCol });
                }
            }

            if (candidates.isEmpty()) {
                stack.remove(stack.size() - 1);
                continue;
            }

            int[] next = candidates.get(random.nextInt(candidates.size()));
            int wallRow = row + ((next[0] - row) / 2);
            int wallCol = col + ((next[1] - col) / 2);
            open[wallRow][wallCol] = true;
            open[next[0]][next[1]] = true;
            stack.add(next);
        }
    }

    /**
     * Extends carved corridors into the final interior row/column for even dimensions.
     *
     * <p>Backtracking on a two-cell step naturally visits odd-indexed cells. For even
     * dimensions, the last interior row/column are even indexes and can otherwise remain
     * mostly closed. This pass opens connectors from already-open neighbors.
     *
     * @param open mutable occupancy matrix
     * @param random random source
     */
    private static void extendEvenInteriorEdges(boolean[][] open, Random random) {
        int lastInteriorRow = open.length - 2;
        int lastInteriorCol = open[0].length - 2;

        if (lastInteriorRow % 2 == 0) {
            int opened = 0;
            for (int col = 1; col <= lastInteriorCol; col++) {
                if (open[lastInteriorRow - 1][col] && random.nextDouble() < 0.60) {
                    open[lastInteriorRow][col] = true;
                    opened++;
                }
            }
            if (opened == 0) {
                for (int col = 1; col <= lastInteriorCol; col++) {
                    if (open[lastInteriorRow - 1][col]) {
                        open[lastInteriorRow][col] = true;
                        break;
                    }
                }
            }
        }

        if (lastInteriorCol % 2 == 0) {
            int opened = 0;
            for (int row = 1; row <= lastInteriorRow; row++) {
                if (open[row][lastInteriorCol - 1] && random.nextDouble() < 0.60) {
                    open[row][lastInteriorCol] = true;
                    opened++;
                }
            }
            if (opened == 0) {
                for (int row = 1; row <= lastInteriorRow; row++) {
                    if (open[row][lastInteriorCol - 1]) {
                        open[row][lastInteriorCol] = true;
                        break;
                    }
                }
            }
        }
    }

    /**
     * Ensures the configured goal coordinate is open and connected to the maze.
        *
        * <p>If the goal cell was not reached by the carved graph, this method
        * opens a short connector toward the carved region so solving always has a
        * valid target.
     *
     * @param open mutable occupancy matrix
     * @param goalRow goal row coordinate
     * @param goalCol goal column coordinate
     * @param random random source
     */
    private static void connectGoalToMaze(boolean[][] open, int goalRow, int goalCol, Random random) {
        boolean[][] originallyOpen = new boolean[open.length][open[0].length];
        for (int row = 0; row < open.length; row++) {
            System.arraycopy(open[row], 0, originallyOpen[row], 0, open[row].length);
        }

        int row = goalRow;
        int col = goalCol;
        open[row][col] = true;

        while (!originallyOpen[row][col]) {
            if (row > 1 && col > 1) {
                if (random.nextBoolean()) {
                    row--;
                } else {
                    col--;
                }
            } else if (row > 1) {
                row--;
            } else if (col > 1) {
                col--;
            } else {
                break;
            }
            open[row][col] = true;
        }
    }

    /**
     * Export the current maze to text format for import/export.
      *
      * <p>The output follows the same schema accepted by {@link #importFromFile(String)}.
      *
      * @param filePath destination file path
      * @throws IOException if writing fails
      * @throws IllegalStateException if start or end is not set
     */
    public void exportToFile(String filePath) throws IOException {
        MazeFileIO.exportToFile(this, filePath);
    }

    /**
     * Exports the current maze using the complete saveable map format.
     *
     * @return serialized maze text
     */
    public String exportToText() {
        return MazeFileIO.exportToText(this);
    }

    /**
     * Gets the cost of moving between two adjacent nodes.
     *
     * @param from source node
     * @param to destination node
     * @return zero when movement is disabled, otherwise the positive cost
     * @throws IllegalArgumentException if the nodes are not adjacent
     */
    public int getMoveCost(Node from, Node to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("Both movement nodes are required.");
        }

        for (Dir dir : Dir.values()) {
            if (from.getNeighbor(dir) == to) {
                return from.getMoveCost(dir);
            }
        }

        throw new IllegalArgumentException("Nodes are not Manhattan neighbors.");
    }

    /**
     * Indicates whether movement is enabled from a node in a direction.
     *
     * @param from source node
     * @param direction direction to inspect
     * @return true when a neighbor exists and its movement cost is positive
     */
    public boolean canMove(Node from, Dir direction) {
        return from != null
                && from.getNeighbor(direction) != null
                && from.getMoveCost(direction) > 0;
    }


    /**
     * Sets the start node if the given coordinate is occupied.
     *
     * @param row start row coordinate
     * @param col start column coordinate
     */
    public void setStart(int row, int col) {
        if (isOccupied(row, col)) {
            this.start = grid[row][col];
        }
    }
    
    /**
     * Sets the goal node and updates the goal flag on nodes accordingly.
     *
     * @param row goal row coordinate
     * @param col goal column coordinate
     */
    public void setGoal(int row, int col) {
        if (this.goal != null) {
            this.goal.isGoal = false;
        }

        if (isOccupied(row, col)) {
            this.goal = grid[row][col];
            this.goal.isGoal = true;
        } else {
            this.goal = null;
        }
    }
    
    /**
     * Returns the current start node.
     *
     * @return start node, or null if not set
     */
    public Node getStart() {
        return this.start;
    }
    
    /**
     * Returns the current goal node.
     *
     * @return goal node, or null if not set
     */
    public Node getGoal() {
        return this.goal;
    }
    
    /**
     * Returns the node at a coordinate if that cell is occupied.
     *
     * @param row row coordinate to query
     * @param col column coordinate to query
     * @return node at the coordinate, or null if not occupied
     */
    public Node getNode(int row, int col) {
        if (isOccupied(row, col)) {
            return grid[row][col];
        }
        return null;
    }
    
    /**
     * Checks whether coordinates are inside maze boundaries.
     *
     * @param row row coordinate to test
     * @param col column coordinate to test
     * @return true if in range, otherwise false
     */
    public boolean isInBounds(int row, int col) {
        return row >= 0 && row < length && col >= 0 && col < width;
    }

    /**
     * Checks whether a cell is a traversable/occupied cell.
     *
     * @param row row coordinate to test
     * @param col column coordinate to test
     * @return true if in bounds and occupied, otherwise false
     */
    public boolean isOccupied(int row, int col) {
        if (isInBounds(row, col)) {
            return grid[row][col] != null;
        }
        return false;
    }
    
    /**
     * Gets the maze row count.
     *
     * @return total number of rows
     */
    public int getLength() {
        return length;
    }
    
    /**
     * Gets the maze column count.
     *
     * @return total number of columns
     */
    public int getWidth() {
        return width;
    }



    // Validation and debugging utilities.

    /**
     * Validation result for neighbor consistency checks.
     */
    public static class NeighborValidationResult {
        private final boolean valid;
        private final int issueCount;
        private final List<String> issues;

        /**
         * Creates an immutable validation result object.
         *
         * @param valid true if no issues were found
         * @param issueCount total number of issues detected
         * @param issues captured issue messages (possibly truncated)
         */
        private NeighborValidationResult(boolean valid, int issueCount, List<String> issues) {
            this.valid = valid;
            this.issueCount = issueCount;
            this.issues = issues;
        }

        /**
         * Indicates whether neighbor validation succeeded.
         *
         * @return true when zero issues were found
         */
        public boolean isValid() {
            return valid;
        }

        /**
         * Gets the total number of detected issues.
         *
         * @return issue count
         */
        public int getIssueCount() {
            return issueCount;
        }

        /**
        * Gets the collected issue messages.
        *
        * @return immutable list of issue descriptions
        */
        public List<String> getIssues() {
            return issues;
        }
    }

    /**
     * Validate all node-to-neighbor links against occupied adjacency.
     *
     * @param maxIssuesToCollect maximum number of issue messages to store.
    * @return validation result containing status, issue count, and messages
     */
    public NeighborValidationResult validateNeighborConnections(int maxIssuesToCollect) {
        int issues = 0;
        int limit = Math.max(0, maxIssuesToCollect);
        List<String> issueMessages = new ArrayList<>();

        for (int row = 0; row < length; row++) {
            for (int col = 0; col < width; col++) {
                if (!isOccupied(row, col)) {
                    continue;
                }

                Node node = getNode(row, col);
                if (node == null) {
                    issues++;
                    addIssue(issueMessages, limit,
                        "Neighbor mismatch: occupied cell has null node at (" + row + "," + col + ")");
                    continue;
                }

                for (Dir dir : Dir.values()) {
                    int nRow = row + rowDelta(dir);
                    int nCol = col + colDelta(dir);
                    boolean expectedConnected = isInBounds(nRow, nCol) && isOccupied(nRow, nCol);

                    Node actualNeighbor = node.getNeighbor(dir);

                    if (!expectedConnected) {
                        if (actualNeighbor != null) {
                            issues++;
                            addIssue(issueMessages, limit,
                                "Neighbor mismatch: expected null " + dir + " neighbor at (" + row + "," + col + ") but got ("
                                    + actualNeighbor.row + "," + actualNeighbor.col + ")");
                        }
                        continue;
                    }

                    Node expectedNeighbor = getNode(nRow, nCol);
                    if (actualNeighbor == null) {
                        issues++;
                        addIssue(issueMessages, limit,
                            "Neighbor mismatch: missing " + dir + " neighbor at (" + row + "," + col + "), expected ("
                                + nRow + "," + nCol + ")");
                        continue;
                    }

                    if (actualNeighbor != expectedNeighbor) {
                        issues++;
                        addIssue(issueMessages, limit,
                            "Neighbor mismatch: wrong " + dir + " neighbor at (" + row + "," + col + "), expected ("
                                + nRow + "," + nCol + ") but got (" + actualNeighbor.row + "," + actualNeighbor.col + ")");
                    }

                    Node reciprocal = actualNeighbor.getNeighbor(opposite(dir));
                    if (reciprocal != node) {
                        issues++;
                        addIssue(issueMessages, limit,
                            "Neighbor mismatch: reciprocal link broken between (" + row + "," + col + ") and ("
                                + actualNeighbor.row + "," + actualNeighbor.col + ") for " + dir);
                    }
                }
            }
        }

        return new NeighborValidationResult(issues == 0, issues, Collections.unmodifiableList(issueMessages));
    }

    /**
     * Build a compact, line-by-line neighbor table for occupied cells.
        *
        * @return list of formatted lines describing neighbors for debugging
     */
    public List<String> buildNeighborTableLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Neighbor table (occupied cells only):");

        for (int row = 0; row < length; row++) {
            for (int col = 0; col < width; col++) {
                if (!isOccupied(row, col)) {
                    continue;
                }

                Node node = getNode(row, col);
                if (node == null) {
                    lines.add("(" + row + "," + col + ") N=null E=null S=null W=null");
                    continue;
                }

                String north = formatNode(node.getNeighbor(Dir.NORTH));
                String east = formatNode(node.getNeighbor(Dir.EAST));
                String south = formatNode(node.getNeighbor(Dir.SOUTH));
                String west = formatNode(node.getNeighbor(Dir.WEST));
                lines.add("(" + row + "," + col + ") N=" + north + " E=" + east + " S=" + south + " W=" + west
                        + " costs=[" + node.getMoveCost(Dir.NORTH) + "," + node.getMoveCost(Dir.EAST)
                        + "," + node.getMoveCost(Dir.SOUTH) + "," + node.getMoveCost(Dir.WEST) + "]");
            }
        }

        return lines;
    }

    /**
     * Adds an issue message to the collection while respecting a hard cap.
     *
     * @param issues destination list of issue messages
     * @param maxIssuesToCollect maximum number of messages to store
     * @param message issue message text
     */
    private static void addIssue(List<String> issues, int maxIssuesToCollect, String message) {
        if (issues.size() < maxIssuesToCollect) {
            issues.add(message);
        }
    }

    /**
     * Returns row delta for a movement direction.
     *
     * @param dir direction to convert
     * @return -1, 0, or 1 depending on vertical movement
     */
    private static int rowDelta(Dir dir) {
        switch (dir) {
            case NORTH:
                return -1;
            case SOUTH:
                return 1;
            default:
                return 0;
        }
    }

    /**
     * Returns column delta for a movement direction.
     *
     * @param dir direction to convert
     * @return -1, 0, or 1 depending on horizontal movement
     */
    private static int colDelta(Dir dir) {
        switch (dir) {
            case EAST:
                return 1;
            case WEST:
                return -1;
            default:
                return 0;
        }
    }

    /**
     * Returns the opposite of a given direction.
     *
     * @param dir input direction
     * @return opposite direction
     */
    private static Dir opposite(Dir dir) {
        switch (dir) {
            case NORTH:
                return Dir.SOUTH;
            case EAST:
                return Dir.WEST;
            case SOUTH:
                return Dir.NORTH;
            case WEST:
                return Dir.EAST;
            default:
                throw new IllegalArgumentException("Unknown direction: " + dir);
        }
    }

    /**
     * Formats a node as coordinate text for debug output.
     *
     * @param node node to format
     * @return coordinate string or null text
     */
    private static String formatNode(Node node) {
        if (node == null) {
            return "null";
        }
        return "(" + node.row + "," + node.col + ")";
    }

}
