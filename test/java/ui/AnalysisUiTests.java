package ui;

import analysis.ResultFiles;
import analysis.ResultQuery;
import analysis.ResultRecord;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.JTextField;

/** Headless Swing integration checks for global queries, paging, and record identity. */
public final class AnalysisUiTests {
    private AnalysisUiTests() { }
    /** Uses the deterministic CSV emitted by test.AnalysisTests --artifacts. */
    public static void main(String[] args) throws Exception {
        List<ResultRecord> fixture = ResultFiles.load(Path.of(args[0]));
        List<ResultRecord> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) { rows.addAll(fixture); }
        LinkedBlockingQueue<List<ResultRecord>> selections = new LinkedBlockingQueue<>();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<ResultAnalysisPanel> panel = new AtomicReference<>();
        AtomicReference<JTable> resultTable = new AtomicReference<>();
        AtomicReference<ResultRecord> latestResult = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            checkConfigurationQuery();
            panel.set(new ResultAnalysisPanel((rowsSelected, latest) -> {
                latestResult.set(latest < 0 ? null : rowsSelected.get(latest));
                selections.add(rowsSelected);
            },
                    ignored -> { }, failure::set));
            resultTable.set(new JTable(new ExperimentResultTableModel()));
            panel.get().installTableHeader(resultTable.get());
            panel.get().setSource(rows);
        });
        try {
            List<ResultRecord> initial = selections.poll(10, TimeUnit.SECONDS);
            require(initial != null && initial.size() == 60, "Global source was not loaded.");
            SwingUtilities.invokeAndWait(() -> clickHeader(resultTable.get(), 7));
            List<ResultRecord> sorted = selections.poll(10, TimeUnit.SECONDS);
            require(sorted != null && sorted.get(0).result().getRuntimeMs() == 9,
                    "Header sorting did not use the complete dataset.");
            SwingUtilities.invokeAndWait(() -> {
                ExperimentResultTableModel model = new ExperimentResultTableModel();
                model.setPage(sorted.subList(25, 50));
                require(model.getRowCount() == 25 && model.recordAt(0) == sorted.get(25),
                        "Review record identity changed after global sorting and paging.");
                require(model.copyValueAt(0, 7).equals(Double.toString(sorted.get(25).result().getRuntimeMs()))
                        && model.copyRowAt(0).split("\t", -1).length == 14,
                        "Copy must use the displayed record and omit the Review action.");
                try {
                    var filters = ResultAnalysisPanel.class.getDeclaredField("filterText");
                    filters.setAccessible(true);
                    JTextField input = (JTextField) filters.get(panel.get());
                    input.setText("algorithm=UCS;runtime_ms<100"); input.postActionEvent();
                } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
            });
            List<ResultRecord> filtered = selections.poll(10, TimeUnit.SECONDS);
            require(filtered != null && filtered.size() == 24 && filtered.stream().allMatch(
                    r -> r.result().getAlgorithm() == maze.search.MazeSolver.Algorithm.UCS),
                    "Swing filter apply failed across pages.");
            require(latestResult.get() == fixture.get(3), "Follow must find the newest matching arrival.");
            SwingUtilities.invokeAndWait(() -> {
                var batch = new ArrayList<>(rows);
                batch.add(fixture.get(0));
                panel.get().updateSource(batch);
                batch.add(fixture.get(1));
                panel.get().updateSource(batch);
            });
            List<ResultRecord> firstBatch = selections.poll(10, TimeUnit.SECONDS);
            List<ResultRecord> secondBatch = selections.poll(10, TimeUnit.SECONDS);
            require(firstBatch != null && firstBatch.size() == 25 && secondBatch != null && secondBatch.size() == 26,
                    "New results should not prevent the in-progress live snapshot from reaching the table.");
            SwingUtilities.invokeAndWait(() -> panel.get().setView("Graphs"));
            require(selections.poll(10, TimeUnit.SECONDS) != null, "Graph background query did not complete.");
            SwingUtilities.invokeAndWait(() -> {
                choose(panel.get(), "view", analysis.ResultChart.View.ITERATIONS);
                choose(panel.get(), "type", analysis.ResultChart.Type.LINE);
                choose(panel.get(), "xAxis", ResultQuery.Field.ALGORITHM);
                clickHeader(resultTable.get(), 7);
            });
            List<ResultRecord> withInvalidChart = selections.poll(10, TimeUnit.SECONDS);
            require(withInvalidChart != null && withInvalidChart.get(0).result().getRuntimeMs() == 11,
                    "Invalid chart settings blocked a valid table sort.");
            require(failure.get() == null, "Swing analysis failed: " + failure.get());
            System.out.println("Swing checks passed: configuration and result queries, live following, copy values, and graph worker.");
        } finally { SwingUtilities.invokeAndWait(() -> panel.get().close()); }
        if (args.length == 2) { windowSmoke(fixture, Path.of(args[1])); }
    }

    private static void clickHeader(JTable table, int column) {
        var header = table.getTableHeader();
        int x = header.getHeaderRect(column).x + 5;
        header.dispatchEvent(new MouseEvent(header, MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, x, 5, 1, false, MouseEvent.BUTTON1));
    }

    private static void checkConfigurationQuery() {
        var model = new TestConfigurationTableModel();
        model.addRow(new Object[] {"ten", "UCS", "10"});
        model.addRow(new Object[] {"two", "UCS,A_STAR", 2});
        model.addRow(new Object[] {"hundred", "A_STAR", 100});
        var table = new TestConfigurationTable(model);
        var filter = new JTextField("algorithms~UCS");
        var sort = new JTextField("CONFIGURATION:desc");
        var controls = new TableHeaderQuery(filter, sort,
                () -> table.applyQuery(filter.getText(), sort.getText()));
        var field = TestConfigurationTableModel.Field.ITERATIONS;
        invokeMenu(controls.menu(table, "Iterations", field, true), "Sort ascending");
        require(sort.getText().equals("iterations:asc")
                && table.getRowCount() == 2 && table.convertRowIndexToModel(0) == 1,
                "Configuration header sort must update its text and compare numbers numerically.");
        invokeMenu(controls.menu(table, "Iterations", field, true), "Sort descending");
        require(table.convertRowIndexToModel(0) == 0, "Descending sort did not reverse the numeric order.");
        controls.addFilter(field, ">=", "5");
        require(filter.getText().equals("algorithms~UCS;iterations>=5")
                && table.getRowCount() == 1 && model.getRowCount() == 3,
                "A configuration filter must keep existing clauses and hide rows only in the view.");
        invokeMenu(controls.menu(table, "Iterations", field, true), "Clear column filters");
        require(filter.getText().equals("algorithms~UCS") && table.getRowCount() == 2,
                "Clearing a column filter must preserve filters for other columns.");
        sort.setText("ITERATIONS:asc,configuration:desc");
        invokeMenu(controls.menu(table, "Iterations", field, true), "Add descending sort key");
        require(sort.getText().equals("iterations:desc,configuration:desc"),
                "Changing a sort key must preserve its priority.");
        invokeMenu(controls.menu(table, "Iterations", field, true), "Clear column sort");
        require(sort.getText().equals("configuration:desc")
                && table.convertRowIndexToModel(0) == 1, "Clearing one key must keep the other key.");
        table.setRowSelectionInterval(0, 0);
        int[] selected = table.selectedModelRows();
        require(selected.length == 1 && selected[0] == 1,
                "Row actions must target the selected configuration after sorting.");
        model.duplicateRows(selected);
        require(model.getValueAt(2, 0).equals("two-copy") && table.getRowCount() == 3,
                "Duplicating a displayed configuration must update the filtered view.");
        model.setValueAt("BFS", 2, 1);
        require(table.getRowCount() == 2 && model.getRowCount() == 4,
                "Editing an active filter field must update visibility without deleting rows.");
    }

    private static void invokeMenu(JPopupMenu menu, String label) {
        for (var component : menu.getComponents()) {
            if (component instanceof JMenuItem item && item.getText().equals(label)) {
                require(item.isEnabled(), "Expected an enabled menu item " + label);
                item.doClick(0);
                return;
            }
        }
        throw new AssertionError("Missing header action " + label);
    }
    private static void choose(ResultAnalysisPanel panel, String fieldName, Object value) {
        try {
            var field = ResultAnalysisPanel.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            ((javax.swing.JComboBox<?>) field.get(panel)).setSelectedItem(value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
    private static void windowSmoke(List<ResultRecord> rows, Path screenshot) throws Exception {
        AtomicReference<TestSuiteWindow> window = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                TestSuiteWindow.showWindow();
                var active = TestSuiteWindow.class.getDeclaredField("activeWindow"); active.setAccessible(true);
                window.set((TestSuiteWindow) active.get(null));
                var open = TestSuiteWindow.class.getDeclaredMethod("openAnalysisResults", List.class); open.setAccessible(true);
                open.invoke(window.get(), rows);
                var results = TestSuiteWindow.class.getDeclaredField("resultsPanel"); results.setAccessible(true);
                ((ExperimentResultsPanel) results.get(window.get())).showGraphs();
            } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
            while (!ready.get() && System.nanoTime() < deadline) {
                Thread.sleep(50);
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        var field = TestSuiteWindow.class.getDeclaredField("analysisPanel"); field.setAccessible(true);
                        var image = ResultAnalysisPanel.class.getDeclaredField("chartImage"); image.setAccessible(true);
                        ready.set(image.get(field.get(window.get())) != null);
                    } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
                });
            }
            require(ready.get(), "Full-window graph never rendered.");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var field = TestSuiteWindow.class.getDeclaredField("frame"); field.setAccessible(true);
                    var frame = (javax.swing.JFrame) field.get(window.get());
                    frame.setSize(1500, 950); frame.validate();
                    var image = new java.awt.image.BufferedImage(frame.getWidth(), frame.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                    var graphics = image.createGraphics();
                    try { frame.printAll(graphics); } finally { graphics.dispose(); }
                    javax.imageio.ImageIO.write(image, "png", screenshot.toFile());
                } catch (ReflectiveOperationException | java.io.IOException ex) { throw new IllegalStateException(ex); }
            });
            System.out.println("Full desktop-window screenshot: " + screenshot.toAbsolutePath());
            checkFollowScroll(window.get(), rows);
            checkHeaderMenus(window.get());
        } finally { SwingUtilities.invokeAndWait(TestSuiteWindow::closeWindow); }
    }

    private static void checkHeaderMenus(TestSuiteWindow window) throws Exception {
        var resultField = TestSuiteWindow.class.getDeclaredField("resultTable");
        var configField = TestSuiteWindow.class.getDeclaredField("configTable");
        resultField.setAccessible(true);
        configField.setAccessible(true);
        JTable results = (JTable) resultField.get(window);
        JTable configs = (JTable) configField.get(window);
        SwingUtilities.invokeAndWait(() -> {
            for (JTable table : List.of(results, configs)) {
                var header = table.getTableHeader();
                var manager = javax.swing.MenuSelectionManager.defaultManager();
                header.dispatchEvent(new MouseEvent(header, MouseEvent.MOUSE_RELEASED,
                        System.currentTimeMillis(), 0, 8, 8, 1, true, MouseEvent.BUTTON3));
                try {
                    var menu = Arrays.stream(manager.getSelectedPath())
                            .filter(JPopupMenu.class::isInstance).map(JPopupMenu.class::cast).findFirst();
                    require(menu.isPresent(), "Right-click must open a column header menu.");
                    invokeMenu(menu.orElseThrow(), "Sort descending");
                } finally {
                    manager.clearSelectedPath();
                }
            }
        });
        System.out.println("Desktop header menu check passed for both tables.");
    }

    private static void checkFollowScroll(TestSuiteWindow window, List<ResultRecord> fixture) throws Exception {
        List<ResultRecord> dataset = new ArrayList<>();
        for (int i = 0; i < 6; i++) { dataset.addAll(fixture); }
        var resultsField = TestSuiteWindow.class.getDeclaredField("resultsPanel");
        var tableField = TestSuiteWindow.class.getDeclaredField("resultTable");
        var selectorField = ExperimentResultsPanel.class.getDeclaredField("viewSelector");
        var followField = ExperimentResultsPanel.class.getDeclaredField("followLatest");
        var selection = TestSuiteWindow.class.getDeclaredMethod("analysisSelectionChanged", List.class, int.class);
        var jump = TestSuiteWindow.class.getDeclaredMethod("goToResultPage", int.class);
        for (var member : List.of(resultsField, tableField, selectorField, followField, selection, jump)) {
            member.setAccessible(true);
        }
        var results = (ExperimentResultsPanel) resultsField.get(window);
        var table = (javax.swing.JTable) tableField.get(window);
        var selector = (javax.swing.JComboBox<?>) selectorField.get(results);
        var follow = (javax.swing.JCheckBox) followField.get(results);
        SwingUtilities.invokeAndWait(() -> {
            selector.setSelectedItem("Table view");
            var frame = (javax.swing.JFrame) SwingUtilities.getWindowAncestor(table);
            frame.setSize(1000, 480);
            frame.validate();
            follow.doClick();
            try { selection.invoke(window, dataset, 49); }
            catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
        });
        SwingUtilities.invokeAndWait(() -> {
            var model = (ExperimentResultTableModel) table.getModel();
            require(model.recordAt(24) == dataset.get(49), "Following did not jump to the newest row's sorted page.");
            require(table.getVisibleRect().y > 0 && table.getVisibleRect().intersects(table.getCellRect(24, 0, true)),
                    "Following did not scroll to the newest row.");
            try { jump.invoke(window, 1); }
            catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
            require(!follow.isSelected() && model.recordAt(0) == dataset.get(0),
                    "Manual paging must pause following and uncheck the control.");
        });
        System.out.println("Desktop follow check passed: newest row in view and manual paging pauses follow.");
    }
    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
