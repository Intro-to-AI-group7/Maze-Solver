package maze.search;

import maze.map.Maze;

/**
 * Internal incremental search strategy used by {@link MazeSolver}.
 *
 * <p>The public solver remains the stable lifecycle and metrics facade while
 * each strategy owns its frontier and algorithm-specific bookkeeping.
 */
interface SearchStrategy {
    void initialize(MazeSolver solver);

    Maze.Node step(MazeSolver solver);
}
