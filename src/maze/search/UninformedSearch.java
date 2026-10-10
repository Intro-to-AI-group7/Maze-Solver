package maze.search;

import java.util.ArrayDeque;
import java.util.Deque;
import maze.map.Maze;

/**
 * Incremental implementation for the uninformed DFS and BFS algorithms.
 *
 * <p>Both algorithms use the same discovery, parent, cost, and completion
 * logic. Their only behavioral difference is frontier removal order, which is
 * selected explicitly by {@link TraversalMode}.
 */
final class UninformedSearch implements SearchStrategy {
    enum TraversalMode {
        DFS,
        BFS
    }

    private final TraversalMode traversalMode;
    private final Deque<Maze.Node> frontier = new ArrayDeque<>();
    private boolean[][] discovered;

    UninformedSearch(TraversalMode traversalMode) {
        if (traversalMode == null) {
            throw new IllegalArgumentException("Traversal mode cannot be null.");
        }
        this.traversalMode = traversalMode;
    }

    @Override
    public void initialize(MazeSolver solver) {
        Maze maze = solver.strategyMaze();
        discovered = new boolean[maze.getLength()][maze.getWidth()];
        solver.prepareStrategyTables();
        Maze.Node start = maze.getStart();
        discovered[start.row][start.col] = true;
        solver.setStrategyCost(start, 0);
        frontier.addLast(start);
    }

    @Override
    public Maze.Node step(MazeSolver solver) {
        Maze maze = solver.strategyMaze();
        if (frontier.isEmpty()) {
            solver.finishStrategy(false);
            return null;
        }

        Maze.Node current = traversalMode == TraversalMode.DFS
                ? frontier.removeLast() : frontier.removeFirst();
        solver.recordStrategyVisit(current);
        if (current == maze.getGoal()) {
            solver.finishStrategy(true);
            solver.setStrategyPathCost(solver.calculateStrategyPathCost(current));
            return current;
        }

        for (Maze.Dir direction : Maze.Dir.values()) {
            Maze.Node neighbor = current.getNeighbor(direction);
            if (neighbor == null || !maze.canMove(current, direction)
                    || discovered[neighbor.row][neighbor.col]) {
                continue;
            }
            discovered[neighbor.row][neighbor.col] = true;
            solver.setStrategyParent(neighbor, current);
            solver.setStrategyCost(neighbor,
                    solver.getStrategyCost(current) + maze.getMoveCost(current, neighbor));
            frontier.addLast(neighbor);
        }

        if (frontier.isEmpty()) {
            solver.finishStrategy(false);
        }
        return current;
    }
}
