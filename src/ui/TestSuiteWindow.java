package ui;

import java.awt.BorderLayout;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import experiments.ExperimentResult;
import experiments.ExperimentRunner;
import experiments.TestSuite;
import experiments.TestSuiteIO;
import maze.map.Maze;

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
    private static TestSuiteWindow activeWindow;

    private final JFrame frame = new JFrame("Maze Solver - Configure Tests");
    private final JTextField suiteNameField = new JTextField("experiment-suite", 18);
    private final TestConfigurationTableModel configModel = new TestConfigurationTableModel();
    private final ExperimentResultTableModel resultModel = new ExperimentResultTableModel();
    private final JTable configTable = new TestConfigurationTable(configModel);
    private final JTable resultTable = new JTable(resultModel);
    private final List<ExperimentResult> resultRows = new java.util.ArrayList<>();
    private final JLabel statusLabel = new JLabel("Add or edit a test row.");
    private final Runnable onBack;
    private SwingWorker<List<ExperimentResult>, ExperimentResult> worker;

    private TestSuiteWindow(Runnable onBack) {
        this.onBack = onBack;
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
        activeWindow.cancelRun();
        activeWindow.frame.dispose();
        activeWindow = null;
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.add(new TestSuiteConfigurationPanel(
                suiteNameField,
                configTable,
                this::addDefaultRow,
                this::removeSelectedRow,
                this::saveSuite,
                this::loadSuite,
                this::returnToPreviousScreen), BorderLayout.CENTER);
        root.add(new ExperimentResultsPanel(
                resultTable,
                statusLabel,
                this::runSuite,
                this::cancelRun,
                this::exportResults,
                this::exportMaps,
                this::reviewResult), BorderLayout.SOUTH);
        return root;
    }

    private void returnToPreviousScreen() {
        closeWindow();
        if (onBack != null) {
            onBack.run();
        }
    }

    private void addDefaultRow() {
        configModel.addRow(new Object[] {
            "test-" + (configModel.getRowCount() + 1), 20, 20, 25,
            TestSuite.ReachabilityMode.GUARANTEED_SOLVABLE, TestSuite.CostMode.RANDOM,
            1, 5, true, TestSuite.EndpointMode.RANDOM,
            1, 1, 18, 18, 34, 1, 0L, TestSuite.MapSource.AUTOMATIC_SEED,
            10, "UCS,GREEDY,A_STAR", ""
        });
    }

    private void configureTableEditors() {
        configTable.getColumnModel().getColumn(3).setCellEditor(new IntegerEditor());
        configTable.getColumnModel().getColumn(4).setCellEditor(
                new EnumComboBoxEditor<>(TestSuite.ReachabilityMode.values(),
                        new String[] {"SOLVABLE", "UNSOLVABLE", "NATURAL"}));
        configTable.getColumnModel().getColumn(5).setCellEditor(
                new JComboBoxEditor<>(TestSuite.CostMode.values()));
        configTable.getColumnModel().getColumn(8).setCellEditor(
                new BooleanEditor());
        configTable.getColumnModel().getColumn(8).setCellRenderer(new BooleanRenderer());
        configTable.getColumnModel().getColumn(9).setCellEditor(
                new EnumComboBoxEditor<>(TestSuite.EndpointMode.values(),
                        new String[] {"FIXED", "RANDOM", "DISTANCE"}));
        configTable.getColumnModel().getColumn(4).setCellRenderer(
                new EnumLabelRenderer(new String[] {"SOLVABLE", "UNSOLVABLE", "NATURAL"}));
        configTable.getColumnModel().getColumn(9).setCellRenderer(
                new EnumLabelRenderer(new String[] {"FIXED", "RANDOM", "DISTANCE"}));
        configTable.getColumnModel().getColumn(16).setCellEditor(new MapValueEditor());
        configTable.getColumnModel().getColumn(16).setCellRenderer(new MapValueRenderer());
        configTable.getColumnModel().getColumn(17).setCellEditor(
                new EnumComboBoxEditor<>(TestSuite.MapSource.values(),
                        new String[] {"AUTO SEED", "MANUAL SEED", "SAVED MAP"}));
        configTable.getColumnModel().getColumn(17).setCellRenderer(
                new EnumLabelRenderer(new String[] {"AUTO SEED", "MANUAL SEED", "SAVED MAP"}));
        for (int column : new int[] {1, 2, 6, 7, 10, 11, 12, 13, 14, 15, 18}) {
            configTable.getColumnModel().getColumn(column).setCellEditor(new IntegerEditor());
        }
        configTable.getColumnModel().getColumn(19).setCellEditor(new AlgorithmEditor());

        int[] widths = {110, 55, 65, 75, 145, 90, 65, 65, 110, 125,
            70, 70, 70, 70, 105, 70, 115, 105, 75, 150, 0};
        for (int index = 0; index < widths.length; index++) {
            configTable.getColumnModel().getColumn(index).setPreferredWidth(widths[index]);
        }
        configTable.getColumnModel().getColumn(20).setMinWidth(0);
        configTable.getColumnModel().getColumn(20).setMaxWidth(0);
        configTable.getColumnModel().getColumn(20).setWidth(0);
        configModel.addTableModelListener(event -> {
            if (event.getColumn() == 17) {
                normalizeMapSourceRow(event.getFirstRow());
            }
            if (event.getColumn() == 9 || event.getColumn() == 16
                    || event.getColumn() == 17 || event.getColumn() == -1) {
                configTable.repaint();
            }
        });
    }

    private void normalizeMapSourceRow(int row) {
        if (row < 0 || row >= configModel.getRowCount()) {
            return;
        }
        TestSuite.MapSource source =
                (TestSuite.MapSource) configModel.getValueAt(row, 17);
        configModel.setValueAt("", row, 20);
        configModel.setValueAt(
                source == TestSuite.MapSource.SAVED_MAP ? "Import map..." : 0L,
                row, 16);
    }

    private void removeSelectedRow() {
        int row = configTable.getSelectedRow();
        if (row >= 0) {
            configModel.removeRow(row);
        }
    }

    private TestSuite readSuiteFromTable() {
        if (configTable.isEditing()) {
            configTable.getCellEditor().stopCellEditing();
        }
        TestSuite suite = new TestSuite(suiteNameField.getText());
        for (int row = 0; row < configModel.getRowCount(); row++) {
            TestSuite.TestCase testCase = new TestSuite.TestCase(text(row, 0));
            testCase.setRows(integer(row, 1));
            testCase.setColumns(integer(row, 2));
            testCase.setBlockedPercent(decimal(row, 3));
            testCase.setReachabilityMode(enumValue(row, 4, TestSuite.ReachabilityMode.class));
            testCase.setCostMode(enumValue(row, 5, TestSuite.CostMode.class));
            testCase.setMinimumCost(integer(row, 6));
            testCase.setMaximumCost(integer(row, 7));
            testCase.setSameCostBothDirections(booleanValue(row, 8));
            testCase.setEndpointMode(enumValue(row, 9, TestSuite.EndpointMode.class));
            testCase.setStartRow(integer(row, 10));
            testCase.setStartColumn(integer(row, 11));
            testCase.setGoalRow(integer(row, 12));
            testCase.setGoalColumn(integer(row, 13));
            testCase.setTargetManhattanDistance(integer(row, 14));
            testCase.setDistanceTolerance(integer(row, 15));
            TestSuite.MapSource source = enumValue(row, 17, TestSuite.MapSource.class);
            if (source == TestSuite.MapSource.MANUAL_SEED) {
                testCase.setSeed(Long.parseLong(text(row, 16)));
            } else if (source == TestSuite.MapSource.SAVED_MAP) {
                testCase.setSavedMapText(text(row, 20));
                String mapName = text(row, 16);
                if (!"Import map...".equals(mapName)) {
                    testCase.setSavedMapName(mapName);
                }
            } else {
                testCase.setMapSource(source);
            }
            testCase.setIterations(integer(row, 18));
            testCase.setAlgorithms(TestSuiteTableComponents.parseAlgorithms(text(row, 19)));
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
            resultModel.setRowCount(0);
            resultRows.clear();
            worker = new SwingWorker<List<ExperimentResult>, ExperimentResult>() {
                @Override
                protected List<ExperimentResult> doInBackground() {
                    return ExperimentRunner.run(suite, (completed, total, result) -> {
                        publish(result);
                        javax.swing.SwingUtilities.invokeLater(
                                () -> statusLabel.setText("Completed " + completed + " of " + total));
                    }, this::isCancelled);
                }

                @Override
                protected void process(List<ExperimentResult> results) {
                    for (ExperimentResult result : results) {
                        addResult(result);
                    }
                }

                @Override
                protected void done() {
                    statusLabel.setText(isCancelled() ? "Run cancelled." : "Run complete.");
                    try {
                        List<ExperimentResult> results = get();
                        if (suite.isAutomaticExport()) {
                            Path directory = suite.getExportDirectory() == null
                                    ? TestSuiteIO.defaultExportDirectory()
                                    : Paths.get(suite.getExportDirectory());
                            TestSuiteIO.exportCsv(results,
                                    directory.resolve(safeFileName(suite.getName()) + ".csv"));
                        }
                    } catch (Exception ex) {
                        showError("Experiment failed: " + rootCause(ex).getMessage());
                    }
                }
            };
            worker.execute();
        } catch (Exception ex) {
            showError("Invalid suite: " + rootCause(ex).getMessage());
        }
    }

    private void cancelRun() {
        if (worker != null && !worker.isDone()) {
            worker.cancel(false);
            statusLabel.setText("Cancellation requested...");
        }
    }

    private void saveSuite() {
        try {
            TestSuite suite = readSuiteFromTable();
            Path file = chooseFile("Save Test Suite", JFileChooser.SAVE_DIALOG);
            if (file != null) {
                file = withExtension(file, TestSuiteIO.SUITE_EXTENSION);
                TestSuiteIO.save(suite, file);
                statusLabel.setText("Suite saved.");
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
            configModel.setRowCount(0);
            for (TestSuite.TestCase testCase : suite.getTestCases()) {
                configModel.addRow(rowValues(testCase));
            }
            statusLabel.setText("Suite loaded.");
        } catch (Exception ex) {
            showError("Could not load suite: " + rootCause(ex).getMessage());
        }
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
                statusLabel.setText("Results exported.");
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
                statusLabel.setText("Maps exported to "
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
            row.getId(), row.getRows(), row.getColumns(), row.getBlockedPercent(),
            row.getReachabilityMode(), row.getCostMode(), row.getMinimumCost(),
            row.getMaximumCost(), row.isSameCostBothDirections(), row.getEndpointMode(),
            row.getStartRow(), row.getStartColumn(), row.getGoalRow(), row.getGoalColumn(),
            row.getTargetManhattanDistance(), row.getDistanceTolerance(),
            row.getMapSource() == TestSuite.MapSource.SAVED_MAP
                    ? (row.getSavedMapName() == null ? "Import map..." : row.getSavedMapName())
                    : row.getSeed(),
            row.getMapSource(), row.getIterations(),
            TestSuiteTableComponents.algorithmsText(row.getAlgorithms()),
            row.getSavedMapText() == null ? "" : row.getSavedMapText()
        };
    }

    private void addResult(ExperimentResult result) {
        resultRows.add(result);
        resultModel.addRow(new Object[] {
            result.getConfigurationId(), result.getIteration(), result.getAlgorithm(),
            result.isSolved(), result.getPathCost(), result.getPathLength(),
            result.getNodesExplored(), String.format(java.util.Locale.ROOT, "%.3f",
                    result.getRuntimeMs()), result.getMapSource(), result.getSeedLabel(), "Review"
        });
    }

    private void reviewResult(int row) {
        if (row < 0 || row >= resultRows.size()) {
            return;
        }
        ExperimentResult result = resultRows.get(row);
        try {
            MazeRenderer.reviewExperiment(
                    Maze.importFromText(result.getMapText()),
                    result.getAlgorithm(),
                    TestSuiteWindow::showWindow);
        } catch (Exception ex) {
            showError("Could not open result review: " + rootCause(ex).getMessage());
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
