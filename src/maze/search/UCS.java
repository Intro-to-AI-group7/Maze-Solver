package maze.search;

import java.util.PriorityQueue;
import maze.map.Maze;

/**
 * Uniform-Cost Search strategy.
 */
final class UCS implements SearchStrategy {
    private final PriorityQueue<Entry> frontier = new PriorityQueue<>();
    private boolean[][] discovered;
    private boolean[][] closed;

    @Override
    public void initialize(MazeSolver solver) {
        Maze maze = solver.strategyMaze();
        solver.prepareStrategyTablesWithInfinity();
        discovered = new boolean[maze.getLength()][maze.getWidth()];
        closed = new boolean[maze.getLength()][maze.getWidth()];
        Maze.Node start = maze.getStart();
        discovered[start.row][start.col] = true;
        solver.setStrategyCost(start, 0);
        frontier.add(new Entry(start, 0, 0));
    }

    @Override
    public Maze.Node step(MazeSolver solver) {
        Maze maze = solver.strategyMaze();
        while (!frontier.isEmpty()) {
            Entry entry = frontier.remove();
            Maze.Node current = entry.node;
            if (closed[current.row][current.col]) {
                continue;
            }
            if (entry.gCost != solver.getStrategyCost(current)) {
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
                if (routeCost >= solver.getStrategyCost(neighbor)) {
                    continue;
                }
                solver.setStrategyCost(neighbor, routeCost);
                solver.setStrategyParent(neighbor, current);
                frontier.add(new Entry(neighbor, routeCost, routeCost));
            }
            if (frontier.isEmpty()) {
                solver.finishStrategy(false);
            }
            return current;
        }
        solver.finishStrategy(false);
        return null;
    }

    private static final class Entry implements Comparable<Entry> {
        private final Maze.Node node;
        private final long gCost;
        private final long priority;

        private Entry(Maze.Node node, long gCost, long priority) {
            this.node = node;
            this.gCost = gCost;
            this.priority = priority;
        }

        @Override
        public int compareTo(Entry other) {
            int result = Long.compare(priority, other.priority);
            return result != 0 ? result : Long.compare(gCost, other.gCost);
        }
    }
}
