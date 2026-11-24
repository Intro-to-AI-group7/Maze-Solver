package ui;

import analysis.ResultChart;
import analysis.ResultFiles;
import analysis.ResultQuery;
import analysis.ResultRecord;
import analysis.ResultStatistics;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;

/** Analysis controls and background queries shared by the table, summary and graph cards. */
final class ResultAnalysisPanel {
    private final JPanel controls = new JPanel();
    private final JPanel graph = new JPanel(new BorderLayout());
    private final JLabel imageLabel = new JLabel("Run or open results to analyze.");
    private BufferedImage chartImage;
    private final JPanel chartCards = new JPanel(new java.awt.CardLayout());
    private final JPanel preview = createPreview();
    private javax.swing.JFrame expandedFrame;
    private JPanel expandedPreview;
    private final JLabel counts = new JLabel("0 matching results");
    private final JTextField filterText = new JTextField(28);
    private final JTextField sortText = new JTextField(22);
    private final TableHeaderQuery headerQuery = new TableHeaderQuery(filterText, sortText, this::apply);
    private final JTextField groupsText = new JTextField("configuration,map,algorithm", 25);
    private final JComboBox<ResultQuery.Field> filterField = new JComboBox<>(ResultQuery.Field.values());
    private final JComboBox<String> operator = new JComboBox<>(new String[] {"=", "!=", ">=", "<=", ">", "<", "~"});
    private final JTextField filterValue = new JTextField(15);
    private final JComboBox<ResultChart.Type> type = new JComboBox<>(ResultChart.Type.values());
    private final JComboBox<ResultQuery.Field> xAxis = new JComboBox<>(ResultQuery.Field.values());
    private final JComboBox<ResultStatistics.Metric> metric = new JComboBox<>(ResultStatistics.Metric.values());
    private final JComboBox<ResultChart.Aggregate> aggregate = new JComboBox<>(ResultChart.Aggregate.values());
    private final JComboBox<ResultChart.Order> order = new JComboBox<>(ResultChart.Order.values());
    private final JComboBox<ResultChart.View> view = new JComboBox<>(ResultChart.View.values());
    private final JComboBox<ResultStatistics.Outliers> outliers = new JComboBox<>(ResultStatistics.Outliers.values());
    private final JComboBox<ResultStatistics.Metric> outlierMetric = new JComboBox<>(ResultStatistics.Metric.values());
    private final JCheckBox logY = new JCheckBox("Log Y");
    private final JCheckBox errorBars = new JCheckBox("Mean +/- sample SD");
    private final JCheckBox showLegend = new JCheckBox("Show legend", true);
    private final DefaultTableModel summaryModel = new DefaultTableModel(new String[] {
        "Group / execution", "Original", "Excluded", "Retained", "Maps", "Success % (raw)", "Success % (retained)", "Mean ms", "Median ms",
        "Sample SD ms", "Min ms", "Max ms", "Mean nodes", "Median nodes", "Cost samples", "Mean cost",
        "Median cost", "UCS pairs", "Excess cost %", "Nodes saved %"
    }, 0) {
        private static final long serialVersionUID = 1L;
        @Override public boolean isCellEditable(int row, int column) { return false; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? String.class : Object.class; }
    };
    private final JScrollPane summary;
    private final BiConsumer<List<ResultRecord>, Integer> onSelection;
    private final Consumer<List<ResultRecord>> onOpen;
    private final Consumer<String> onError;
    private List<ResultRecord> source = List.of();
    private List<ResultQuery.Field> grouping = List.of(ResultQuery.Field.CONFIGURATION,
            ResultQuery.Field.MAP, ResultQuery.Field.ALGORITHM);
    private SwingWorker<Computed, Void> queryWorker;
    private boolean closed;
    private long revision;
    private String filters = "", sorting = "";
    private boolean graphVisible;

    ResultAnalysisPanel(BiConsumer<List<ResultRecord>, Integer> onSelection,
            Consumer<List<ResultRecord>> onOpen, Consumer<String> onError) {
        this.onSelection = onSelection; this.onOpen = onOpen; this.onError = onError;
        controls.setLayout(new javax.swing.BoxLayout(controls, javax.swing.BoxLayout.Y_AXIS));
        JTable summaryTable = new JTable(summaryModel);
        summaryTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        summaryTable.getColumnModel().getColumn(0).setPreferredWidth(550);
        for (int i = 1; i < summaryTable.getColumnCount(); i++) { summaryTable.getColumnModel().getColumn(i).setPreferredWidth(110); }
        summary = new JScrollPane(summaryTable);
        xAxis.setSelectedItem(ResultQuery.Field.ALGORITHM);
        filterField.setSelectedItem(ResultQuery.Field.MAP);
        aggregate.setSelectedItem(ResultChart.Aggregate.MEAN);
        view.setSelectedItem(ResultChart.View.OVERLAY);
        outliers.setSelectedItem(ResultStatistics.Outliers.IQR);
        filterValue.setToolTipText("Use | for alternatives, e.g. UCS|A_STAR. ~ means contains.");
        filterText.setToolTipText("AND rules separated by ; e.g. algorithm=UCS|A_STAR;rows>=500");
        sortText.setToolTipText("Global multi-column sort, e.g. configuration:asc,runtime_ms:asc. " + TableHeaderQuery.HEADER_HINT);
        groupsText.setToolTipText("Summary grouping fields. Algorithm and execution mode are always separated. Default compares each map.");
        JToggleButton analysisOptions = new JToggleButton("Analysis options");
        controls.add(row(new JLabel("Filters:"), filterText, button("Apply", this::apply),
                button("Clear filters", () -> { filterText.setText(""); apply(); }), analysisOptions, counts));
        JPanel advanced = new JPanel(new java.awt.GridLayout(0, 1));
        advanced.add(row(new JLabel("Add filter:"), filterField, operator, filterValue,
                button("Add", this::addFilter), new JLabel("Sort:"), sortText,
                button("Reset sort", () -> { sortText.setText(""); apply(); })));
        advanced.add(row(new JLabel("Summary groups:"), groupsText));
        JPanel chartControls = row(new JLabel("View:"), view, new JLabel("Chart:"), type,
                new JLabel("X:"), xAxis, new JLabel("Y:"), metric);
        JPanel chartOptions = row(new JLabel("Raw statistic:"), aggregate,
                new JLabel("Order:"), order, logY, errorBars, showLegend, button("Expand graph", this::expandGraph));
        advanced.add(row(new JLabel("Outlier removal:"), outliers, new JLabel("Metric:"), outlierMetric));
        advanced.setVisible(false);
        analysisOptions.addActionListener(event -> advanced.setVisible(analysisOptions.isSelected()));
        controls.add(advanced);
        JPanel graphHeader = new JPanel(new java.awt.GridLayout(0, 1));
        graphHeader.add(chartControls);
        graphHeader.add(chartOptions);
        graph.add(graphHeader, BorderLayout.NORTH);
        chartCards.add(preview, "image"); chartCards.add(imageLabel, "message");
        ((java.awt.CardLayout) chartCards.getLayout()).show(chartCards, "message");
        graph.add(chartCards, BorderLayout.CENTER);
        for (JComboBox<?> combo : List.of(type, xAxis, metric, aggregate, order, view, outliers, outlierMetric)) {
            combo.addActionListener(event -> changed());
        }
        logY.addActionListener(event -> changed()); errorBars.addActionListener(event -> changed());
        showLegend.addActionListener(event -> changed());
        type.addActionListener(event -> {
            if ((type.getSelectedItem() == ResultChart.Type.LINE || type.getSelectedItem() == ResultChart.Type.SCATTER)
                    && !((ResultQuery.Field) xAxis.getSelectedItem()).isNumeric()) {
                xAxis.setSelectedItem(ResultQuery.Field.ROWS);
            }
        });
        filterText.addActionListener(event -> apply()); sortText.addActionListener(event -> apply());
        groupsText.addActionListener(event -> apply());
    }
    JPanel controls() { return controls; }
    JPanel graph() { return graph; }
    JScrollPane summary() { return summary; }
    void setView(String name) {
        controls.setVisible(!name.equals("Live solve log"));
        graphVisible = name.equals("Graphs");
        if (graphVisible) { changed(); }
    }
    void setSource(List<ResultRecord> rows) {
        source = List.copyOf(rows); changed();
    }
    void updateSource(List<ResultRecord> rows) {
        source = List.copyOf(rows);
        if (!closed && queryWorker == null) { startQuery(); }
    }
    void close() {
        closed = true; if (queryWorker != null) { queryWorker.cancel(true); }
        if (expandedFrame != null) { expandedFrame.dispose(); }
    }
    JButton resultsMenu(Runnable exportMaps) {
        JButton button = new JButton("Results actions");
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("Open results CSV…", this::open));
        menu.addSeparator();
        menu.add(menuItem("Export all results CSV…", () -> export(false)));
        menu.add(menuItem("Export filtered CSV…", () -> export(true)));
        menu.add(menuItem("Export summary CSV…", this::exportSummary));
        menu.add(menuItem("Export graph PNG…", this::exportGraph));
        menu.add(menuItem("Export maps…", exportMaps));
        menu.addSeparator();
        menu.add(menuItem("Save analysis view…", this::savePreset));
        menu.add(menuItem("Load analysis view…", this::loadPreset));
        menu.add(menuItem("Analysis help", this::help));
        button.addActionListener(event -> menu.show(button, 0, button.getHeight()));
        return button;
    }

    private static JMenuItem menuItem(String label, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(event -> action.run());
        return item;
    }

    void filterAlgorithm(maze.search.MazeSolver.Algorithm algorithm) {
        headerQuery.addFilter(ResultQuery.Field.ALGORITHM, "=", algorithm.name());
    }
    void compareMap(String mapId) {
        if (mapId.isBlank()) { onError.accept("This result has no verified map identity. Rerun the suite to compare its exact map."); return; }
        filterText.setText("map=" + mapId);
        sortText.setText("algorithm:asc,iteration:asc");
        type.setSelectedItem(ResultChart.Type.BAR);
        xAxis.setSelectedItem(ResultQuery.Field.ALGORITHM);
        view.setSelectedItem(ResultChart.View.OVERLAY);
        apply();
    }
    void installTableHeader(JTable table) {
        headerQuery.install(table, ExperimentResultTableModel::fieldAt);
    }
    private void addFilter() {
        headerQuery.addFilter((ResultQuery.Field) filterField.getSelectedItem(),
                (String) operator.getSelectedItem(), filterValue.getText());
    }
    private void apply() {
        try {
            new ResultQuery(filterText.getText(), sortText.getText());
            grouping = groups();
            filters = filterText.getText();
            sorting = sortText.getText();
            changed();
        } catch (IllegalArgumentException ex) { onError.accept(ex.getMessage()); }
    }
    private List<ResultQuery.Field> groups() {
        if (groupsText.getText().isBlank()) { return List.of(); }
        return java.util.Arrays.stream(groupsText.getText().split(",", -1)).map(ResultQuery.Field::parse).toList();
    }
    private ResultChart.Spec spec() {
        ResultChart.Type chartType = view.getSelectedItem() == ResultChart.View.OVERLAY
                ? (((ResultQuery.Field) xAxis.getSelectedItem()).isNumeric() ? ResultChart.Type.SCATTER : ResultChart.Type.BAR)
                : (ResultChart.Type) type.getSelectedItem();
        return new ResultChart.Spec(chartType,
                (ResultQuery.Field) xAxis.getSelectedItem(), (ResultStatistics.Metric) metric.getSelectedItem(),
                (ResultChart.Aggregate) aggregate.getSelectedItem(), (ResultChart.Order) order.getSelectedItem(),
                logY.isSelected(), errorBars.isSelected(), false, showLegend.isSelected());
    }
    private String caption() { return "Filters: " + (filters.isBlank() ? "none" : filters)
            + "; sort: " + (sorting.isBlank() ? "arrival order" : sorting); }
    private void changed() {
        boolean overlay = view.getSelectedItem() == ResultChart.View.OVERLAY;
        type.setEnabled(!overlay); errorBars.setEnabled(!overlay);
        aggregate.setEnabled(view.getSelectedItem() == ResultChart.View.ITERATIONS || view.getSelectedItem() == ResultChart.View.BOTH);
        revision++;
        if (closed) { return; }
        if (queryWorker == null) { startQuery(); }
    }
    private record Computed(List<ResultRecord> selected, int latestIndex, List<ResultStatistics.Summary> summaries,
            int excluded, BufferedImage image, String chartError) { }
    private void startQuery() {
        List<ResultRecord> snapshot = source;
        long requestRevision = revision;
        String queryFilters = filters, querySort = sorting, caption = caption();
        List<ResultQuery.Field> groups = grouping;
        var policy = (ResultStatistics.Outliers) outliers.getSelectedItem();
        var trimmingMetric = (ResultStatistics.Metric) outlierMetric.getSelectedItem();
        var chartView = (ResultChart.View) view.getSelectedItem();
        boolean renderGraph = graphVisible || (expandedFrame != null && expandedFrame.isShowing());
        ResultChart.Spec chart = null;
        String invalidChart = "";
        if (renderGraph) {
            try { chart = spec(); }
            catch (IllegalArgumentException ex) { invalidChart = ex.getMessage(); }
        }
        ResultChart.Spec chartRequest = chart;
        String chartValidationError = invalidChart;
        counts.setText("Analyzing " + snapshot.size() + " results...");
        queryWorker = new SwingWorker<>() {
            @Override protected Computed doInBackground() {
                var query = new ResultQuery(queryFilters, querySort);
                List<ResultRecord> selection = query.apply(snapshot);
                int latestIndex = -1;
                if (!selection.isEmpty()) {
                    for (int i = snapshot.size() - 1; i >= 0; i--) {
                        ResultRecord row = snapshot.get(i);
                        if (query.matches(row)) {
                            latestIndex = selection.lastIndexOf(row);
                            break;
                        }
                    }
                }
                var summaryRows = ResultStatistics.summarize(selection, snapshot, groups, policy, trimmingMetric);
                int excluded = summaryRows.stream().mapToInt(ResultStatistics.Summary::excluded).sum();
                BufferedImage image = null;
                String error = chartValidationError;
                if (chartRequest != null) {
                    try { image = ResultChart.renderView(selection, snapshot, chartRequest, chartView, policy,
                            trimmingMetric, caption, 1600, 1000); }
                    catch (IllegalArgumentException ex) { error = ex.getMessage(); }
                }
                return new Computed(selection, latestIndex, summaryRows, excluded, image, error);
            }
            @Override protected void done() {
                if (closed) { return; }
                try {
                    Computed result = get();
                    if (requestRevision == revision) {
                        onSelection.accept(result.selected, result.latestIndex);
                        counts.setText(result.selected.size() + " / " + snapshot.size() + " match; normalized excludes " + result.excluded);
                        summaryModel.setRowCount(0);
                        for (var s : result.summaries) {
                            summaryModel.addRow(new Object[] {s.group(), s.originalRuns(), s.excluded(), s.runs(),
                                s.distinctMaps() < 0 ? "UNKNOWN" : s.distinctMaps(), number(s.originalSuccessPercent()), number(s.successPercent()),
                                number(s.runtime().mean()), number(s.runtime().median()), number(s.runtime().stddev()),
                                number(s.runtime().min()), number(s.runtime().max()), number(s.nodes().mean()),
                                number(s.nodes().median()), s.cost().count(), number(s.cost().mean()), number(s.cost().median()),
                                s.excessCost().count(), number(s.excessCost().mean()), number(s.nodeReduction().mean())});
                        }
                        if (result.image != null) {
                            chartImage = result.image;
                            ((java.awt.CardLayout) chartCards.getLayout()).show(chartCards, "image");
                            preview.repaint(); if (expandedPreview != null) { expandedPreview.repaint(); }
                        } else if (renderGraph) {
                            chartImage = null; imageLabel.setText(result.chartError);
                            ((java.awt.CardLayout) chartCards.getLayout()).show(chartCards, "message");
                            if (expandedPreview != null) { expandedPreview.repaint(); }
                        }
                    }
                } catch (java.util.concurrent.CancellationException ignored) {
                    // Window closed while a background analysis was running.
                } catch (Exception ex) { onError.accept("Analysis failed: " + ex.getMessage()); }
                queryWorker = null;
                if (!closed && (requestRevision != revision || source != snapshot)) { startQuery(); }
            }
        };
        queryWorker.execute();
    }
    private JPanel createPreview() {
        JPanel panel = new JPanel() {
            private static final long serialVersionUID = 1L;
            @Override protected void paintComponent(java.awt.Graphics graphics) {
                super.paintComponent(graphics);
                if (chartImage == null) { return; }
                double scale = Math.min(getWidth() / (double) chartImage.getWidth(), getHeight() / (double) chartImage.getHeight());
                int width = (int) (chartImage.getWidth() * scale), height = (int) (chartImage.getHeight() * scale);
                var g = (java.awt.Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(chartImage, (getWidth() - width) / 2, (getHeight() - height) / 2, width, height, null);
                } finally { g.dispose(); }
            }
        };
        panel.setBackground(java.awt.Color.WHITE); return panel;
    }
    private void expandGraph() {
        if (expandedFrame == null || !expandedFrame.isDisplayable()) {
            expandedFrame = new javax.swing.JFrame("Maze Solver - Result graph");
            expandedFrame.setDefaultCloseOperation(javax.swing.JFrame.DISPOSE_ON_CLOSE);
            expandedPreview = createPreview(); expandedFrame.add(expandedPreview);
            expandedFrame.setSize(1200, 800); expandedFrame.setLocationRelativeTo(graph);
        }
        expandedFrame.setVisible(true); expandedFrame.toFront(); changed();
    }
    private static Object number(double value) {
        return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.4f", value) : "N/A";
    }
    private Path choose(String title, String extension, boolean save) {
        JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle(title);
        chooser.setFileFilter(new FileNameExtensionFilter(extension.toUpperCase(java.util.Locale.ROOT) + " files", extension));
        if ((save ? chooser.showSaveDialog(controls) : chooser.showOpenDialog(controls)) != JFileChooser.APPROVE_OPTION) { return null; }
        Path path = chooser.getSelectedFile().toPath();
        if (save && !path.toString().toLowerCase(java.util.Locale.ROOT).endsWith("." + extension)) {
            path = path.resolveSibling(path.getFileName() + "." + extension);
        }
        if (save && Files.exists(path) && JOptionPane.showConfirmDialog(controls,
                "Replace " + path.getFileName() + "?", "Replace file", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return null; }
        return path;
    }
    @FunctionalInterface private interface IoTask { void run() throws Exception; }
    private void io(String action, IoTask task) {
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { task.run(); return null; }
            @Override protected void done() {
                if (closed) { return; }
                try { get(); counts.setText(action + " complete."); }
                catch (Exception ex) { onError.accept(action + " failed: " + ex.getMessage()); }
            }
        }.execute();
    }
    private void export(boolean filtered) {
        Path path = choose(filtered ? "Export filtered results" : "Export all results", "csv", true);
        if (path == null) { return; }
        List<ResultRecord> snapshot = source;
        ResultQuery query = new ResultQuery(filters, sorting);
        io("CSV export", () -> ResultFiles.save(filtered ? query.apply(snapshot) : snapshot, path));
    }
    private void exportSummary() {
        Path path = choose("Export normalized summary", "csv", true); if (path == null) { return; }
        List<ResultRecord> snapshot = source; ResultQuery query = new ResultQuery(filters, sorting);
        List<ResultQuery.Field> groups = grouping;
        var policy = (ResultStatistics.Outliers) outliers.getSelectedItem();
        var trimmingMetric = (ResultStatistics.Metric) outlierMetric.getSelectedItem();
        io("Summary export", () -> ResultFiles.saveSummary(ResultStatistics.summarize(query.apply(snapshot),
                snapshot, groups, policy, trimmingMetric), path));
    }
    private void exportGraph() {
        try {
            ResultChart.Spec chart = spec(); Path path = choose("Export graph image", "png", true);
            if (path == null) { return; }
            List<ResultRecord> snapshot = source; ResultQuery query = new ResultQuery(filters, sorting);
            var policy = (ResultStatistics.Outliers) outliers.getSelectedItem();
            var trimmingMetric = (ResultStatistics.Metric) outlierMetric.getSelectedItem();
            var chartView = (ResultChart.View) view.getSelectedItem(); String caption = caption();
            io("Graph export", () -> ResultChart.exportView(query.apply(snapshot), snapshot, chart,
                    chartView, policy, trimmingMetric, caption, path, 1600, 1000));
        } catch (IllegalArgumentException ex) { onError.accept(ex.getMessage()); }
    }
    private void open() {
        Path path = choose("Open saved results", "csv", false); if (path == null) { return; }
        new SwingWorker<List<ResultRecord>, Void>() {
            @Override protected List<ResultRecord> doInBackground() throws Exception { return ResultFiles.load(path); }
            @Override protected void done() {
                if (closed) { return; }
                try { onOpen.accept(get()); } catch (Exception ex) { onError.accept("Could not open results: " + ex.getMessage()); }
            }
        }.execute();
    }
    private Properties preset() {
        Properties p = new Properties(); p.setProperty("version", "1");
        p.setProperty("filters", filters); p.setProperty("sort", sorting); p.setProperty("groups", groupsText.getText());
        p.setProperty("type", type.getSelectedItem().toString()); p.setProperty("x", xAxis.getSelectedItem().toString());
        p.setProperty("metric", ((ResultStatistics.Metric) metric.getSelectedItem()).name());
        p.setProperty("aggregate", aggregate.getSelectedItem().toString()); p.setProperty("order", order.getSelectedItem().toString());
        p.setProperty("view", view.getSelectedItem().toString()); p.setProperty("outliers", outliers.getSelectedItem().toString());
        p.setProperty("outlierMetric", ((ResultStatistics.Metric) outlierMetric.getSelectedItem()).name());
        p.setProperty("logY", "" + logY.isSelected()); p.setProperty("errorBars", "" + errorBars.isSelected());
        p.setProperty("showLegend", "" + showLegend.isSelected()); return p;
    }
    private void savePreset() {
        Path path = choose("Save analysis view", "msview", true); if (path == null) { return; }
        Properties p = preset();
        io("Save view", () -> { try (var writer = Files.newBufferedWriter(path)) { p.store(writer, "Maze-Solver analysis view"); } });
    }
    private void loadPreset() {
        Path path = choose("Load analysis view", "msview", false); if (path == null) { return; }
        try {
            Properties p = new Properties(); try (var reader = Files.newBufferedReader(path)) { p.load(reader); }
            if (!p.getProperty("version", "").equals("1")) { throw new IllegalArgumentException("Unsupported view version."); }
            new ResultQuery(p.getProperty("filters", ""), p.getProperty("sort", ""));
            var loadedType = ResultChart.Type.valueOf(p.getProperty("type"));
            var loadedX = ResultQuery.Field.parse(p.getProperty("x"));
            var loadedMetric = ResultStatistics.Metric.valueOf(p.getProperty("metric"));
            var loadedAggregate = ResultChart.Aggregate.valueOf(p.getProperty("aggregate"));
            var loadedOrder = ResultChart.Order.valueOf(p.getProperty("order"));
            var loadedView = ResultChart.View.valueOf(p.getProperty("view"));
            var loadedOutliers = ResultStatistics.Outliers.valueOf(p.getProperty("outliers"));
            var loadedOutlierMetric = ResultStatistics.Metric.valueOf(p.getProperty("outlierMetric"));
            new ResultChart.Spec(loadedType, loadedX, loadedMetric, loadedAggregate, loadedOrder, false, false);
            java.util.Arrays.stream(p.getProperty("groups").split(",")).filter(s -> !s.isBlank()).forEach(ResultQuery.Field::parse);
            filterText.setText(p.getProperty("filters")); sortText.setText(p.getProperty("sort")); groupsText.setText(p.getProperty("groups"));
            type.setSelectedItem(loadedType); xAxis.setSelectedItem(loadedX); metric.setSelectedItem(loadedMetric);
            aggregate.setSelectedItem(loadedAggregate); order.setSelectedItem(loadedOrder); view.setSelectedItem(loadedView);
            outliers.setSelectedItem(loadedOutliers); outlierMetric.setSelectedItem(loadedOutlierMetric);
            logY.setSelected(Boolean.parseBoolean(p.getProperty("logY"))); errorBars.setSelected(Boolean.parseBoolean(p.getProperty("errorBars")));
            showLegend.setSelected(Boolean.parseBoolean(p.getProperty("showLegend", "true")));
            apply();
        } catch (Exception ex) { onError.accept("Could not load analysis view: " + ex.getMessage()); }
    }
    private void help() {
        JOptionPane.showMessageDialog(controls,
                "Filters apply to every result before paging. Add multiple rules for AND; use | for alternatives.\n"
                + "Examples: algorithm=UCS|A_STAR;rows>=500   or   map~first-few-characters\n"
                + TableHeaderQuery.HEADER_HINT + "\nMissing costs always sort last.\n"
                + "Default summary groups compare each algorithm on each exact map, separately by execution mode.\n"
                + "Normalized = mean after optional IQR trimming within each map/algorithm/execution group.\n"
                + "IQR uses Q1-1.5*IQR to Q3+1.5*IQR, with at least 4 eligible runs. Counts remain visible.\n"
                + "Categorical OVERLAY uses solid mean bars and translucent bars for raw runs.\n"
                + "Numeric OVERLAY connects raw dots in iteration order within each map and algorithm. Diamonds show normalized means.\n"
                + "BOTH shows two panels.\n"
                + "Normalizing gives each map/algorithm one mean; no algorithms are averaged together.\n"
                + "Cost statistics exclude failed solves. Missing UCS baselines appear as N/A.\n"
                + "Old CSV files lack map identities: pairing/trimming cannot be verified.\n"
                + "Sequential runs support timing comparisons; parallel runs measure concurrent workloads.\n"
                + "Use the same controls in the CLI: --help lists analysis/export options.",
                "Analyze results", JOptionPane.INFORMATION_MESSAGE);
    }
    private static JPanel row(java.awt.Component... controls) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 1));
        for (var control : controls) { panel.add(control); } return panel;
    }
    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text); button.addActionListener(event -> action.run()); return button;
    }
}
