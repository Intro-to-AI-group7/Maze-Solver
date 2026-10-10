package ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JViewport;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import maze.map.Maze;
import maze.search.MazeSolver;

/**
 * Renderer and UI controller for the maze application.
 *
 * <p>This class owns the full Swing-driven UX flow:
 * <ul>
 *   <li>Title screen mode selection (import or random generation)</li>
 *   <li>Maze file selection or random maze creation</li>
 *   <li>Maze grid rendering with a paint-based renderer for all maze sizes</li>
 *   <li>Runtime solver controls (start/pause/restart, delay, instant mode, map load/save/generate)</li>
 *   <li>Zoom handling (buttons and Ctrl+mouse wheel)</li>
 *   <li>Optional directional movement-cost overlay</li>
 * </ul>
 *
 * <p>Typical usage is through {@link #launchFromTitleScreen()} from the app entrypoint.
 * Example:
 * <pre>{@code
 * SwingUtilities.invokeLater(MazeRenderer::launchFromTitleScreen);
 * }</pre>
 *
 * <p>Typical extension point for custom highlighting:
 * <pre>{@code
 * Maze.Node node = maze.getStart();
 * MazeRenderer.setNodeColor(node, Color.MAGENTA);
 * MazeRenderer.resetNodeColor(node);
 * }</pre>
 */
public final class MazeRenderer {

    // ---------------------------------------------------------------------
    // Application defaults and theme
    // ---------------------------------------------------------------------

    private static final String TITLE_SCREEN_IMAGE_PATH = "images/TitleImage.jpg";
    private static final String DEFAULT_SESSION_ALGORITHM = "A*";
    private static final boolean DEFAULT_SHOW_NEIGHBORS = true;
    private static final boolean DEFAULT_SHOW_WEIGHTS = false;
    private static final int DEFAULT_SESSION_STEP_DELAY_MS = 200;
    private static final Color DEFAULT_VISITED_COLOR = new Color(0xF4C542);

    private static final Color START_CELL_COLOR = Color.GREEN;
    private static final Color GOAL_CELL_COLOR = Color.BLUE;
    private static final Color BLOCKED_CELL_COLOR = Color.DARK_GRAY;
    private static final Color OPEN_CELL_COLOR = Color.WHITE;
    private static final Color GRID_LINE_COLOR = new Color(0, 0, 0, 55);
    private static final Color TRAVERSED_WEIGHT_COLOR = new Color(70, 70, 70, 180);
    private static final Color FINAL_PATH_WEIGHT_COLOR = new Color(150, 55, 0);
    private static final Color FINAL_PATH_WEIGHT_BACKGROUND = new Color(255, 220, 120, 235);
    private static final int MIN_WEIGHT_LABEL_CELL_SIZE_PX = 20;
    private static final float WEIGHT_LABEL_FONT_SIZE = 12f;

    private static final int MIN_CELL_SIZE_PX = 6;
    private static final int MAX_CELL_SIZE_PX = 60;
    private static final int ZOOM_STEP_PX = 2;

    // ---------------------------------------------------------------------
    // UI components and runtime session state
    // ---------------------------------------------------------------------

    private static JFrame titleFrame;
    private static JFrame mazeFrame;

    private static Maze renderedMaze;
    private static Color[][] renderedCellColors;
    private static boolean[][][] finalPathEdges;
    private static JPanel renderedGridPanel;
    private static JScrollPane renderedGridScrollPane;

    private static JComboBox<String> algorithmSelector;
    private static JSpinner delaySpinner;
    private static JLabel traversedCountLabel;
    private static JLabel solveTimeLabel;
    private static JLabel currentCostLabel;
    private static JLabel finalCostLabel;
    private static JCheckBox instantSolveCheckBox;
    private static JCheckBox showWeightsCheckBox;

    private static MazeSolver solver;
    private static Timer solveTimer;
    private static volatile boolean solvingInstantly;
    private static Thread instantSolveWorker;
    private static boolean shuttingDown;
    private static long solveSessionWallStartNs;
    private static String solveSessionMode = "unknown";
    private static int solveSessionDelayMs;
    private static boolean sessionStarted;
    private static int traversedTiles;
    private static Color sessionVisitedColor = DEFAULT_VISITED_COLOR;

    private static String defaultAlgorithm = "DFS";
    private static int defaultStepDelayMs = DEFAULT_SESSION_STEP_DELAY_MS;
    private static int defaultTraversedCount;
    private static int cellSizePx = 18;
    private static boolean applyingControlDefaults;

    /**
     * @brief Utility class; not instantiable.
     */
    private MazeRenderer() {
    }

    // ---------------------------------------------------------------------
    // Public bootstrap API
    // ---------------------------------------------------------------------

    /**
     * Starts the renderer-driven application flow from the title screen.
     *
     * <p>This is the recommended public entrypoint for GUI startup.
     */
    public static void launchFromTitleScreen() {
        showTitleScreen();
    }

    /**
     * Stops active work, closes all application windows, and terminates the JVM.
     *
     * <p>This is used by every top-level window so closing the UI cannot leave
     * an experiment worker or solve session running in the background.
     */
    public static void shutdownApplication() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        stopTimerIfRunning();
        if (instantSolveWorker != null) {
            instantSolveWorker.interrupt();
            instantSolveWorker = null;
        }
        TestSuiteWindow.closeWindow();
        if (titleFrame != null) {
            titleFrame.dispose();
            titleFrame = null;
        }
        if (mazeFrame != null) {
            mazeFrame.dispose();
            mazeFrame = null;
        }
        System.exit(0);
    }

    /**
     * Opens an animated review session for an experiment result.
     *
     * @param maze exact map used by the result
     * @param algorithm algorithm to select initially
     */
    public static void reviewExperiment(Maze maze, MazeSolver.Algorithm algorithm) {
        if (maze == null || algorithm == null) {
            throw new IllegalArgumentException("Maze and algorithm are required.");
        }
        Runnable review = () -> runInteractiveSolveSession(
                maze, algorithm == MazeSolver.Algorithm.A_STAR ? "A*" : algorithm.name(),
                DEFAULT_SESSION_STEP_DELAY_MS, DEFAULT_VISITED_COLOR);
        if (SwingUtilities.isEventDispatchThread()) {
            review.run();
        } else {
            SwingUtilities.invokeLater(review);
        }
    }

    // ---------------------------------------------------------------------
    // Title and file selection flow
    // ---------------------------------------------------------------------

    /**
     * @brief Displays the title screen and allows import or random generation.
     */
    private static void showTitleScreen() {
        if (titleFrame != null) {
            titleFrame.dispose();
        }

        titleFrame = new JFrame("Maze Solver");
        titleFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        titleFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                shutdownApplication();
            }
        });
        titleFrame.setSize(1280, 720);
        titleFrame.setLocationRelativeTo(null);

        JPanel mainPanel = new BackgroundImagePanel(TITLE_SCREEN_IMAGE_PATH);
        mainPanel.setLayout(new GridBagLayout());

        JButton importButton = new JButton("IMPORT MAZE");
        styleTitleButton(importButton);
        importButton.addActionListener(e -> {
            titleFrame.dispose();
            titleFrame = null;
            promptForMazeAndRun();
        });

        JButton generateButton = new JButton("GENERATE RANDOM");
        styleTitleButton(generateButton);
        generateButton.addActionListener(e -> {
            titleFrame.dispose();
            titleFrame = null;
            promptForRandomMazeAndRun();
        });

        JButton configureTestsButton = new JButton("CONFIGURE TESTS");
        styleTitleButton(configureTestsButton);
        configureTestsButton.addActionListener(e -> TestSuiteWindow.showWindow());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(270, 0, 12, 0);

        mainPanel.add(importButton, gbc);

        gbc.gridy = 1;
        gbc.insets = new Insets(0, 0, 0, 0);
        mainPanel.add(generateButton, gbc);
        gbc.gridy = 2;
        gbc.insets = new Insets(12, 0, 0, 0);
        mainPanel.add(configureTestsButton, gbc);
        titleFrame.add(mainPanel);
        titleFrame.setVisible(true);
    }

    /**
     * @brief Opens a maze file, prints diagnostics, and starts an interactive session.
     *
     * <p>Flow:
     * <ol>
     *   <li>Prompt user for a maze text file</li>
     *   <li>Load maze via {@link Maze#importFromFile(String)}</li>
     *   <li>Print diagnostics to console</li>
     *   <li>Create a fresh interactive solve session</li>
     * </ol>
     */
    private static void promptForMazeAndRun() {
        try {
            String inputFile = chooseMazeFile();
            if (inputFile == null) {
                showTitleScreen();
                return;
            }

            Maze maze = Maze.importFromFile(inputFile);
            launchMazeSession(maze, DEFAULT_SESSION_ALGORITHM, DEFAULT_SESSION_STEP_DELAY_MS);
        } catch (Exception ex) {
            System.err.println("Failed to load or print maze: " + ex.getMessage());
            ex.printStackTrace();
            JOptionPane.showMessageDialog(
                    null,
                    "Failed to load maze: " + ex.getMessage(),
                    "Maze Load Error",
                    JOptionPane.ERROR_MESSAGE);
            showTitleScreen();
        }
    }

    /**
     * @brief Prompts for dimensions, generates a random maze, and starts a session.
     */
    private static void promptForRandomMazeAndRun() {
        RandomMazeOptions options = promptForRandomMazeDimensions();
        if (options == null) {
            showTitleScreen();
            return;
        }

        try {
            Maze maze = Maze.generateRandomMaze(
                    options.rows, options.columns, options.weighted,
                    options.minWeight, options.maxWeight,
                    options.sameCostBothDirections);
            launchMazeSession(maze, DEFAULT_SESSION_ALGORITHM, DEFAULT_SESSION_STEP_DELAY_MS);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    null,
                    "Failed to generate random maze: " + ex.getMessage(),
                    "Maze Generation Error",
                    JOptionPane.ERROR_MESSAGE);
            showTitleScreen();
        }
    }

    /**
     * @brief Prompts for random maze dimensions.
     *
     * @return selected generation options, or null when canceled
     */
    private static RandomMazeOptions promptForRandomMazeDimensions() {
        while (true) {
            JSpinner rowSpinner = new JSpinner(new SpinnerNumberModel(60, 3, 5000, 1));
            JSpinner colSpinner = new JSpinner(new SpinnerNumberModel(60, 3, 5000, 1));
            JCheckBox weightedCheckBox = new JCheckBox("Enable movement weights");
            JCheckBox sameDirectionCheckBox =
                    new JCheckBox("Use the same cost in both directions", true);
            JSpinner minWeightSpinner = new JSpinner(new SpinnerNumberModel(
                    Maze.MIN_MOVEMENT_COST,
                    Maze.MIN_MOVEMENT_COST,
                    Maze.MAX_MOVEMENT_COST,
                    1));
            JSpinner maxWeightSpinner = new JSpinner(new SpinnerNumberModel(
                    10,
                    Maze.MIN_MOVEMENT_COST,
                    Maze.MAX_MOVEMENT_COST,
                    1));
            minWeightSpinner.setEnabled(false);
            maxWeightSpinner.setEnabled(false);
            sameDirectionCheckBox.setEnabled(false);
            weightedCheckBox.addActionListener(e -> {
                boolean enabled = weightedCheckBox.isSelected();
                minWeightSpinner.setEnabled(enabled);
                maxWeightSpinner.setEnabled(enabled);
                sameDirectionCheckBox.setEnabled(enabled);
            });

            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(4, 4, 4, 4);
            gbc.anchor = GridBagConstraints.WEST;

            addLabeledControl(panel, gbc, 0, "Rows:", rowSpinner);
            addLabeledControl(panel, gbc, 1, "Columns:", colSpinner);

            gbc.gridx = 0;
            gbc.gridy = 2;
            gbc.gridwidth = 2;
            panel.add(weightedCheckBox, gbc);

            gbc.gridy = 3;
            panel.add(sameDirectionCheckBox, gbc);

            gbc.gridwidth = 1;
            addLabeledControl(panel, gbc, 4, "Minimum weight:", minWeightSpinner);
            addLabeledControl(panel, gbc, 5, "Maximum weight:", maxWeightSpinner);

            int result = JOptionPane.showConfirmDialog(
                    null, panel, "Generate Random Maze",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return null;
            }

            int minWeight = ((Number) minWeightSpinner.getValue()).intValue();
            int maxWeight = ((Number) maxWeightSpinner.getValue()).intValue();
            if (!weightedCheckBox.isSelected() || minWeight <= maxWeight) {
                return new RandomMazeOptions(
                        ((Number) rowSpinner.getValue()).intValue(),
                        ((Number) colSpinner.getValue()).intValue(),
                        weightedCheckBox.isSelected(),
                        minWeight,
                        maxWeight,
                        sameDirectionCheckBox.isSelected());
            }

            JOptionPane.showMessageDialog(
                    null,
                    "Minimum weight cannot be greater than maximum weight.",
                    "Invalid Weight Range",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void addLabeledControl(
            JPanel panel, GridBagConstraints constraints, int row, String label, JSpinner control) {
        constraints.gridx = 0;
        constraints.gridy = row;
        panel.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        panel.add(control, constraints);
    }

    private static final class RandomMazeOptions {
        private final int rows;
        private final int columns;
        private final boolean weighted;
        private final int minWeight;
        private final int maxWeight;
        private final boolean sameCostBothDirections;

        private RandomMazeOptions(
                int rows, int columns, boolean weighted, int minWeight, int maxWeight,
                boolean sameCostBothDirections) {
            this.rows = rows;
            this.columns = columns;
            this.weighted = weighted;
            this.minWeight = minWeight;
            this.maxWeight = maxWeight;
            this.sameCostBothDirections = sameCostBothDirections;
        }
    }

    /**
     * @brief Prints diagnostics and launches an interactive maze session.
     *
     * @param maze maze instance to run
     * @param initialAlgorithm default algorithm label
     * @param initialStepDelayMs default step delay
     */
    private static void launchMazeSession(Maze maze, String initialAlgorithm, int initialStepDelayMs) {
        printMaze(maze);
        validateMazeNeighbors(maze);
        if (DEFAULT_SHOW_NEIGHBORS) {
            printNeighborTable(maze);
        }

        runInteractiveSolveSession(
                maze,
                initialAlgorithm,
                initialStepDelayMs,
                DEFAULT_VISITED_COLOR);
    }

    /**
     * @brief Prompts for a maze text file.
     *
     * @return selected absolute file path, or {@code null} when canceled
     */
    private static String chooseMazeFile() {
        FileDialog openDialog = new FileDialog((Frame) null, "Select Maze File", FileDialog.LOAD);
        openDialog.setDirectory(new java.io.File(".").getAbsoluteFile().getParent());
        openDialog.setFile("*.msmap");
        openDialog.setVisible(true);

        String fileName = openDialog.getFile();
        String directory = openDialog.getDirectory();
        if (fileName == null) {
            System.out.println("No File Chosen");
            return null;
        }
        if (!fileName.toLowerCase().endsWith(".msmap")) {
            JOptionPane.showMessageDialog(
                    null,
                    "Please select a .msmap map file.",
                    "Maze File Type Error",
                    JOptionPane.ERROR_MESSAGE);
            return null;
        }
        return directory + fileName;
    }

    /**
     * @brief Applies the visual style used by title-screen action buttons.
     *
     * @param button title action button
     */
    private static void styleTitleButton(JButton button) {
        button.setPreferredSize(new Dimension(200, 60));
        button.setFont(button.getFont().deriveFont(20f));
        button.setBackground(new Color(65, 180, 220));
        button.setForeground(Color.BLACK);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createLineBorder(Color.BLACK, 2));
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
    }

    // ---------------------------------------------------------------------
    // Interactive solve session
    // ---------------------------------------------------------------------

    /**
     * @brief Starts a full interactive solve session for a maze.
      *
      * <p>This method resets renderer runtime state for a new maze file and prepares
      * the timer-driven incremental solver loop.
     *
     * @param maze maze to render and solve
     * @param initialAlgorithm initial algorithm label (DFS, BFS, UCS, Greedy, or A*)
     * @param initialStepDelayMs initial delay between solver steps in milliseconds
     * @param visitedColor color used for visited cells
     */
    private static void runInteractiveSolveSession(
            Maze maze,
            String initialAlgorithm,
            int initialStepDelayMs,
            Color visitedColor) {
        if (maze == null) {
            throw new IllegalArgumentException("Maze cannot be null.");
        }

        stopTimerIfRunning();

        sessionVisitedColor = visitedColor != null ? visitedColor : DEFAULT_VISITED_COLOR;
        buildMazeWindow(maze);
        applyControlDefaults(initialAlgorithm, initialStepDelayMs);
        resetTraversalCounter();
        setSolveTimeNs(0);
        setCurrentPathCost(-1);
        setFinalPathCost(-1);
        solveSessionWallStartNs = 0;
        solveSessionMode = "animated";
        solveSessionDelayMs = getSelectedStepDelayMsValue();

        solver = new MazeSolver();
        solver.setMaze(maze);
        solver.setAlg(getSelectedAlgorithmLabel());

        sessionStarted = false;
        solvingInstantly = false;
        solveTimer = new Timer(getSelectedStepDelayMsValue(), e -> onSolveTick());
        refreshRuntimeControlState();
    }

    /**
     * @brief Handles one timer tick of incremental solving.
     *
     * <p>Each tick performs exactly one call to {@link MazeSolver#updateSolve()} and
     * then updates UI state based on the returned node and terminal solver status.
     * When solved, the final path is colored and start/goal semantic colors are restored.
     */
    private static void onSolveTick() {
        syncTimerDelayWithUi();
        if (solveSessionWallStartNs == 0) {
            solveSessionWallStartNs = System.nanoTime();
            solveSessionDelayMs = getSelectedStepDelayMsValue();
            System.out.println("Solve debug: mode=animated, interval="
                    + solveSessionDelayMs + " ms, algorithm=" + solver.getAlg());
        }

        Maze.Node visited = solver.updateSolve();
        if (visited != null && visited != renderedMaze.getGoal() && visited != renderedMaze.getStart()) {
            setNodeColor(visited, sessionVisitedColor);
            traversedTiles++;
            setTraversedCount(traversedTiles);
        }

        setCurrentPathCost(solver.getCurrentPathCost());
        setSolveTimeNs(solver.getSolveTimeNs());

        if (!solver.isFinished()) {
            return;
        }

        stopTimerIfRunning();
        refreshRuntimeControlState();
        if (solver.isSolved()) {
            setFinalPathCost(solver.getPathCost());
            List<Point> finalPath = solver.getFinalPath();
            setFinalPathEdges(finalPath);
            setCellColors(finalPath, Color.CYAN);
            resetNodeColor(renderedMaze.getStart());
            resetNodeColor(renderedMaze.getGoal());
            printSolveDebugResult(true);
            return;
        }

        printSolveDebugResult(false);
        setFinalPathCost(-1);
    }

    /**
     * @brief Handles Start button action.
     *
     * <p>On first start after a restart, this resets solver search state while keeping
     * current control selections (algorithm, delay, and instant mode).
     * If Instant mode is selected, Start executes a background solve-to-completion.
     */
    private static void handleStartRequested() {
        if (solver == null || solveTimer == null) {
            return;
        }

        if (solvingInstantly) {
            return;
        }

        syncTimerDelayWithUi();
        if (!sessionStarted) {
            solver.setAlg(getSelectedAlgorithmLabel());
            solver.resetSearch();
            resetAllCellColors();
            resetTraversalCounter();
            sessionStarted = true;
        }

        if (isInstantSolveSelected()) {
            runInstantSolve();
            return;
        }

        if (!solver.isFinished() && !solveTimer.isRunning()) {
            solveTimer.start();
            refreshRuntimeControlState();
        }
    }


    /**
     * @brief Handles Restart button action and algorithm changes.
     *
     * <p>This preserves the currently loaded maze while clearing traversal coloring and
     * rebuilding solver state from the current algorithm selection.
     */
    private static void handleRestartRequested() {
        if (solver == null || solveTimer == null) {
            return;
        }

        stopTimerIfRunning();
        solver.setAlg(getSelectedAlgorithmLabel());
        solver.resetSearch();
        resetAllCellColors();
        resetTraversalCounter();
        setSolveTimeNs(0);
        setCurrentPathCost(-1);
        setFinalPathCost(-1);
        syncTimerDelayWithUi();
        solveSessionWallStartNs = 0;
        solveSessionMode = "animated";
        solveSessionDelayMs = getSelectedStepDelayMsValue();
        sessionStarted = false;
        solvingInstantly = false;
        refreshRuntimeControlState();
    }

    /**
     * @brief Runs solve loop to completion without per-step UI updates.
     *
     * <p>The display is updated only when solving has finished, either with a final path
     * (solved) or with an unsolved result. During this run, mode toggling is disabled
     * via {@link #refreshRuntimeControlState()}. Traversed-cell coloring and traversed
     * count semantics match timer mode (start/goal excluded), including restoration
     * of start/goal colors after final path painting.
     */
    private static void runInstantSolve() {
        if (solver == null || solvingInstantly || !isInstantSolveSelected()) {
            return;
        }

        stopTimerIfRunning();
        solvingInstantly = true;
        solveSessionWallStartNs = System.nanoTime();
        solveSessionMode = "instant";
        solveSessionDelayMs = 0;
        System.out.println("Solve debug: mode=instant, interval=none, algorithm=" + solver.getAlg());
        refreshRuntimeControlState();

        Thread worker = new Thread(() -> {
            List<Point> traversedPoints = new ArrayList<>();
            while (!solver.isFinished()
                    && !Thread.currentThread().isInterrupted()
                    && !shuttingDown) {
                Maze.Node visited = solver.updateSolve();
                if (visited != null && visited != renderedMaze.getStart() && visited != renderedMaze.getGoal()) {
                    traversedPoints.add(new Point(visited.col, visited.row));
                }
            }

            if (Thread.currentThread().isInterrupted() || shuttingDown) {
                return;
            }

            boolean solved = solver.isSolved();
            List<Point> finalPath = solved ? solver.getFinalPath() : null;

            SwingUtilities.invokeLater(() -> {
                if (shuttingDown) {
                    return;
                }
                solvingInstantly = false;
                traversedTiles = setCellColors(traversedPoints, sessionVisitedColor);
                setTraversedCount(traversedTiles);
                setCurrentPathCost(solver.getCurrentPathCost());
                setSolveTimeNs(solver.getSolveTimeNs());

                if (solved && finalPath != null) {
                    setFinalPathCost(solver.getPathCost());
                    setFinalPathEdges(finalPath);
                    setCellColors(finalPath, Color.CYAN);
                    resetNodeColor(renderedMaze.getStart());
                    resetNodeColor(renderedMaze.getGoal());
                    printSolveDebugResult(true);
                } else {
                    setFinalPathCost(-1);
                    printSolveDebugResult(false);
                }

                refreshRuntimeControlState();
            });
        }, "maze-instant-solve");

        worker.setDaemon(true);
        instantSolveWorker = worker;
        worker.start();
    }

    /**
     * Prints timing diagnostics without changing the user-facing solve metric.
     */
    private static void printSolveDebugResult(boolean solved) {
        double computeMs = solver.getSolveTimeNs() / 1_000_000.0;
        double wallMs = solveSessionWallStartNs == 0
                ? 0.0
                : (System.nanoTime() - solveSessionWallStartNs) / 1_000_000.0;
        String result = solved ? "goal reached" : "no path found";
        System.out.println("Solve debug: result=" + result
                + ", mode=" + solveSessionMode
                + ", interval=" + ("instant".equals(solveSessionMode)
                        ? "none" : solveSessionDelayMs + " ms")
                + ", algorithm=" + solver.getAlg()
                + ", visited=" + solver.getVisitedCount()
                        + ", pathCost=" + (solved ? solver.getPathCost() : -1)
                        + ", compute=" + String.format("%.4f", computeMs) + " ms"
                + ", wall=" + String.format("%.4f", wallMs) + " ms"
                + ", timingSource=monotonic search-step clock");
    }

    /**
     * @brief Stops the session timer if it is currently running.
     */
    private static void stopTimerIfRunning() {
        if (solveTimer != null && solveTimer.isRunning()) {
            solveTimer.stop();
        }
    }

    /**
     * @brief Resets traversal counter state and UI text.
     */
    private static void resetTraversalCounter() {
        traversedTiles = 0;
        setTraversedCount(0);
    }

    // ---------------------------------------------------------------------
    // Maze window construction
    // ---------------------------------------------------------------------

    /**
     * @brief Builds and displays the maze window with controls.
      *
      * <p>Large mazes are displayed inside a scroll pane and the frame is clamped to
      * available screen bounds.
     *
     * @param maze maze to render
     */
    private static void buildMazeWindow(Maze maze) {
        int rows = maze.getLength();
        int cols = maze.getWidth();

        if (mazeFrame != null) {
            mazeFrame.dispose();
        }

        renderedMaze = maze;
        renderedCellColors = new Color[rows][cols];
        finalPathEdges = new boolean[rows][cols][Maze.Dir.values().length];
        initializePaintedCellColors(rows, cols);
        renderedGridPanel = createPaintedGridPanel(rows, cols);

        JPanel controlPanel = buildControlPanel();

        renderedGridScrollPane = new JScrollPane(
                renderedGridPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        renderedGridScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        renderedGridScrollPane.getHorizontalScrollBar().setUnitIncrement(16);
        renderedGridScrollPane.addMouseWheelListener(e -> {
            if (!e.isControlDown()) {
                return;
            }
            if (e.getWheelRotation() < 0) {
                adjustZoom(1);
            } else if (e.getWheelRotation() > 0) {
                adjustZoom(-1);
            }
            e.consume();
        });

        mazeFrame = new JFrame("Maze Map");
        mazeFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        mazeFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                shutdownApplication();
            }
        });
        mazeFrame.setLayout(new BorderLayout());
        mazeFrame.add(renderedGridScrollPane, BorderLayout.CENTER);
        mazeFrame.add(controlPanel, BorderLayout.SOUTH);
        mazeFrame.pack();

        clampAndCenterMazeFrame(rows, cols, controlPanel);
        mazeFrame.setVisible(true);
    }

    /**
     * @brief Initializes backing colors for paint-render mode.
     *
     * @param rows maze row count
     * @param cols maze column count
     */
    private static void initializePaintedCellColors(int rows, int cols) {
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                renderedCellColors[row][col] = getDefaultCellColor(renderedMaze, row, col);
            }
        }
    }

     /**
      * @brief Creates the paint-based maze grid panel.
      *
      * <p>This renderer is used for all maze sizes. It avoids one Swing component per
      * cell, paints only the visible viewport region, and overlays subtle gridlines.
     *
     * @param rows maze row count
     * @param cols maze column count
     * @return configured paint panel
     */
    private static JPanel createPaintedGridPanel(int rows, int cols) {
        JPanel panel = new JPanel() {
            /**
             * @brief Paints only the visible maze viewport region.
             *
             * <p>Cell colors come from the renderer cache and gridlines are overlaid
             * for node separation. Start/goal labels are drawn when zoom is large enough.
             *
             * @param graphics paint context provided by Swing
             */
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                if (renderedMaze == null || renderedCellColors == null) {
                    return;
                }

                Rectangle clip = graphics.getClipBounds();
                int startRow = Math.max(0, (clip.y / cellSizePx) - 1);
                int endRow = Math.min(renderedMaze.getLength() - 1, (clip.y + clip.height) / cellSizePx + 1);
                int startCol = Math.max(0, (clip.x / cellSizePx) - 1);
                int endCol = Math.min(renderedMaze.getWidth() - 1, (clip.x + clip.width) / cellSizePx + 1);

                Maze.Node start = renderedMaze.getStart();
                Maze.Node goal = renderedMaze.getGoal();

                for (int row = startRow; row <= endRow; row++) {
                    for (int col = startCol; col <= endCol; col++) {
                        Color color = renderedCellColors[row][col];
                        graphics.setColor(color != null ? color : BLOCKED_CELL_COLOR);

                        int x = col * cellSizePx;
                        int y = row * cellSizePx;
                        graphics.fillRect(x, y, cellSizePx, cellSizePx);

                        if (cellSizePx >= 14 && start != null && start.row == row && start.col == col) {
                            graphics.setColor(Color.BLACK);
                            graphics.drawString("S", x + (cellSizePx / 3), y + (2 * cellSizePx / 3));
                        } else if (cellSizePx >= 14 && goal != null && goal.row == row && goal.col == col) {
                            graphics.setColor(Color.BLACK);
                            graphics.drawString("G", x + (cellSizePx / 3), y + (2 * cellSizePx / 3));
                        }
                    }
                }

                // Draw subtle grid lines so individual nodes are easier to identify.
                graphics.setColor(GRID_LINE_COLOR);
                int minX = startCol * cellSizePx;
                int maxX = (endCol + 1) * cellSizePx;
                int minY = startRow * cellSizePx;
                int maxY = (endRow + 1) * cellSizePx;

                for (int row = startRow; row <= endRow + 1; row++) {
                    int y = row * cellSizePx;
                    graphics.drawLine(minX, y, maxX, y);
                }
                for (int col = startCol; col <= endCol + 1; col++) {
                    int x = col * cellSizePx;
                    graphics.drawLine(x, minY, x, maxY);
                }

                if (isWeightOverlayVisible()) {
                    drawMovementCosts((Graphics2D) graphics, startRow, endRow, startCol, endCol);
                }
            }
        };

        panel.setPreferredSize(new Dimension(cols * cellSizePx, rows * cellSizePx));
        return panel;
    }

    /**
     * @brief Draws directional movement costs on visible grid edges.
     *
     * <p>Each geometric connection is drawn once from its north or west node.
     * Equal costs in opposite directions use one label. Different costs use two
     * labels with direction markers so asymmetric movement remains visible.
     *
     * @param graphics graphics context for the grid
     * @param startRow first visible row
     * @param endRow last visible row
     * @param startCol first visible column
     * @param endCol last visible column
     */
    private static void drawMovementCosts(
            Graphics2D graphics,
            int startRow,
            int endRow,
            int startCol,
            int endCol) {
        graphics.setFont(graphics.getFont().deriveFont(WEIGHT_LABEL_FONT_SIZE));

        for (int row = startRow; row <= endRow; row++) {
            for (int col = startCol; col <= endCol; col++) {
                Maze.Node node = renderedMaze.getNode(row, col);
                if (node == null) {
                    continue;
                }

                if (col < renderedMaze.getWidth() - 1) {
                    Maze.Node east = node.getNeighbor(Maze.Dir.EAST);
                    if (east != null) {
                        drawHorizontalCostLabels(
                                graphics,
                                (col + 1) * cellSizePx,
                                row * cellSizePx + (cellSizePx / 2),
                                node.getMoveCost(Maze.Dir.EAST),
                                east.getMoveCost(Maze.Dir.WEST),
                                getWeightDisplayState(node, east));
                    }
                }

                if (row < renderedMaze.getLength() - 1) {
                    Maze.Node south = node.getNeighbor(Maze.Dir.SOUTH);
                    if (south != null) {
                        drawVerticalCostLabels(
                                graphics,
                                col * cellSizePx + (cellSizePx / 2),
                                (row + 1) * cellSizePx,
                                node.getMoveCost(Maze.Dir.SOUTH),
                                south.getMoveCost(Maze.Dir.NORTH),
                                getWeightDisplayState(node, south));
                    }
                }
            }
        }
    }

    /**
     * Draws one horizontal edge's cost labels.
     */
    private static void drawHorizontalCostLabels(
            Graphics2D graphics,
            int edgeX,
            int centerY,
            int eastCost,
            int westCost,
            WeightDisplayState state) {
        if (eastCost == westCost) {
            drawCostLabel(graphics, Integer.toString(eastCost), edgeX, centerY, state);
            return;
        }

        drawCostLabel(graphics, ">" + eastCost, edgeX, centerY - 7, state);
        drawCostLabel(graphics, "<" + westCost, edgeX, centerY + 7, state);
    }

    /**
     * Draws one vertical edge's cost labels.
     */
    private static void drawVerticalCostLabels(
            Graphics2D graphics,
            int centerX,
            int edgeY,
            int southCost,
            int northCost,
            WeightDisplayState state) {
        if (southCost == northCost) {
            drawCostLabel(graphics, Integer.toString(southCost), centerX, edgeY, state);
            return;
        }

        drawCostLabel(graphics, "v" + southCost, centerX + 8, edgeY, state);
        drawCostLabel(graphics, "^" + northCost, centerX - 8, edgeY, state);
    }

    /**
     * Describes how a weight label should be styled based on the solve state.
     */
    private enum WeightDisplayState {
        NORMAL,
        TRAVERSED,
        FINAL_PATH
    }

    /**
     * Selects final-path styling before visited-node styling.
     */
    private static WeightDisplayState getWeightDisplayState(Maze.Node first, Maze.Node second) {
        Color firstColor = renderedCellColors[first.row][first.col];
        Color secondColor = renderedCellColors[second.row][second.col];
        if (isFinalPathEdge(first, second)) {
            return WeightDisplayState.FINAL_PATH;
        }
        if (sessionVisitedColor.equals(firstColor) || sessionVisitedColor.equals(secondColor)) {
            return WeightDisplayState.TRAVERSED;
        }
        return WeightDisplayState.NORMAL;
    }

    /**
     * Records only the consecutive node-to-node edges in the final solution.
     *
     * @param path solver path in goal-to-start order
     */
    private static void setFinalPathEdges(List<Point> path) {
        if (finalPathEdges == null) {
            return;
        }

        for (int row = 0; row < finalPathEdges.length; row++) {
            for (int col = 0; col < finalPathEdges[row].length; col++) {
                for (int direction = 0; direction < finalPathEdges[row][col].length; direction++) {
                    finalPathEdges[row][col][direction] = false;
                }
            }
        }

        if (path == null) {
            return;
        }

        for (int index = 0; index + 1 < path.size(); index++) {
            Point first = path.get(index);
            Point second = path.get(index + 1);
            if (first == null || second == null
                    || !renderedMaze.isInBounds(first.y, first.x)
                    || !renderedMaze.isInBounds(second.y, second.x)) {
                continue;
            }

            Maze.Dir firstDirection = directionBetween(first, second);
            Maze.Dir secondDirection = directionBetween(second, first);
            if (firstDirection != null && secondDirection != null) {
                finalPathEdges[first.y][first.x][firstDirection.idx()] = true;
                finalPathEdges[second.y][second.x][secondDirection.idx()] = true;
            }
        }
    }

    /**
     * @return true only when the two nodes form a consecutive final-path edge
     */
    private static boolean isFinalPathEdge(Maze.Node first, Maze.Node second) {
        if (finalPathEdges == null || first == null || second == null) {
            return false;
        }

        Maze.Dir direction = directionBetween(
                new Point(first.col, first.row),
                new Point(second.col, second.row));
        return direction != null && finalPathEdges[first.row][first.col][direction.idx()];
    }

    /**
     * Determines the cardinal direction from one coordinate to an adjacent one.
     */
    private static Maze.Dir directionBetween(Point from, Point to) {
        int rowDelta = to.y - from.y;
        int colDelta = to.x - from.x;
        if (rowDelta == -1 && colDelta == 0) {
            return Maze.Dir.NORTH;
        }
        if (rowDelta == 0 && colDelta == 1) {
            return Maze.Dir.EAST;
        }
        if (rowDelta == 1 && colDelta == 0) {
            return Maze.Dir.SOUTH;
        }
        if (rowDelta == 0 && colDelta == -1) {
            return Maze.Dir.WEST;
        }
        return null;
    }

    /**
     * Draws a compact cost label with state-specific styling and a background
     * for readability over visited and path cells.
     */
    private static void drawCostLabel(
            Graphics2D graphics,
            String text,
            int centerX,
            int centerY,
            WeightDisplayState state) {
        int padding = 2;
        int width = graphics.getFontMetrics().stringWidth(text);
        int height = graphics.getFontMetrics().getHeight();
        int x = centerX - (width / 2);
        int y = centerY + (height / 3);

        Color background = Color.WHITE;
        Color foreground = Color.BLACK;
        if (state == WeightDisplayState.TRAVERSED) {
            background = new Color(235, 235, 235, 205);
            foreground = TRAVERSED_WEIGHT_COLOR;
        } else if (state == WeightDisplayState.FINAL_PATH) {
            background = FINAL_PATH_WEIGHT_BACKGROUND;
            foreground = FINAL_PATH_WEIGHT_COLOR;
        }

        graphics.setColor(background);
        graphics.fillRect(x - padding, y - height + padding, width + (2 * padding), height);
        graphics.setColor(foreground);
        graphics.drawString(text, x, y);
    }

    /**
     * @brief Builds the bottom control panel and wires listeners.
         *
         * <p>Control semantics:
         * <ul>
         *   <li><b>Start</b>: begin or continue stepping</li>
         *   <li><b>Pause</b>: stop timer without resetting search</li>
         *   <li><b>Restart</b>: reset search and recolor cells</li>
         *   <li><b>Load Map</b>: replace current session maze from file</li>
         *   <li><b>Save Map</b>: export current session maze to file</li>
         *   <li><b>Generate Map</b>: replace current session maze with a new random map</li>
         *   <li><b>Step Delay</b>: 1..5000 ms for timer-driven solving</li>
         *   <li><b>Instant</b>: solve to completion with timer-mode-equivalent final output</li>
         *   <li><b>Zoom +/-</b>: adjust cell size in fixed increments</li>
         *   <li><b>Show Weights</b>: overlay directional movement costs at larger zoom levels</li>
         *   <li><b>Current Cost</b>: cost to the most recently visited node</li>
         *   <li><b>Final Cost</b>: total cost of the completed solution path</li>
         * </ul>
     *
     * @return constructed control panel
     */
    private static JPanel buildControlPanel() {
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 8));

        JButton startButton = new JButton("Start");
        JButton pauseButton = new JButton("Pause");
        JButton restartButton = new JButton("Restart");
        JButton loadMapButton = new JButton("Load Map");
        JButton saveMapButton = new JButton("Save Map");
        JButton generateMapButton = new JButton("Generate Map");
        JButton zoomOutButton = new JButton("-");
        JButton zoomInButton = new JButton("+");

        JLabel algorithmLabel = new JLabel("Algorithm:");
        JLabel delayLabel = new JLabel("Step Delay (ms):");
        JLabel instantSolveLabel = new JLabel("Instant:");
        JLabel zoomLabel = new JLabel("Zoom:");

        algorithmSelector = new JComboBox<>(new String[] { "DFS", "BFS", "UCS", "Greedy", "A*" });
        delaySpinner = new JSpinner(new SpinnerNumberModel(defaultStepDelayMs, 1, 5000, 1));
        instantSolveCheckBox = new JCheckBox();
        showWeightsCheckBox = new JCheckBox("Show Weights", DEFAULT_SHOW_WEIGHTS);
        showWeightsCheckBox.setToolTipText(
                "Show N/E/S/W movement costs. Zoom in to display labels; 0 means disabled.");
        traversedCountLabel = new JLabel("Traversed: " + defaultTraversedCount);
        solveTimeLabel = new JLabel("Solve Time: 0.0000 ms");
        currentCostLabel = new JLabel("Current Cost: --");
        finalCostLabel = new JLabel("Final Cost: --");

        algorithmSelector.addActionListener(e -> {
            if (!applyingControlDefaults) {
                handleRestartRequested();
            }
        });
        instantSolveCheckBox.addActionListener(e -> {
            if (solveTimer != null && solveTimer.isRunning()) {
                instantSolveCheckBox.setSelected(false);
            }
            refreshRuntimeControlState();
        });
        showWeightsCheckBox.addActionListener(e -> {
            if (renderedGridPanel != null) {
                renderedGridPanel.repaint();
            }
        });

        startButton.addActionListener(e -> handleStartRequested());
        pauseButton.addActionListener(e -> {
            stopTimerIfRunning();
            refreshRuntimeControlState();
        });
        restartButton.addActionListener(e -> handleRestartRequested());
        loadMapButton.addActionListener(e -> handleLoadMapRequested());
        saveMapButton.addActionListener(e -> handleSaveMapRequested());
        generateMapButton.addActionListener(e -> handleGenerateMapRequested());
        zoomOutButton.addActionListener(e -> adjustZoom(-1));
        zoomInButton.addActionListener(e -> adjustZoom(1));

        controlPanel.add(startButton);
        controlPanel.add(pauseButton);
        controlPanel.add(restartButton);
        controlPanel.add(loadMapButton);
        controlPanel.add(saveMapButton);
        controlPanel.add(generateMapButton);
        controlPanel.add(algorithmLabel);
        controlPanel.add(algorithmSelector);
        controlPanel.add(delayLabel);
        controlPanel.add(delaySpinner);
        controlPanel.add(instantSolveLabel);
        controlPanel.add(instantSolveCheckBox);
        controlPanel.add(zoomLabel);
        controlPanel.add(zoomOutButton);
        controlPanel.add(zoomInButton);
        controlPanel.add(showWeightsCheckBox);
        controlPanel.add(traversedCountLabel);
        controlPanel.add(solveTimeLabel);
        controlPanel.add(currentCostLabel);
        controlPanel.add(finalCostLabel);

        refreshRuntimeControlState();

        return controlPanel;
    }

    /**
     * @brief Loads a different maze file and restarts the session with it.
     */
    private static void handleLoadMapRequested() {
        String inputFile = chooseMazeFile();
        if (inputFile == null) {
            return;
        }

        String selectedAlgorithm = getSelectedAlgorithmLabel();
        int selectedDelay = getSelectedStepDelayMsValue();

        try {
            Maze maze = Maze.importFromFile(inputFile);
            launchMazeSession(maze, selectedAlgorithm, selectedDelay);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    mazeFrame,
                    "Failed to load maze: " + ex.getMessage(),
                    "Maze Load Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * @brief Saves the currently loaded maze to a text file.
     */
    private static void handleSaveMapRequested() {
        if (renderedMaze == null) {
            return;
        }

        String outputFile = chooseSaveMazeFile();
        if (outputFile == null) {
            return;
        }

        try {
            renderedMaze.exportToFile(outputFile);
            JOptionPane.showMessageDialog(
                    mazeFrame,
                    "Maze saved to:\n" + outputFile,
                    "Maze Saved",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    mazeFrame,
                    "Failed to save maze: " + ex.getMessage(),
                    "Save Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * @brief Generates a new random maze and restarts the session with it.
     */
    private static void handleGenerateMapRequested() {
        RandomMazeOptions options = promptForRandomMazeDimensions();
        if (options == null) {
            return;
        }

        String selectedAlgorithm = getSelectedAlgorithmLabel();
        int selectedDelay = getSelectedStepDelayMsValue();

        try {
            Maze maze = Maze.generateRandomMaze(
                    options.rows, options.columns, options.weighted,
                    options.minWeight, options.maxWeight,
                    options.sameCostBothDirections);
            launchMazeSession(maze, selectedAlgorithm, selectedDelay);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    mazeFrame,
                    "Failed to generate maze: " + ex.getMessage(),
                    "Maze Generation Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * @brief Prompts for destination path when saving a maze file.
     *
     * <p>The application stores maze files with a {@code .msmap} extension so
     * they remain visible in the import dialog.
     *
     * @return selected absolute file path, or null when canceled
     */
    private static String chooseSaveMazeFile() {
        FileDialog saveDialog = new FileDialog((Frame) null, "Save Maze File", FileDialog.SAVE);
        saveDialog.setDirectory(new java.io.File(".").getAbsoluteFile().getParent());
        saveDialog.setFile("maze.msmap");
        saveDialog.setVisible(true);

        String fileName = saveDialog.getFile();
        String directory = saveDialog.getDirectory();
        if (fileName == null) {
            return null;
        }
        if (!fileName.toLowerCase().endsWith(".msmap")) {
            fileName += ".msmap";
        }
        return directory + fileName;
    }

    /**
     * @brief Sizes maze window while keeping it within current screen bounds.
     *
     * @param rows maze row count
     * @param cols maze column count
     * @param controlPanel control panel used to estimate preferred height
     */
    private static void clampAndCenterMazeFrame(int rows, int cols, JPanel controlPanel) {
        Dimension preferredSize = mazeFrame.getPreferredSize();
        int controlsHeight = controlPanel.getPreferredSize().height;
        int verticalPadding = 36;

        int initialWidth = Math.max(cols * 35, preferredSize.width);
        int initialHeight = Math.max(rows * 35 + controlsHeight + verticalPadding, preferredSize.height);

        Rectangle maxWindowBounds = GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .getMaximumWindowBounds();

        int clampedWidth = Math.min(initialWidth, maxWindowBounds.width);
        int clampedHeight = Math.min(initialHeight, maxWindowBounds.height);

        mazeFrame.setSize(new Dimension(clampedWidth, clampedHeight));
        mazeFrame.setLocation(
            maxWindowBounds.x + (maxWindowBounds.width - clampedWidth) / 2,
            maxWindowBounds.y + (maxWindowBounds.height - clampedHeight) / 2);
    }

    // ---------------------------------------------------------------------
    // Control values and normalization
    // ---------------------------------------------------------------------

    /**
     * @brief Applies initial algorithm and delay defaults to current controls.
     *
     * @param algorithm preferred algorithm label
     * @param stepDelayMs preferred delay in milliseconds
     */
    private static void applyControlDefaults(String algorithm, int stepDelayMs) {
        if (algorithm != null && !algorithm.trim().isEmpty()) {
            defaultAlgorithm = normalizeAlgorithmLabel(algorithm);
        }
        defaultStepDelayMs = Math.max(1, stepDelayMs);

        if (algorithmSelector != null) {
            applyingControlDefaults = true;
            try {
                algorithmSelector.setSelectedItem(defaultAlgorithm);
            } finally {
                applyingControlDefaults = false;
            }
        }

        if (delaySpinner != null) {
            delaySpinner.setValue(defaultStepDelayMs);
        }
        refreshRuntimeControlState();
    }

    /**
     * @brief Reads currently selected algorithm from controls.
     *
     * @return canonical algorithm label
     */
    private static String getSelectedAlgorithmLabel() {
        if (algorithmSelector == null) {
            return defaultAlgorithm;
        }
        Object selected = algorithmSelector.getSelectedItem();
        if (selected == null) {
            return defaultAlgorithm;
        }
        return normalizeAlgorithmLabel(selected.toString());
    }

    /**
     * @brief Reads currently selected delay from controls.
     *
        * @return delay in milliseconds (minimum 1)
     */
    private static int getSelectedStepDelayMsValue() {
        if (delaySpinner == null) {
            return defaultStepDelayMs;
        }

        Object value = delaySpinner.getValue();
        if (value instanceof Number) {
            return Math.max(1, ((Number) value).intValue());
        }
        return defaultStepDelayMs;
    }

    /**
     * @brief Indicates whether instant-solve mode is selected in the UI.
     *
     * @return true if instant-solve checkbox is selected
     */
    private static boolean isInstantSolveSelected() {
        return instantSolveCheckBox != null && instantSolveCheckBox.isSelected();
    }

    /**
     * @brief Refreshes runtime control enablement based on current solve state.
     *
     * <p>Rules:
     * <ul>
      *   <li>Instant solve cannot be toggled while a solve is actively running</li>
     *   <li>Step delay spinner is disabled whenever instant solve is selected</li>
      *   <li>Both rules apply for timer mode and instant worker mode</li>
     * </ul>
     */
    private static void refreshRuntimeControlState() {
        boolean timerRunning = solveTimer != null && solveTimer.isRunning();
        boolean activelySolving = timerRunning || solvingInstantly;

        if (instantSolveCheckBox != null) {
            instantSolveCheckBox.setEnabled(!activelySolving);
        }

        if (delaySpinner != null) {
            delaySpinner.setEnabled(!isInstantSolveSelected());
        }
    }

    /**
     * @brief Updates timer delay to match current UI value.
     */
    private static void syncTimerDelayWithUi() {
        if (solveTimer == null) {
            return;
        }

        int selectedDelay = getSelectedStepDelayMsValue();
        if (solveTimer.getDelay() == selectedDelay) {
            return;
        }

        solveTimer.setDelay(selectedDelay);
        solveTimer.setInitialDelay(selectedDelay);
    }

    /**
     * @brief Normalizes an algorithm label to a supported search algorithm.
     *
     * @param algorithm input label
     * @return canonical label
     */
    private static String normalizeAlgorithmLabel(String algorithm) {
        String normalized = algorithm.trim().toUpperCase();
        if ("A_STAR".equals(normalized) || "ASTAR".equals(normalized)) {
            return "A*";
        }
        if ("A*".equals(normalized) || "BFS".equals(normalized) || "DFS".equals(normalized)) {
            return normalized;
        }
        if ("UCS".equals(normalized) || "UNIFORM COST".equals(normalized)
                || "UNIFORM_COST".equals(normalized)) {
            return "UCS";
        }
        if ("GREEDY".equals(normalized) || "GREEDY BEST-FIRST".equals(normalized)
                || "GREEDY_BEST_FIRST".equals(normalized)) {
            return "Greedy";
        }
        return "DFS";
    }

    // ---------------------------------------------------------------------
    // Cell color and appearance helpers
    // ---------------------------------------------------------------------

    /**
     * @brief Sets traversed-count label text.
     *
     * @param traversedCount traversed tile count
     */
    private static void setTraversedCount(int traversedCount) {
        defaultTraversedCount = Math.max(0, traversedCount);
        if (traversedCountLabel == null) {
            return;
        }

        SwingUtilities.invokeLater(
                () -> traversedCountLabel.setText("Traversed: " + defaultTraversedCount));
    }

    /**
     * @brief Sets solve-time label text.
     *
     * @param solveTimeNs solve time in nanoseconds
     */
    private static void setSolveTimeNs(long solveTimeNs) {
        if (solveTimeLabel == null) {
            return;
        }

        double timeMs = solveTimeNs / 1_000_000.0;
        Runnable update = () -> solveTimeLabel.setText(
                String.format("Solve Time: %.4f ms", timeMs));
        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        } else {
            SwingUtilities.invokeLater(update);
        }
    }

    /**
     * @brief Sets the live current-route cost label.
     *
     * @param cost cost to the most recently visited node, or -1 when unavailable
     */
    private static void setCurrentPathCost(long cost) {
        if (currentCostLabel == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> currentCostLabel.setText(
                cost >= 0 ? "Current Cost: " + cost : "Current Cost: --"));
    }

    /**
     * @brief Sets the final-route cost label.
     *
     * @param cost final path cost, or -1 when no solution is available
     */
    private static void setFinalPathCost(long cost) {
        if (finalCostLabel == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> finalCostLabel.setText(
                cost >= 0 ? "Final Cost: " + cost : "Final Cost: --"));
    }

    /**
     * Sets one node color using maze coordinates.
      *
      * <p>Typical usage is temporary overlays (for example hints or debug markers)
      * on top of solver coloring.
     *
     * @param node maze node to color
     * @param color target color
     * @return true when update was accepted
     */
    public static boolean setNodeColor(Maze.Node node, Color color) {
        if (node == null) {
            return false;
        }
        return setCellColor(node.row, node.col, color);
    }

    /**
     * Restores one node to its default semantic color.
      *
      * <p>Default semantic color rules are: start=green, goal=blue,
      * traversable=white, blocked=dark gray.
     *
     * @param node maze node to reset
     * @return true when update was accepted
     */
    public static boolean resetNodeColor(Maze.Node node) {
        if (node == null || renderedMaze == null) {
            return false;
        }
        return setCellColor(node.row, node.col, getDefaultCellColor(renderedMaze, node.row, node.col));
    }

    /**
     * @brief Sets one rendered cell color.
     *
     * @param row row index
     * @param col column index
     * @param color target color
     * @return true when update was accepted
     */
    private static boolean setCellColor(int row, int col, Color color) {
        if (!isRendererReady() || color == null || !renderedMaze.isInBounds(row, col)) {
            return false;
        }

        SwingUtilities.invokeLater(() -> {
            renderedCellColors[row][col] = color;
            if (renderedGridPanel != null) {
                repaintGridForWeightOverlay(row, col);
            }
        });
        return true;
    }

    /**
     * @brief Sets multiple rendered cell colors.
     *
     * @param cells cells where {@code x=col, y=row}
     * @param color color to apply
     * @return number of accepted updates
     */
    private static int setCellColors(List<Point> cells, Color color) {
        if (cells == null || color == null) {
            return 0;
        }

        int updated = 0;
        for (Point point : cells) {
            if (point == null || !renderedMaze.isInBounds(point.y, point.x)) {
                continue;
            }
            renderedCellColors[point.y][point.x] = color;
            updated++;
        }

        if (updated > 0 && renderedGridPanel != null) {
            repaintGridForWeightOverlay(cells);
        }
        return updated;
    }

    /**
     * Repaints the changed cell and its adjacent edge-label area.
     */
    private static void repaintGridForWeightOverlay(int row, int col) {
        if (renderedGridPanel == null) {
            return;
        }
        repaintGridRegion(row, col, row, col);
    }

    /**
     * Repaints all changed cells as one bounded region, expanded by one cell so
     * labels on connections to neighboring cells are included.
     */
    private static void repaintGridForWeightOverlay(List<Point> cells) {
        if (renderedGridPanel == null || cells == null || cells.isEmpty()) {
            return;
        }

        int minRow = renderedMaze.getLength();
        int minCol = renderedMaze.getWidth();
        int maxRow = -1;
        int maxCol = -1;
        for (Point point : cells) {
            if (point == null || !renderedMaze.isInBounds(point.y, point.x)) {
                continue;
            }
            minRow = Math.min(minRow, point.y);
            minCol = Math.min(minCol, point.x);
            maxRow = Math.max(maxRow, point.y);
            maxCol = Math.max(maxCol, point.x);
        }

        if (maxRow >= 0) {
            repaintGridRegion(minRow, minCol, maxRow, maxCol);
        }
    }

    /**
     * Repaints an inclusive cell rectangle with one-cell padding.
     */
    private static void repaintGridRegion(int minRow, int minCol, int maxRow, int maxCol) {
        int paddedMinRow = Math.max(0, minRow - 1);
        int paddedMinCol = Math.max(0, minCol - 1);
        int paddedMaxRow = Math.min(renderedMaze.getLength() - 1, maxRow + 1);
        int paddedMaxCol = Math.min(renderedMaze.getWidth() - 1, maxCol + 1);

        int labelMargin = Math.max(4, cellSizePx / 4);
        int x = Math.max(0, (paddedMinCol * cellSizePx) - labelMargin);
        int y = Math.max(0, (paddedMinRow * cellSizePx) - labelMargin);
        int right = Math.min(
                renderedMaze.getWidth() * cellSizePx,
                ((paddedMaxCol + 1) * cellSizePx) + labelMargin);
        int bottom = Math.min(
                renderedMaze.getLength() * cellSizePx,
                ((paddedMaxRow + 1) * cellSizePx) + labelMargin);
        int width = right - x;
        int height = bottom - y;
        renderedGridPanel.repaint(x, y, width, height);
    }

    /**
     * @return true when weight labels are currently being painted
     */
    private static boolean isWeightOverlayVisible() {
        return showWeightsCheckBox != null
                && showWeightsCheckBox.isSelected()
                && cellSizePx >= MIN_WEIGHT_LABEL_CELL_SIZE_PX;
    }

    /**
     * @brief Resets all rendered cells to their default maze-based style.
     */
    private static void resetAllCellColors() {
        if (!isRendererReady()) {
            return;
        }

        SwingUtilities.invokeLater(() -> {
            if (finalPathEdges != null) {
                for (int row = 0; row < finalPathEdges.length; row++) {
                    for (int col = 0; col < finalPathEdges[row].length; col++) {
                        for (int direction = 0; direction < finalPathEdges[row][col].length; direction++) {
                            finalPathEdges[row][col][direction] = false;
                        }
                    }
                }
            }
            for (int row = 0; row < renderedMaze.getLength(); row++) {
                for (int col = 0; col < renderedMaze.getWidth(); col++) {
                    renderedCellColors[row][col] = getDefaultCellColor(renderedMaze, row, col);
                }
            }
            if (renderedGridPanel != null) {
                renderedGridPanel.repaint();
            }
            if (mazeFrame != null) {
                mazeFrame.repaint();
            }
        });
    }

    /**
     * @brief Resolves the default color for a maze cell by semantic role.
     *
     * @param maze source maze
     * @param row row index
     * @param col column index
     * @return default cell color
     */
    private static Color getDefaultCellColor(Maze maze, int row, int col) {
        Maze.Node start = maze.getStart();
        Maze.Node goal = maze.getGoal();
        Maze.Node node = maze.getNode(row, col);

        if (start != null && start.row == row && start.col == col) {
            return START_CELL_COLOR;
        }
        if (goal != null && goal.row == row && goal.col == col) {
            return GOAL_CELL_COLOR;
        }
        if (!maze.isOccupied(row, col) || node == null) {
            return BLOCKED_CELL_COLOR;
        }
        return OPEN_CELL_COLOR;
    }

    /**
     * @brief Returns true when renderer cache has been initialized.
     *
     * @return true when maze and cell grid exist
     */
    private static boolean isRendererReady() {
        return renderedMaze != null && renderedCellColors != null;
    }

    // ---------------------------------------------------------------------
    // Zoom helpers
    // ---------------------------------------------------------------------

    /**
     * @brief Adjusts zoom by one configured step.
     *
     * @param direction +1 zoom in, -1 zoom out
     */
    private static void adjustZoom(int direction) {
        if (direction == 0) {
            return;
        }
        setCellSize(cellSizePx + (direction * ZOOM_STEP_PX));
    }

    /**
     * @brief Applies a new cell size while preserving viewport center.
      *
      * <p>The viewport keeps approximately the same visual center after zoom so users do
      * not lose context while navigating large mazes.
     *
     * @param requestedCellSizePx requested cell size in pixels
     */
    private static void setCellSize(int requestedCellSizePx) {
        if (!isRendererReady() || renderedGridPanel == null || renderedGridScrollPane == null) {
            cellSizePx = clampCellSize(requestedCellSizePx);
            return;
        }

        int clampedSize = clampCellSize(requestedCellSizePx);
        if (clampedSize == cellSizePx) {
            return;
        }

        int oldSize = cellSizePx;
        cellSizePx = clampedSize;

        JViewport viewport = renderedGridScrollPane.getViewport();
        Rectangle oldViewRect = viewport.getViewRect();
        double oldCenterX = oldViewRect.getCenterX();
        double oldCenterY = oldViewRect.getCenterY();

        renderedGridPanel.setPreferredSize(
                new Dimension(renderedMaze.getWidth() * cellSizePx, renderedMaze.getLength() * cellSizePx));

        renderedGridPanel.revalidate();
        renderedGridPanel.repaint();

        double scale = ((double) cellSizePx) / oldSize;
        int targetCenterX = (int) Math.round(oldCenterX * scale);
        int targetCenterY = (int) Math.round(oldCenterY * scale);

        SwingUtilities.invokeLater(() -> {
            Dimension viewSize = viewport.getExtentSize();
            Dimension fullSize = viewport.getViewSize();

            int targetX = targetCenterX - (viewSize.width / 2);
            int targetY = targetCenterY - (viewSize.height / 2);
            targetX = Math.max(0, Math.min(targetX, Math.max(0, fullSize.width - viewSize.width)));
            targetY = Math.max(0, Math.min(targetY, Math.max(0, fullSize.height - viewSize.height)));

            viewport.setViewPosition(new Point(targetX, targetY));
        });
    }

    /**
     * @brief Clamps a requested cell size to supported bounds.
     *
     * @param requestedCellSizePx requested cell size
     * @return clamped size
     */
    private static int clampCellSize(int requestedCellSizePx) {
        return Math.max(MIN_CELL_SIZE_PX, Math.min(MAX_CELL_SIZE_PX, requestedCellSizePx));
    }

    // ---------------------------------------------------------------------
    // Diagnostics and debug output
    // ---------------------------------------------------------------------

    /**
     * @brief Prints maze dimensions, start/end coordinates, and occupancy grid.
      *
      * <p>Intended for debug/validation workflows to verify import correctness.
     *
     * @param maze maze to print
     */
    private static void printMaze(Maze maze) {
        System.out.println(maze.getLength());
        System.out.println(maze.getWidth());

        Maze.Node start = maze.getStart();
        Maze.Node goal = maze.getGoal();

        System.out.println(start == null ? "start: unset" : "start: " + start.row + "," + start.col);
        System.out.println(goal == null ? "end: unset" : "end: " + goal.row + "," + goal.col);

        for (int row = 0; row < maze.getLength(); row++) {
            StringBuilder line = new StringBuilder();
            for (int col = 0; col < maze.getWidth(); col++) {
                line.append(maze.isOccupied(row, col) ? '1' : '0');
            }
            System.out.println(line);
        }
    }

    /**
     * @brief Validates maze neighbor consistency and prints issues.
     *
     * @param maze maze to validate
     */
    private static void validateMazeNeighbors(Maze maze) {
        final int maxPrintedIssues = 25;
        Maze.NeighborValidationResult result = maze.validateNeighborConnections(maxPrintedIssues);

        if (result.isValid()) {
            System.out.println("Neighbor validation: PASSED");
            return;
        }

        for (String issue : result.getIssues()) {
            System.out.println(issue);
        }

        System.out.println("Neighbor validation: FAILED (" + result.getIssueCount() + " issue(s))");
        if (result.getIssueCount() > maxPrintedIssues) {
            System.out.println("Only first " + maxPrintedIssues + " issue(s) shown.");
        }
    }

    /**
     * @brief Prints compact neighbor table lines for occupied cells.
     *
     * @param maze maze whose neighbor table is printed
     */
    private static void printNeighborTable(Maze maze) {
        for (String line : maze.buildNeighborTableLines()) {
            System.out.println(line);
        }
    }


    
    // ---------------------------------------------------------------------
    // Internal UI support classes
    // ---------------------------------------------------------------------

    /**
     * @brief JPanel that paints a stretched background image.
     */
    private static class BackgroundImagePanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private final transient BufferedImage backgroundImage;

        /**
         * @brief Creates a panel backed by the image at the given path.
         *
         * @param imagePath path to image file
         */
        BackgroundImagePanel(String imagePath) {
            BufferedImage loaded = null;
            try {
                loaded = loadImageFromClasspath(imagePath);
                if (loaded == null) {
                    loaded = ImageIO.read(new File(imagePath));
                }
                if (loaded == null) {
                    loaded = ImageIO.read(new File("src" + File.separator + imagePath));
                }
            } catch (Exception ignored) {
                loaded = null;
            }
            this.backgroundImage = loaded;
        }

        /**
         * @brief Loads image bytes from classpath for JAR-compatible startup assets.
         *
         * @param imagePath relative image path (for example, images/TitleImage.jpg)
         * @return decoded image, or null when no matching classpath resource exists
         */
        private static BufferedImage loadImageFromClasspath(String imagePath) {
            String normalizedPath = imagePath.replace('\\', '/');
            String[] candidates = {
                    normalizedPath,
                    "/" + normalizedPath,
                    "src/" + normalizedPath,
                    "/src/" + normalizedPath
            };

            for (String candidate : candidates) {
                try (InputStream stream = MazeRenderer.class.getResourceAsStream(candidate)) {
                    if (stream != null) {
                        BufferedImage image = ImageIO.read(stream);
                        if (image != null) {
                            return image;
                        }
                    }
                } catch (Exception ignored) {
                    // Try next candidate resource path.
                }
            }

            return null;
        }

        /**
         * @brief Paints stretched image or fallback solid background.
         */
        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (backgroundImage != null) {
                graphics.drawImage(backgroundImage, 0, 0, getWidth(), getHeight(), this);
                return;
            }

            graphics.setColor(new Color(30, 30, 30));
            graphics.fillRect(0, 0, getWidth(), getHeight());
        }
    }
}
