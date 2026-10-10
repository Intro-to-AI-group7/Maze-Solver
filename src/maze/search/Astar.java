package maze.search;

import java.util.PriorityQueue;
import maze.map.Maze;

/**
 * Incremental weighted A* strategy using Manhattan distance as its heuristic.
 */
final class Astar implements SearchStrategy {
    private final PriorityQueue<Entry> frontier = new PriorityQueue<>();
    private boolean[][] closed;

    @Override
    public void initialize(MazeSolver solver) {
        Maze maze = solver.strategyMaze();
        solver.prepareStrategyTablesWithInfinity();
        closed = new boolean[maze.getLength()][maze.getWidth()];
        Maze.Node start = maze.getStart();
        solver.setStrategyCost(start, 0);
        frontier.add(new Entry(start, 0, heuristic(start, maze.getGoal())));
    }

    @Override
    public Maze.Node step(MazeSolver solver) {
        Maze maze = solver.strategyMaze();
        while (!frontier.isEmpty()) {
            Entry entry = frontier.remove();
            Maze.Node current = entry.node;
            if (closed[current.row][current.col]
                    || entry.gCost != solver.getStrategyCost(current)) {
                continue;
            }
            closed[current.row][current.col] = true;
            solver.recordStrategyVisit(current);
            if (current == maze.getGoal()) {
                solver.finishStrategy(true);
                solver.setStrategyPathCost(solver.getStrategyCost(current));
                return current;
            }

            for (Maze.Dir direction : Maze.Dir.values()) {
                Maze.Node neighbor = current.getNeighbor(direction);
                if (neighbor == null || closed[neighbor.row][neighbor.col]
                        || !maze.canMove(current, direction)) {
                    continue;
                }
                long routeCost = solver.getStrategyCost(current)
                        + maze.getMoveCost(current, neighbor);
                if (routeCost < solver.getStrategyCost(neighbor)) {
                    solver.setStrategyCost(neighbor, routeCost);
                    solver.setStrategyParent(neighbor, current);
                    frontier.add(new Entry(neighbor, routeCost,
                            routeCost + heuristic(neighbor, maze.getGoal())));
                }
            }
            if (frontier.isEmpty()) {
                solver.finishStrategy(false);
            }
            return current;
        }
        solver.finishStrategy(false);
        return null;
    }

    private static long heuristic(Maze.Node from, Maze.Node to) {
        return (long) Math.abs(from.row - to.row) + Math.abs(from.col - to.col);
    }

    private static final class Entry implements Comparable<Entry> {
        private final Maze.Node node;
        private final long gCost;
        private final long fCost;

        private Entry(Maze.Node node, long gCost, long fCost) {
            this.node = node;
            this.gCost = gCost;
            this.fCost = fCost;
        }

        @Override
        public int compareTo(Entry other) {
            int result = Long.compare(fCost, other.fCost);
            return result != 0 ? result : Long.compare(gCost, other.gCost);
        }
    }
}
