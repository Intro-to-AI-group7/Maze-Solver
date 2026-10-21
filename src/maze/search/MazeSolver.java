package maze.search;

import maze.map.Maze;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * Incremental maze solver that supports DFS, BFS, Uniform Cost Search,
 * Greedy Best-First Search, and weighted A*.
 *
 * <p>The solver keeps internal state between calls to {@link #updateSolve()},
 * so callers can visualize or inspect each step one node at a time.
 * By default, reported solve time accumulates the elapsed time of each
 * search-step call, excluding timer delays and UI repaint work. Batch callers
 * can disable this diagnostic timing and measure a complete solve externally
 * with {@link #setStepTimingEnabled(boolean)}.
 *
 * <p>Typical usage: create a solver, set algorithm and maze, then call
 * {@link #updateSolve()} repeatedly until {@link #isFinished()} returns true.
 * Example:
 * <pre>{@code
 * MazeSolver solver = new MazeSolver();
 * solver.setMaze(maze);
 * solver.setAlg("A*");
 * while (!solver.isFinished()) {
 *     solver.updateSolve();
 * }
 * if (solver.isSolved()) {
 *     List<Point> path = solver.getFinalPath();
 * }
 * }</pre>
 */
public class MazeSolver {

    /**
     * Search strategies supported by this solver.
     */
    public enum Algorithm {
        /** Depth-first graph search. */
        DFS,
        /** Breadth-first graph search. */
        BFS,
        /** Uniform Cost Search ordered by accumulated route cost. */
        UCS,
        /** Greedy Best-First Search ordered by Manhattan distance. */
        GREEDY,
        /** A* ordered by route cost plus Manhattan distance. */
        A_STAR
    }

    /** Creates an unconfigured solver using DFS as its default algorithm. */
    public MazeSolver() {
    }

    private Maze maze;
    private Algorithm algorithm = Algorithm.DFS;

    private boolean initialized;
    private boolean finished;
    private boolean solved;
    private int visitedCount;
    private long solveTimeNs;
    private boolean stepTimingEnabled = true;
    private long pathCost = -1;
    private long currentPathCost = -1;
    private Maze.Node[][] parent;
    private SearchStrategy strategy;

    private long[][] gScore;

    /**
     * Sets which algorithm to run.
     *
     * @param alg algorithm label. Accepted values are DFS, BFS, UCS, Greedy,
     *            A*, ASTAR, and A_STAR (case-insensitive)
     * @throws IllegalArgumentException if {@code alg} is null or unsupported
     */
    public void setAlg(String alg) {
        if (alg == null) {
            throw new IllegalArgumentException("Algorithm cannot be null.");
        }

        String normalized = alg.trim().toUpperCase();
        if ("DFS".equals(normalized)) {
            this.algorithm = Algorithm.DFS;
        } else if ("BFS".equals(normalized)) {
            this.algorithm = Algorithm.BFS;
        } else if ("UCS".equals(normalized) || "UNIFORM COST".equals(normalized)
                || "UNIFORM_COST".equals(normalized)) {
            this.algorithm = Algorithm.UCS;
        } else if ("GREEDY".equals(normalized) || "GREEDY BEST-FIRST".equals(normalized)
                || "GREEDY_BEST_FIRST".equals(normalized)) {
            this.algorithm = Algorithm.GREEDY;
        } else if ("A*".equals(normalized) || "ASTAR".equals(normalized) || "A_STAR".equals(normalized)) {
            this.algorithm = Algorithm.A_STAR;
        } else {
            throw new IllegalArgumentException(
                    "Unsupported algorithm: " + alg + ". Use DFS, BFS, UCS, Greedy, or A*.");
        }

        resetSearch();
    }

    /**
     * Gets the active algorithm label.
     *
     * @return display label for the current algorithm
     */
    public String getAlg() {
        if (algorithm == Algorithm.A_STAR) {
            return "A*";
        }
        if (algorithm == Algorithm.GREEDY) {
            return "Greedy";
        }
        return algorithm.name();
    }

    /**
     * Assigns the maze instance to solve.
     *
     * @param maze maze object to solve; start/goal are validated when solving begins
     */
    public void setMaze(Maze maze) {
        this.maze = maze;
        resetSearch();
    }

    /**
     * Enables or disables the per-step timing used by interactive displays.
     *
     * <p>Timing is enabled by default. Batch callers that measure the complete
     * solve externally can disable it to avoid two clock reads for every search
     * step.
     *
     * @param enabled whether each search step should update {@link #getSolveTimeNs()}
     */
    public void setStepTimingEnabled(boolean enabled) {
        this.stepTimingEnabled = enabled;
    }

    /**
     * Advances solving by exactly one visited node.
     *
     * @return node visited in this step, or {@code null} if solving is complete or no work remains
     * @throws IllegalStateException if maze/start/goal are not configured
     */
    public Maze.Node updateSolve() {
        if (finished) {
            return null;
        }

        initializeIfNeeded();

        long startTime = stepTimingEnabled ? System.nanoTime() : 0;
        Maze.Node result = strategy.step(this);
        if (stepTimingEnabled) {
            solveTimeNs += System.nanoTime() - startTime;
        }
        return result;
    }

    /**
     * Indicates whether this run has terminated.
     *
     * @return true when goal was found or search space was exhausted
     */
    public boolean isFinished() {
        return finished;
    }

    /**
     * Indicates whether the current run found the goal.
     *
     * @return true when goal node has been reached
     */
    public boolean isSolved() {
        return solved;
    }

    /**
     * Gets count of nodes visited in the current run.
     *
     * @return number of visited nodes since last reset
     */
    public int getVisitedCount() {
        return visitedCount;
    }

    /**
     * Gets the total computation time elapsed in the current run when per-step
     * timing is enabled.
     *
     * @return elapsed computation time in nanoseconds, or zero when per-step
     *         timing is disabled
     */
    public long getSolveTimeNs() {
        return solveTimeNs;
    }

    /**
     * Gets the total movement cost of the final path.
     *
     * <p>This is the sum of the directional movement costs in the path. It is
     * {@code -1} before a path is found or when the search is unsuccessful.
     *
     * @return final path cost, or {@code -1} when no path is available
     */
    public long getPathCost() {
        return pathCost;
    }

    /**
     * Gets the cost of the parent route for the most recently visited node.
     *
     * <p>This is the current branch cost, not the sum of all explored branches.
     * It is updated in constant time as each node is visited and remains useful
     * for displaying progress during an incomplete search.
     *
     * @return current route cost, or {@code -1} before the first search step
     */
    public long getCurrentPathCost() {
        return currentPathCost;
    }

    /**
     * Gets the final path after a successful solve.
     *
     * <p>The path is traced backward using the internal parent table, so the returned
     * list begins at the goal and ends at the start.
      *
      * <p>Typical UI code can iterate as-is for reverse painting, or reverse the list
      * first if start-to-goal order is preferred.
     *
     * @return goal-to-start path points where {@code x=col, y=row}; empty if unsolved
     */
    public List<Point> getFinalPath() {
        List<Point> path = new ArrayList<>();
        if (parent == null || !solved) {
            return path;
        }
        Maze.Node curr = maze.getGoal();
        // Trace back using the parent map
        while (curr != null) {
            path.add(new Point(curr.col, curr.row));
            curr = parent[curr.row][curr.col];
        }
        return path;
    }

    /**
     * Resets transient search state so solving can start fresh.
     *
     * <p>This keeps the currently assigned maze and algorithm.
     * Typical use is after changing visualization mode or when handling a restart.
     */
    public void resetSearch() {
        parent = null;
        gScore = null;
        strategy = null;
        initialized = false;
        finished = false;
        solved = false;
        visitedCount = 0;
        solveTimeNs = 0;
        pathCost = -1;
        currentPathCost = -1;
    }

    /**
     * Performs lazy one-time initialization before first solve step.
     *
     * @throws IllegalStateException if maze, start, or goal are not set
     */
    private void initializeIfNeeded() {
        if (initialized) {
            return;
        }

        if (maze == null) {
            throw new IllegalStateException("Maze must be assigned before solving.");
        }
        if (maze.getStart() == null) {
            throw new IllegalStateException("Maze start is not set.");
        }
        if (maze.getGoal() == null) {
            throw new IllegalStateException("Maze goal is not set.");
        }

        switch (algorithm) {
            case DFS:
                strategy = new UninformedSearch(UninformedSearch.TraversalMode.DFS);
                break;
            case BFS:
                strategy = new UninformedSearch(UninformedSearch.TraversalMode.BFS);
                break;
            case UCS:
                strategy = new UCS();
                break;
            case GREEDY:
                strategy = new GreedySearch();
                break;
            case A_STAR:
                strategy = new Astar();
                break;
            default:
                throw new IllegalStateException("Unsupported algorithm state: " + algorithm);
        }
        strategy.initialize(this);

        initialized = true;
        finished = false;
        solved = false;
        visitedCount = 0;
    }

    Maze strategyMaze() {
        return maze;
    }

    void prepareStrategyTables() {
        parent = new Maze.Node[maze.getLength()][maze.getWidth()];
        gScore = new long[maze.getLength()][maze.getWidth()];
    }

    void prepareStrategyTablesWithInfinity() {
        prepareStrategyTables();
        for (int row = 0; row < gScore.length; row++) {
            java.util.Arrays.fill(gScore[row], Long.MAX_VALUE);
        }
    }

    void setStrategyParent(Maze.Node node, Maze.Node value) {
        parent[node.row][node.col] = value;
    }

    void setStrategyCost(Maze.Node node, long value) {
        gScore[node.row][node.col] = value;
    }

    long getStrategyCost(Maze.Node node) {
        return gScore[node.row][node.col];
    }

    void recordStrategyVisit(Maze.Node node) {
        visitedCount++;
        currentPathCost = getStrategyCost(node);
    }

    void finishStrategy(boolean solvedValue) {
        finished = true;
        solved = solvedValue;
    }

    void setStrategyPathCost(long value) {
        pathCost = value;
    }

    long calculateStrategyPathCost(Maze.Node end) {
        return calculatePathCost(end);
    }


    /**
     * Computes the movement cost represented by the current parent chain.
     *
     * @param end last node in the discovered path
     * @return sum of directional costs from start to end
     */
    private long calculatePathCost(Maze.Node end) {
        long total = 0;
        Maze.Node current = end;
        while (parent[current.row][current.col] != null) {
            Maze.Node previous = parent[current.row][current.col];
            total += maze.getMoveCost(previous, current);
            current = previous;
        }
        return total;
    }
}
