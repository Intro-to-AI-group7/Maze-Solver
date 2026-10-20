package app;

import javax.swing.SwingUtilities;

import ui.MazeRenderer;

/**
 * Demo application entry point.
 *
 * <p>Typical usage is to run this class directly from IDE or command line.
 * It delegates all GUI and session flow to {@link ui.MazeRenderer}.
 */
public class MS {
    /** Creates the application entry-point holder. */
    public MS() {
    }

    /**
     * Starts the Maze-Solver 0.8.1 application.
     *
     * <p>Delegates application flow to {@link ui.MazeRenderer}, including title
     * screen mode selection, maze import or generation, and interactive solve
     * controls.
     *
     * @param args command line arguments (currently unused)
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(MazeRenderer::launchFromTitleScreen);
    }
}
