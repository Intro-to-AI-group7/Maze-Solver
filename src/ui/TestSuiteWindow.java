package ui;

import java.awt.BorderLayout;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.Map;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import experiments.ExperimentResult;
import experiments.ExperimentRunner;
import experiments.TestSuite;
import experiments.TestSuiteIO;
import maze.map.Maze;
import maze.search.MazeSolver;

/**
 * Swing editor and execution window for configurable experiment suites.
 *
 * <p>The table intentionally keeps one row equal to one
 * {@code TestSuite.TestCase}. Experiment generation and solving remain in
 * {@code ExperimentRunner}; this
 * class only translates editor values and presents progress/results.
 */
@SuppressWarnings("auxiliaryclass")
public final class TestSuiteWindow {
    private static final int STATUS_LABEL_WIDTH = 180;
    private static final int MAX_LIVE_LOG_ENTRIES = 500;
    private static TestSuiteWindow activeWindow;

    private final JFrame frame = new JFrame("Maze Solver - Configure Tests");
    private final JTextField suiteNameField = new JTextField("experiment-suite", 18);
    private final JComboBox<TestSuite.ExecutionMode> executionModeCombo =
            new JComboBox<>(TestSuite.ExecutionMode.values());
    private final TestConfigurationTableModel configModel = new TestConfigurationTableModel();
    private final ExperimentResultTableModel resultModel = new ExperimentResultTableModel();
    private final TestConfigurationTable configTable = new TestConfigurationTable(configModel);
    private final JTable resultTable = new JTable(resultModel);
    private final JTextArea solveLog = new JTextArea();
    private final JTextArea mapLog = new JTextArea();
    private final Map<String, ExperimentRunner.SolveProgress> activeSolveProgress =
            new LinkedHashMap<>();
    private final Deque<String> completedSolveLog = new ArrayDeque<>();
    private final Deque<String> mapGenerationLog = new ArrayDeque<>();
    private final List<ExperimentResult> resultRows = new java.util.ArrayList<>();
    private final JLabel statusLabel = new JLabel("Add or edit a test row.");
    private final JProgressBar progressBar = new JProgressBar();
    private final JProgressBar mapProgressBar = new JProgressBar();
    private ExperimentResultsPanel resultsPanel;
    private int resultPageSize = 25;
    private int resultPage;
    private boolean followLatestResults;
    private TestSuite activeSuite;
    private final Timer memoryTimer;
    private final Timer viewRefreshTimer;
    private final Runnable onBack;
    private SwingWorker<List<ExperimentResult>, Object> worker;
    private boolean resultPageRefreshPending;
    private boolean solveLogRefreshPending;
    private volatile long suiteStartNanos;
    private volatile long suiteEndNanos;
    private volatile long metricWindowStartNanos;
    private volatile int metricWindowSolveCount;
    private volatile int completedSolveCount;
    private volatile double currentSolvesPerSecond;
    private final Object executionControlLock = new Object();
    private volatile boolean paused;

    private TestSuiteWindow(Runnable onBack) {
        this.onBack = onBack;
        solveLog.setEditable(false);
        solveLog.setLineWrap(false);
        solveLog.setFont(new java.awt.Font(
                java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        solveLog.setText("Completed solves will appear here while the suite runs.\n");
        mapLog.setEditable(false);
        mapLog.setLineWrap(false);
        mapLog.setFont(new java.awt.Font(
                java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        mapLog.setText(String.format(java.util.Locale.ROOT,
                "%-12s | %-32s | %-12s | %s%n",
                "EVENT", "CONFIGURATION", "ITERATION", "LOADED"));
        java.awt.Dimension statusSize = new java.awt.Dimension(STATUS_LABEL_WIDTH, 18);
        statusLabel.setPreferredSize(statusSize);
        statusLabel.setMinimumSize(statusSize);
        statusLabel.setMaximumSize(statusSize);
        statusLabel.setToolTipText(statusLabel.getText());
        progressBar.setPreferredSize(new java.awt.Dimension(200, 18));
        progressBar.setStringPainted(true);
        mapProgressBar.setPreferredSize(new java.awt.Dimension(200, 18));
        mapProgressBar.setStringPainted(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeWindow();
                if (TestSuiteWindow.this.onBack != null) {
                    TestSuiteWindow.this.onBack.run();
                }
            }
        });
        frame.setSize(1500, 750);
        frame.setLocationRelativeTo(null);
        frame.add(buildContent());
        UITheme.apply(frame);
        // UITheme applies the proportional application font recursively;
        // restore a character-cell font so diagnostic columns stay aligned.
        solveLog.setFont(new java.awt.Font(
                java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        mapLog.setFont(new java.awt.Font(
                java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        suiteStartNanos = System.nanoTime();
        suiteEndNanos = suiteStartNanos;
        metricWindowStartNanos = suiteStartNanos;
        memoryTimer = new Timer(250, event -> updateMemoryInfo());
        memoryTimer.start();
        viewRefreshTimer = new Timer(150, event -> refreshPendingViews());
        viewRefreshTimer.start();
        addDefaultRow();
        configureTableEditors();
    }

    /**
     * Opens a new test-suite window on the Swing event thread.
     */
    public static void showWindow() {
        showWindow(null);
    }

    /**
     * Opens the test-suite window and invokes the callback when the user
     * navigates back to the previous screen.
     *
     * @param onBack callback invoked after this window is closed
     */
    public static void showWindow(Runnable onBack) {
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
            javax.swing.SwingUtilities.invokeLater(() -> showWindow(onBack));
            return;
        }
        if (activeWindow == null) {
            activeWindow = new TestSuiteWindow(onBack);
        }
        activeWindow.frame.setVisible(true);
        activeWindow.frame.toFront();
        activeWindow.frame.requestFocus();
    }

    /**
     * Cancels any active experiment and closes the test-suite window.
     *
     * <p>This method is safe to call during application-wide shutdown.
     */
    public static void closeWindow() {
        if (activeWindow == null) {
            return;
        }
        activeWindow.memoryTimer.stop();
        activeWindow.viewRefreshTimer.stop();
        activeWindow.cancelRun();
        activeWindow.frame.dispose();
        activeWindow = null;
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(6, 6));
        JPanel configurationPanel = new TestSuiteConfigurationPanel(
                suiteNameField,
                configTable,
                this::addDefaultRow,
                this::duplicateSelectedRows,
                this::removeSelectedRow,
                this::saveSuite,
                this::loadSuite,
                this::returnToPreviousScreen,
                executionModeCombo);
        resultsPanel = new ExperimentResultsPanel(
                resultTable,
                solveLog,
                mapLog,
                statusLabel,
                progressBar,
                mapProgressBar,
                this::runSuite,
                this::cancelRun,
                this::togglePause,
                this::exportResults,
                this::exportMaps,
                this::reviewResult,
                this::setResultPageSize,
                this::goToResultPage,
                this::moveResultPage,
                this::setFollowLatestResults,
                this::setResultsView);
        JSplitPane splitPane = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, configurationPanel, resultsPanel);
        splitPane.setContinuousLayout(true);
        splitPane.setOneTouchExpandable(true);
        splitPane.setResizeWeight(0.72);
        splitPane.setDividerSize(8);
        splitPane.setToolTipText("Drag the divider to resize test setup and results.");
        root.add(splitPane, BorderLayout.CENTER);
        return root;
    }

    private void returnToPreviousScreen() {
        closeWindow();
        if (onBack != null) {
            onBack.run();
        }
    }

    private void addDefaultRow() {
        configModel.addRow(defaultRowValues());
    }

    private void insertDefaultRow(int row) {
        int insertionRow = Math.max(0, Math.min(row, configModel.getRowCount()));
        configModel.insertRow(insertionRow, defaultRowValues());
        configTable.setRowSelectionInterval(insertionRow, insertionRow);
    }

    private Object[] defaultRowValues() {
        return new Object[] {
            nextTestId(), "UCS,GREEDY,A_STAR", 10,
            TestSuite.MapSource.AUTOMATIC_SEED, 0L,
            100, 100, 50, TestSuite.ReachabilityMode.GUARANTEED_SOLVABLE,
            TestSuite.CostMode.RANDOM, 1, 5, true,
            TestSuite.EndpointMode.OPPOSITE_CORNERS, 1, 1, 98, 98, 194, 3, ""
        };
    }

    private String nextTestId() {
        int suffix = 1;
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int row = 0; row < configModel.getRowCount(); row++) {
            ids.add(String.valueOf(configModel.getValueAt(row, 0)));
        }
        while (ids.contains("test-" + suffix)) {
            suffix++;
        }
        return "test-" + suffix;
    }

    private void configureTableEditors() {
        configTable.getColumnModel().getColumn(8).setCellEditor(
                new EnumComboBoxEditor<>(TestSuite.ReachabilityMode.values(),
                        new String[] {"SOLVABLE", "UNSOLVABLE", "NATURAL"}));
        configTable.getColumnModel().getColumn(9).setCellEditor(
                new EnumComboBoxEditor<>(TestSuite.CostMode.values(),
                        new String[] {"UNIFORM", "RANDOM", "FROM MAP"}));
        configTable.getColumnModel().getColumn(9).setCellRenderer(
                new EnumLabelRenderer(new String[] {"UNIFORM", "RANDOM", "FROM MAP"}));
        configTable.getColumnModel().getColumn(12).setCellEditor(
                new BooleanEditor());
        configTable.getColumnModel().getColumn(12).setCellRenderer(new BooleanRenderer());
        configTable.getColumnModel().getColumn(13).setCellEditor(
                new EnumComboBoxEditor<>(TestSuite.EndpointMode.values(),
                        new String[] {"FIXED", "RANDOM", "OPPOSITE CORNERS", "DISTANCE"}));
        configTable.getColumnModel().getColumn(8).setCellRenderer(
                new EnumLabelRenderer(new String[] {"SOLVABLE", "UNSOLVABLE", "NATURAL"}));
        configTable.getColumnModel().getColumn(13).setCellRenderer(
                new EnumLabelRenderer(new String[] {
                    "FIXED", "RANDOM", "OPPOSITE CORNERS", "DISTANCE"
                }));
        configTable.getColumnModel().getColumn(3).setCellEditor(
                new EnumComboBoxEditor<>(TestSuite.MapSource.values(),
                        new String[] {"AUTO SEED", "MANUAL SEED", "SAVED MAP"}));
        configTable.getColumnModel().getColumn(3).setCellRenderer(
                new EnumLabelRenderer(new String[] {"AUTO SEED", "MANUAL SEED", "SAVED MAP"}));
        configTable.getColumnModel().getColumn(4).setCellEditor(new MapValueEditor());
        configTable.getColumnModel().getColumn(4).setCellRenderer(new MapValueRenderer());
        for (int column : new int[] {2, 5, 6, 7, 10, 11, 14, 15, 16, 17, 18, 19}) {
            configTable.getColumnModel().getColumn(column).setCellEditor(new IntegerEditor());
        }
        configTable.getColumnModel().getColumn(1).setCellEditor(new AlgorithmEditor());

        int[] widths = {110, 150, 75, 105, 115, 55, 65, 75, 145, 90,
            65, 65, 110, 125, 70, 70, 70, 70, 105, 70, 0};
        for (int index = 0; index < widths.length; index++) {
            configTable.getColumnModel().getColumn(index).setPreferredWidth(widths[index]);
        }
        configTable.getColumnModel().getColumn(20).setMinWidth(0);
        configTable.getColumnModel().getColumn(20).setMaxWidth(0);
        configTable.getColumnModel().getColumn(20).setWidth(0);
        configModel.addTableModelListener(event -> {
            if (event.getColumn() == 3) {
                normalizeMapSourceRow(event.getFirstRow());
            }
            if (event.getColumn() == 3 || event.getColumn() == 4 || event.getColumn() == 9
                    || event.getColumn() == 13 || event.getColumn() == -1) {
                configTable.repaint();
            }
        });
        configTable.installRowContextMenu(
                this::insertDefaultRow,
                this::duplicateSelectedRows,
                this::removeSelectedRow);
    }

    private void normalizeMapSourceRow(int row) {
        if (row < 0 || row >= configModel.getRowCount()) {
            return;
        }
        TestSuite.MapSource source =
                (TestSuite.MapSource) configModel.getValueAt(row, 3);
        configModel.setValueAt(
                source == TestSuite.MapSource.SAVED_MAP
                        ? TestSuite.CostMode.FROM_MAP : TestSuite.CostMode.RANDOM,
                row, 9);
        configModel.setValueAt("", row, 20);
        configModel.setValueAt(
                source == TestSuite.MapSource.SAVED_MAP ? "Import map..." : 0L,
                row, 4);
    }

    private void removeSelectedRow() {
        if (configTable.isEditing()) {
            configTable.getCellEditor().stopCellEditing();
        }
        int[] selectedRows = configTable.getSelectedRows();
        for (int index = selectedRows.length - 1; index >= 0; index--) {
            configModel.removeRow(selectedRows[index]);
        }
    }

    private void duplicateSelectedRows() {
        if (configTable.isEditing()) {
            configTable.getCellEditor().stopCellEditing();
        }
        int[] selectedRows = configTable.getSelectedRows();
        if (selectedRows.length == 0) {
            return;
        }
        int[] duplicatedRows = configModel.duplicateRows(selectedRows);
        configTable.clearSelection();
        for (int row : duplicatedRows) {
            configTable.addRowSelectionInterval(row, row);
        }
    }

    private TestSuite readSuiteFromTable() {
        if (configTable.isEditing()) {
            configTable.getCellEditor().stopCellEditing();
        }
        TestSuite suite = new TestSuite(suiteNameField.getText());
        suite.setExecutionMode((TestSuite.ExecutionMode) executionModeCombo.getSelectedItem());
        for (int row = 0; row < configModel.getRowCount(); row++) {
            TestSuite.TestCase testCase = new TestSuite.TestCase(text(row, 0));
            testCase.setAlgorithms(TestSuiteTableComponents.parseAlgorithms(text(row, 1)));
            testCase.setIterations(integer(row, 2));
            TestSuite.MapSource source = enumValue(row, 3, TestSuite.MapSource.class);
            testCase.setMapSource(source);
            if (source == TestSuite.MapSource.MANUAL_SEED) {
                testCase.setSeed(Long.parseLong(text(row, 4)));
            }
            testCase.setRows(integer(row, 5));
            testCase.setColumns(integer(row, 6));
            testCase.setBlockedPercent(decimal(row, 7));
            testCase.setReachabilityMode(enumValue(row, 8, TestSuite.ReachabilityMode.class));
            testCase.setCostMode(enumValue(row, 9, TestSuite.CostMode.class));
            testCase.setMinimumCost(integer(row, 10));
            testCase.setMaximumCost(integer(row, 11));
            testCase.setSameCostBothDirections(booleanValue(row, 12));
            testCase.setEndpointMode(enumValue(row, 13, TestSuite.EndpointMode.class));
            testCase.setStartRow(integer(row, 14));
            testCase.setStartColumn(integer(row, 15));
            testCase.setGoalRow(integer(row, 16));
            testCase.setGoalColumn(integer(row, 17));
            testCase.setTargetManhattanDistance(integer(row, 18));
            testCase.setDistanceTolerance(integer(row, 19));
            if (source == TestSuite.MapSource.SAVED_MAP) {
                testCase.setSavedMapText(text(row, 20));
                testCase.setCostMode(TestSuite.CostMode.FROM_MAP);
                String mapName = text(row, 4);
                if (!"Import map...".equals(mapName)) {
                    testCase.setSavedMapName(mapName);
                }
            }
            suite.addTestCase(testCase);
        }
        suite.validate();
        return suite;
    }

    private void runSuite() {
        if (worker != null && !worker.isDone()) {
            return;
        }
        try {
            TestSuite suite = readSuiteFromTable();
            activeSuite = suite;
            resultModel.setRowCount(0);
            resultRows.clear();
            completedSolveLog.clear();
            mapGenerationLog.clear();
            activeSolveProgress.clear();
            solveLog.setText("");
            mapLog.setText(String.format(java.util.Locale.ROOT,
                    "%-12s | %-32s | %-12s | %s%n",
                    "EVENT", "CONFIGURATION", "ITERATION", "LOADED"));
            resultPage = 0;
            refreshResultPage();
            int totalRuns = totalRuns(suite);
            progressBar.setMinimum(0);
            progressBar.setMaximum(totalRuns);
            progressBar.setValue(0);
            progressBar.setString("0 / " + totalRuns);
            progressBar.setStringPainted(true);
            int totalMaps = totalMaps(suite);
            mapProgressBar.setMinimum(0);
            mapProgressBar.setMaximum(totalMaps);
            mapProgressBar.setValue(0);
            mapProgressBar.setString("Maps: 0 / " + totalMaps);
            mapProgressBar.setStringPainted(true);
            suiteStartNanos = System.nanoTime();
            suiteEndNanos = 0L;
            paused = false;
            resultsPanel.setPaused(false);
            metricWindowStartNanos = suiteStartNanos;
            metricWindowSolveCount = 0;
            completedSolveCount = 0;
            currentSolvesPerSecond = 0.0;
            setStatusText(executionStatus(suite.getExecutionMode()));
            worker = new SwingWorker<List<ExperimentResult>, Object>() {
                @Override
                protected List<ExperimentResult> doInBackground() {
                    ExperimentRunner.ExecutionControl control = new ExperimentRunner.ExecutionControl() {
                        @Override
                        public boolean isCancelled() {
                            return TestSuiteWindow.this.worker == null
                                    || TestSuiteWindow.this.worker.isCancelled();
                        }

                        @Override
                        public boolean isPaused() {
                            return paused;
                        }

                        @Override
                        public void awaitIfPaused() {
                            synchronized (executionControlLock) {
                                while (paused && !isCancelled()) {
                                    try {
                                        executionControlLock.wait(250L);
                                    } catch (InterruptedException exception) {
                                        Thread.currentThread().interrupt();
                                        return;
                                    }
                                }
                            }
                        }
                    };
                    return ExperimentRunner.run(suite, (completed, total, result) -> {
                        completedSolveCount = completed;
                        publish(result);
                    }, control, progress -> publish(progress),
                            (generated, total, reservedBytes, admissionBudgetBytes,
                                    heapUsedBytes, maxHeapBytes, loadedMaps, message) ->
                                    publish(new MapProgressUpdate(
                                            generated, total, reservedBytes,
                                            admissionBudgetBytes, heapUsedBytes, maxHeapBytes,
                                            loadedMaps, message)));
                }

                @Override
                protected void process(List<Object> events) {
                    ExperimentResult latestResult = null;
                    for (Object event : events) {
                        if (event instanceof ExperimentRunner.SolveProgress) {
                            updateLiveSolveProgress((ExperimentRunner.SolveProgress) event);
                        } else if (event instanceof MapProgressUpdate) {
                            MapProgressUpdate progress = (MapProgressUpdate) event;
                            updateMapProgress(progress.generated, progress.total,
                                    progress.reservedBytes, progress.admissionBudgetBytes,
                                    progress.heapUsedBytes, progress.maxHeapBytes,
                                    progress.loadedMaps);
                            appendMapLog(progress);
                        } else if (event instanceof ExperimentResult) {
                            ExperimentResult result = (ExperimentResult) event;
                            resultRows.add(result);
                            appendSolveLog(result);
                            latestResult = result;
                        }
                    }
                    if (latestResult != null) {
                        setStatusText("Completed "
                                + latestResult.getConfigurationId() + " / iteration "
                                + latestResult.getIteration() + " / "
                                + latestResult.getAlgorithm());
                    }
                    if (followLatestResults) {
                        resultPage = pageCount() - 1;
                    }
                    requestResultPageRefresh();
                }

                @Override
                protected void done() {
                    suiteEndNanos = System.nanoTime();
                    if (isCancelled()) {
                        setStatusText("Run cancelled.");
                    } else {
                        progressBar.setValue(progressBar.getMaximum());
                        progressBar.setString(progressBar.getMaximum() + " / "
                                + progressBar.getMaximum());
                        mapProgressBar.setValue(mapProgressBar.getMaximum());
                        mapProgressBar.setString("Maps: " + mapProgressBar.getMaximum()
                                + " / " + mapProgressBar.getMaximum()
                                + " | reservation complete");
                        setStatusText("Run complete.");
                    }
                    try {
                        List<ExperimentResult> results = get();
                        if (suite.isAutomaticExport()) {
                            Path directory = suite.getExportDirectory() == null
                                    ? TestSuiteIO.defaultExportDirectory()
                                    : Paths.get(suite.getExportDirectory());
                            TestSuiteIO.exportCsv(results,
                                    directory.resolve(safeFileName(suite.getName()) + ".csv"));
                        }
                    } catch (CancellationException ex) {
                        // Cancellation is an expected end state when the user stops or restarts a run.
                    } catch (Exception ex) {
                        Throwable cause = rootCause(ex);
                        System.err.println("[TestSuiteWindow] Experiment failed.");
                        cause.printStackTrace(System.err);
                        showError("Experiment failed: " + cause.getMessage());
                    }
                }

            };
            worker.execute();
        } catch (Exception ex) {
            showError("Invalid suite: " + rootCause(ex).getMessage());
        }
    }

    private int totalRuns(TestSuite suite) {
        long total = 0;
        for (TestSuite.TestCase testCase : suite.getTestCases()) {
            total += (long) testCase.getIterations() * testCase.getAlgorithms().size();
        }

        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Suite contains too many algorithm runs.");
        }
        return (int) total;
    }

    private int totalMaps(TestSuite suite) {
        long total = 0;
        for (TestSuite.TestCase testCase : suite.getTestCases()) {
            total += testCase.getIterations();
        }
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Suite contains too many maps.");
        }
        return (int) total;
    }

    private void updateProgress(int completed, int total) {
        progressBar.setMaximum(total);
        progressBar.setValue(completed);
        progressBar.setString(completed + " / " + total);
    }

    private void updateMapProgress(
            int generated, int total, long reservedBytes, long admissionBudgetBytes,
            long heapUsedBytes, long maxHeapBytes, int loadedMaps) {
        mapProgressBar.setMaximum(total);
        mapProgressBar.setValue(generated);
        mapProgressBar.setString(String.format(java.util.Locale.ROOT,
                "Maps: %d / %d | loaded: %d | heap %.1f GB | reserved %.1f GB | max %.1f GB",
                generated, total, loadedMaps, heapUsedBytes / 1073741824.0,
                reservedBytes / 1073741824.0, maxHeapBytes / 1073741824.0));
        mapProgressBar.setToolTipText(String.format(java.util.Locale.ROOT,
                "Maps loaded and waiting for solver: %d; JVM heap used: %.1f GB; scheduler reservation: %.1f GB; "
                        + "admission budget: %.1f GB; JVM max heap: %.1f GB",
                loadedMaps, heapUsedBytes / 1073741824.0, reservedBytes / 1073741824.0,
                admissionBudgetBytes / 1073741824.0, maxHeapBytes / 1073741824.0));
    }

    private void appendMapLog(MapProgressUpdate progress) {
        if (progress.message == null || progress.message.isEmpty()) {
            return;
        }
        mapGenerationLog.addLast(progress.message + String.format(
                java.util.Locale.ROOT, " | loaded waiting: %4d", progress.loadedMaps));
        while (mapGenerationLog.size() > MAX_LIVE_LOG_ENTRIES) {
            mapGenerationLog.removeFirst();
        }
        StringBuilder text = new StringBuilder();
        for (String entry : mapGenerationLog) {
            text.append(entry).append('\n');
        }
        mapLog.setText(text.toString());
        mapLog.setCaretPosition(mapLog.getDocument().getLength());
    }

    private static final class MapProgressUpdate {
        private final int generated;
        private final int total;
        private final long reservedBytes;
        private final long admissionBudgetBytes;
        private final long heapUsedBytes;
        private final long maxHeapBytes;
        private final int loadedMaps;
        private final String message;

        private MapProgressUpdate(
                int generated, int total, long reservedBytes, long admissionBudgetBytes,
                long heapUsedBytes, long maxHeapBytes, int loadedMaps, String message) {
            this.generated = generated;
            this.total = total;
            this.reservedBytes = reservedBytes;
            this.admissionBudgetBytes = admissionBudgetBytes;
            this.heapUsedBytes = heapUsedBytes;
            this.maxHeapBytes = maxHeapBytes;
            this.loadedMaps = loadedMaps;
            this.message = message;
        }
    }

    private void setStatusText(String text) {
        String fullText = text == null ? "" : text;
        statusLabel.setToolTipText(fullText);
        java.awt.FontMetrics metrics = statusLabel.getFontMetrics(statusLabel.getFont());
        int availableWidth = STATUS_LABEL_WIDTH - 4;
        if (metrics.stringWidth(fullText) <= availableWidth) {
            statusLabel.setText(fullText);
            return;
        }
        String ellipsis = "...";
        int end = fullText.length();
        while (end > 0 && metrics.stringWidth(fullText.substring(0, end) + ellipsis)
                > availableWidth) {
            end--;
        }
        statusLabel.setText(fullText.substring(0, end) + ellipsis);
    }

    private void updateMemoryInfo() {
        if (resultsPanel == null) {
            return;
        }
        updateProgress(completedSolveCount, progressBar.getMaximum());
        long now = System.nanoTime();
        long elapsedNanos = Math.max(0L,
                (suiteEndNanos == 0L ? now : suiteEndNanos) - suiteStartNanos);
        long windowElapsedNanos = Math.max(1L, now - metricWindowStartNanos);
        int windowSolves = completedSolveCount - metricWindowSolveCount;
        currentSolvesPerSecond = windowSolves
                / (windowElapsedNanos / 1_000_000_000.0);
        metricWindowStartNanos = now;
        metricWindowSolveCount = completedSolveCount;
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double averageSolvesPerSecond = elapsedSeconds <= 0.0
                ? 0.0 : completedSolveCount / elapsedSeconds;
        resultsPanel.setMemoryInfo(String.format(java.util.Locale.ROOT,
                "Workers: solving %d (interval peak %d) / mapping %d (interval peak %d)    Elapsed: %s    "
                        + "Solves/sec: %.2f    Avg: %.2f",
                ExperimentRunner.getActiveSolverWorkerCount(),
                ExperimentRunner.getIntervalPeakSolverWorkerCount(),
                ExperimentRunner.getActiveMapWorkerCount(),
                ExperimentRunner.getIntervalPeakMapWorkerCount(),
                formatElapsedTime(elapsedSeconds),
                currentSolvesPerSecond,
                averageSolvesPerSecond));
    }

    private String formatElapsedTime(double elapsedSeconds) {
        long totalSeconds = (long) elapsedSeconds;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d",
                hours, minutes, seconds);
    }

    private void cancelRun() {
        if (worker != null && !worker.isDone()) {
            paused = false;
            synchronized (executionControlLock) {
                executionControlLock.notifyAll();
            }
            worker.cancel(false);
            setStatusText("Cancellation requested...");
        }
    }

    private void togglePause() {
        if (worker == null || worker.isDone()) {
            return;
        }
        paused = !paused;
        synchronized (executionControlLock) {
            executionControlLock.notifyAll();
        }
        resultsPanel.setPaused(paused);
        setStatusText(paused ? "Run paused." : "Run resumed.");
    }

    private void saveSuite() {
        try {
            TestSuite suite = readSuiteFromTable();
            Path file = chooseFile("Save Test Suite", JFileChooser.SAVE_DIALOG);
            if (file != null) {
                file = withExtension(file, TestSuiteIO.SUITE_EXTENSION);
                TestSuiteIO.save(suite, file);
                setStatusText("Suite saved.");
            }
        } catch (Exception ex) {
            showError("Could not save suite: " + rootCause(ex).getMessage());
        }
    }

    private void loadSuite() {
        try {
            Path file = chooseFile("Load Test Suite", JFileChooser.OPEN_DIALOG);
            if (file == null) {
                return;
            }
            TestSuite suite = TestSuiteIO.load(file);
            suiteNameField.setText(suite.getName());
            executionModeCombo.setSelectedItem(suite.getExecutionMode());
            configModel.setRowCount(0);
            for (TestSuite.TestCase testCase : suite.getTestCases()) {
                configModel.addRow(rowValues(testCase));
            }

            setStatusText("Suite loaded.");
        } catch (Exception ex) {
            showError("Could not load suite: " + rootCause(ex).getMessage());
        }
    }

    private String executionStatus(TestSuite.ExecutionMode mode) {
        if (mode == TestSuite.ExecutionMode.SEQUENTIAL) {
            return "Running sequentially.";
        }
        if (mode == TestSuite.ExecutionMode.ADAPTIVE_PARALLEL) {
            return "Running adaptively with up to "
                    + ExperimentRunner.getAdaptiveWorkerCount() + " workers.";
        }
        return "Running in parallel with "
                + ExperimentRunner.getParallelWorkerCount() + " workers.";
    }

    private void exportResults() {
        try {
            if (worker == null || !worker.isDone() || worker.isCancelled()) {
                showError("Run a complete test suite before exporting results.");
                return;
            }

            List<ExperimentResult> results = worker.get();
            Path file = chooseFile("Export Results CSV", JFileChooser.SAVE_DIALOG);
            if (file != null) {
                TestSuiteIO.exportCsv(results, file);
                setStatusText("Results exported.");
            }
        } catch (Exception ex) {
            showError("Could not export results: " + rootCause(ex).getMessage());
        }
    }

    private void exportMaps() {
        try {
            TestSuite suite = readSuiteFromTable();
            JFileChooser chooser = new JFileChooser(TestSuiteIO.defaultExportDirectory().toFile());
            chooser.setDialogTitle("Select Map Export Directory");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                Path parentDirectory = chooser.getSelectedFile().toPath();
                ExperimentRunner.exportMaps(suite, parentDirectory);
                setStatusText("Maps exported to "
                        + parentDirectory.resolve(safeFileName(suite.getName()) + "-maps"));
            }
        } catch (Exception ex) {
            showError("Could not export maps: " + rootCause(ex).getMessage());
        }
    }

    private static String safeFileName(String value) {
        String sanitized = value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return sanitized.isEmpty() ? "experiment-suite" : sanitized;
    }

    private Path chooseFile(String title, int mode) {
        JFileChooser chooser = new JFileChooser(TestSuiteIO.defaultExportDirectory().toFile());
        chooser.setDialogTitle(title);
        if (title.contains("Test Suite")) {
            chooser.setFileFilter(new FileNameExtensionFilter(
                    "Maze Solver test suites (*.mstests)", "mstests"));
        } else if (title.contains("CSV")) {
            chooser.setFileFilter(new FileNameExtensionFilter("CSV files (*.csv)", "csv"));
        }
        int result = mode == JFileChooser.SAVE_DIALOG
                ? chooser.showSaveDialog(frame) : chooser.showOpenDialog(frame);
        if (result != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        Path file = chooser.getSelectedFile().toPath();
        return title.contains("Test Suite") && mode == JFileChooser.SAVE_DIALOG
                ? withExtension(file, TestSuiteIO.SUITE_EXTENSION) : file;
    }

    private Object[] rowValues(TestSuite.TestCase row) {
        return new Object[] {
            row.getId(), TestSuiteTableComponents.algorithmsText(row.getAlgorithms()),
            row.getIterations(), row.getMapSource(),
            row.getMapSource() == TestSuite.MapSource.SAVED_MAP
                    ? (row.getSavedMapName() == null ? "Import map..." : row.getSavedMapName())
                    : row.getSeed(),
            row.getRows(), row.getColumns(), row.getBlockedPercent(),
            row.getReachabilityMode(), row.getCostMode(), row.getMinimumCost(),
            row.getMaximumCost(), row.isSameCostBothDirections(), row.getEndpointMode(),
            row.getStartRow(), row.getStartColumn(), row.getGoalRow(), row.getGoalColumn(),
            row.getTargetManhattanDistance(), row.getDistanceTolerance(),
            row.getSavedMapText() == null ? "" : row.getSavedMapText()
        };
    }

    private void reviewResult(int row) {
        int resultIndex = resultPage * resultPageSize + row;
        if (row < 0 || resultIndex >= resultRows.size()) {
            return;
        }
        ExperimentResult result = resultRows.get(resultIndex);
        try {
            TestSuite.TestCase testCase = findTestCase(result.getConfigurationId());
            Maze maze = testCase.getMapSource() == TestSuite.MapSource.SAVED_MAP
                    ? Maze.importFromText(testCase.getSavedMapText())
                    : Maze.generateExperimentMap(testCase, result.getEffectiveSeed()).getMaze();
            MazeRenderer.reviewExperiment(
                    maze,
                    result.getAlgorithm(),
                    TestSuiteWindow::showWindow);
        } catch (Exception ex) {
            showError("Could not open result review: " + rootCause(ex).getMessage());
        }
    }

    private TestSuite.TestCase findTestCase(String id) {
            if (activeSuite != null) {
                for (TestSuite.TestCase testCase : activeSuite.getTestCases()) {
                    if (testCase.getId().equals(id)) {
                        return testCase;
                    }
                }
            }
            throw new IllegalArgumentException("Test case '" + id + "' is not available.");
        }

    private void setResultPageSize(int pageSize) {
        pauseFollowingLatestResults();
        resultPageSize = pageSize;
        resultPage = 0;
        refreshResultPage();
    }

    private void goToResultPage(int pageNumber) {
        pauseFollowingLatestResults();
        resultPage = Math.max(0, Math.min(pageNumber - 1, pageCount() - 1));
        refreshResultPage();
    }

    private void moveResultPage(int delta) {
        pauseFollowingLatestResults();
        resultPage = Math.max(0, Math.min(resultPage + delta, pageCount() - 1));
        refreshResultPage();
    }

    private int pageCount() {
        return Math.max(1, (resultRows.size() + resultPageSize - 1) / resultPageSize);
    }

    private void setFollowLatestResults(boolean follow) {
        followLatestResults = follow;
        if (follow) {
            resultPage = pageCount() - 1;
            refreshResultPage();
        }
    }

    private void setResultsView(String view) {
        if ("Live solve log".equals(view)) {
            setStatusText("Live solve log follows completed solves.");
        }
    }

    private void appendSolveLog(ExperimentResult result) {
        String line = formatLogRow(
                "DONE", result.getConfigurationId(), result.getIteration(),
                result.getAlgorithm().toString(), result.isSolved() ? "yes" : "no",
                result.getPathCost(), result.getNodesExplored(), result.isSolved()
                        ? result.getPathLength() + 1 : -1, result.getRuntimeMs());
        completedSolveLog.addLast(line);
        while (completedSolveLog.size() > MAX_LIVE_LOG_ENTRIES) {
            completedSolveLog.removeFirst();
        }
        activeSolveProgress.remove(progressKey(
                result.getConfigurationId(), result.getIteration(), result.getAlgorithm()));
        requestSolveLogRefresh();
    }

    private void updateLiveSolveProgress(ExperimentRunner.SolveProgress progress) {
        String key = progressKey(
                progress.getConfigurationId(), progress.getIteration(), progress.getAlgorithm());
        if (progress.isFinished()) {
            activeSolveProgress.remove(key);
        } else {
            activeSolveProgress.put(key, progress);
        }
        requestSolveLogRefresh();
    }

    private void requestSolveLogRefresh() {
        solveLogRefreshPending = true;
    }

    private void requestResultPageRefresh() {
        resultPageRefreshPending = true;
    }

    private void refreshPendingViews() {
        if (resultPageRefreshPending) {
            resultPageRefreshPending = false;
            refreshResultPage();
        }
        if (solveLogRefreshPending) {
            solveLogRefreshPending = false;
            refreshSolveLog();
        }
    }

    private void refreshSolveLog() {
        // Theme changes and look-and-feel updates can replace component fonts;
        // enforce the character-cell font before rebuilding the fixed-width log.
        java.awt.Font logFont = new java.awt.Font(
                java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12);
        if (!logFont.equals(solveLog.getFont())) {
            solveLog.setFont(logFont);
        }
        StringBuilder text = new StringBuilder(logHeader());
        for (ExperimentRunner.SolveProgress progress : activeSolveProgress.values()) {
            text.append(formatLiveProgress(progress)).append(System.lineSeparator());
        }
        for (String line : completedSolveLog) {
            text.append(line);
        }
        solveLog.setText(text.toString());
        solveLog.setCaretPosition(solveLog.getDocument().getLength());
        // Keep following the newest row vertically without leaving the fixed
        // columns horizontally scrolled out of view.
        java.awt.Container parent = solveLog.getParent();
        if (parent instanceof javax.swing.JViewport) {
            javax.swing.JViewport viewport = (javax.swing.JViewport) parent;
            java.awt.Point position = viewport.getViewPosition();
            position.x = 0;
            viewport.setViewPosition(position);
        }
    }

    private String formatLiveProgress(ExperimentRunner.SolveProgress progress) {
        return formatLogRow(
                "ACTIVE", progress.getConfigurationId(), progress.getIteration(),
                progress.getAlgorithm().toString(), "running", progress.getPathCost(),
                progress.getNodesExplored(), -1, progress.getElapsedNs() / 1_000_000.0);
    }

    private String logHeader() {
        return String.format(java.util.Locale.ROOT,
                "%-6.6s  %-24.24s  i=%-4.4s  %-16.16s  "
                        + "status=%-7.7s  cost=%-8.8s  visited=%-8.8s  "
                        + "path=%-6.6s  runtime=%8.8s ms%n",
                "STATE", "configuration", "iter", "algorithm", "status", "cost",
                "visited", "path", "runtime");
    }

    private String formatLogRow(
            String state, String configuration, int iteration, String algorithm,
            String status, long cost, int visitedNodes, int pathNodes, double runtimeMs) {
        return String.format(java.util.Locale.ROOT,
                "%-6.6s  %-24.24s  i=%-4.4s  %-16.16s  "
                        + "status=%-7.7s  cost=%-8.8s  visited=%-8.8s  "
                        + "path=%-6.6s  runtime=%8.8s ms%n",
                state, configuration,
                iteration <= 0 ? "iter" : Integer.toString(iteration),
                algorithm, status,
                cost < 0 ? "-" : Long.toString(cost),
                visitedNodes < 0 ? "-" : Integer.toString(visitedNodes),
                pathNodes < 0 ? "-" : Integer.toString(pathNodes),
                runtimeMs <= 0.0 ? "-" : String.format(java.util.Locale.ROOT, "%.3f", runtimeMs));
    }

    private String progressKey(
            String configurationId, int iteration, MazeSolver.Algorithm algorithm) {
        return configurationId + '\u0000' + iteration + '\u0000' + algorithm;
    }

    /**
     * Keeps the diagnostic log bounded while the complete result list remains
     * available for the table and exports.
     */
    private void pauseFollowingLatestResults() {
        followLatestResults = false;
    }

    private void refreshResultPage() {
        resultModel.setRowCount(0);
        int start = Math.min(resultRows.size(), resultPage * resultPageSize);
        int end = Math.min(resultRows.size(), start + resultPageSize);
        for (int index = start; index < end; index++) {
            ExperimentResult result = resultRows.get(index);
            resultModel.addRow(new Object[] {
                result.getConfigurationId(), result.getIteration(), result.getAlgorithm(),
                result.isSolved(), result.getPathCost(), result.getPathLength(),
                result.getNodesExplored(), String.format(java.util.Locale.ROOT, "%.3f",
                        result.getRuntimeMs()), result.getMapSource(), result.getSeedLabel(), "Review"
            });
        }
        if (resultsPanel != null) {
            resultsPanel.setPageInfo("Page " + (resultPage + 1) + " of " + pageCount());
        }
    }

    private String text(int row, int column) {
        return String.valueOf(configModel.getValueAt(row, column)).trim();
    }

    private int integer(int row, int column) {
        return Integer.parseInt(text(row, column));
    }

    private double decimal(int row, int column) {
        return Double.parseDouble(text(row, column));
    }

    private boolean booleanValue(int row, int column) {
        return Boolean.parseBoolean(text(row, column));
    }

    private <T extends Enum<T>> T enumValue(int row, int column, Class<T> type) {
        return Enum.valueOf(type, text(row, column).toUpperCase());
    }

    private static Path withExtension(Path file, String extension) {
        String name = file.getFileName().toString();
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(extension)) {
            return file;
        }
        return file.resolveSibling(name + extension);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(frame, message, "Test Suite Error", JOptionPane.ERROR_MESSAGE);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable result = throwable;
        while (result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }
}
