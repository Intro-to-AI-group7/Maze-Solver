package ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Objects;
import java.util.function.IntConsumer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTextArea;

/**
 * Layout and actions for experiment execution controls and results.
 */
@SuppressWarnings("auxiliaryclass")
final class ExperimentResultsPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final JLabel pageInfo = new JLabel("Page 1 of 1");
    private final JLabel memoryInfo = new JLabel(
            "Workers: solving 0 / mapping 0    Elapsed: 00:00:00    "
                    + "Solves/sec: 0.00    Avg: 0.00");
    private final JButton pauseButton = new JButton("Pause");

    ExperimentResultsPanel(
            JTable resultTable,
            JTextArea solveLog,
            JTextArea mapLog,
            JLabel statusLabel,
            JProgressBar progressBar,
            JProgressBar mapProgressBar,
            Runnable runSuite,
            Runnable cancelRun,
            Runnable pauseRun,
            Runnable exportResults,
            Runnable exportMaps,
            IntConsumer reviewResult,
            IntConsumer pageSizeChanged,
            IntConsumer pageJump,
            IntConsumer pageDelta,
            java.util.function.Consumer<Boolean> followLatestChanged,
            java.util.function.Consumer<String> viewChanged) {
        super(new BorderLayout(4, 4));
        Objects.requireNonNull(resultTable);
        Objects.requireNonNull(statusLabel);
        Objects.requireNonNull(progressBar);
        Objects.requireNonNull(mapProgressBar);

        resultTable.getColumnModel().getColumn(10)
                .setCellRenderer(new ReviewButtonRenderer());
        resultTable.getColumnModel().getColumn(10)
                .setCellEditor(new ReviewButtonEditor(reviewResult));

        JPanel actions = new JPanel(new BorderLayout(6, 0));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(button("Run Tests", runSuite));
        pauseButton.addActionListener(event -> pauseRun.run());
        buttons.add(pauseButton);
        buttons.add(button("Cancel", cancelRun));
        buttons.add(button("Export CSV", exportResults));
        buttons.add(button("Export Maps", exportMaps));
        buttons.add(statusLabel);
        actions.add(buttons, BorderLayout.WEST);
        JPanel progressContainer = new JPanel(new GridBagLayout());
        GridBagConstraints progressConstraints = new GridBagConstraints();
        progressConstraints.fill = GridBagConstraints.HORIZONTAL;
        progressConstraints.weightx = 1.0;
        progressConstraints.insets = new Insets(5, 0, 5, 6);
        progressContainer.add(progressBar, progressConstraints);
        progressConstraints.insets = new Insets(0, 0, 5, 6);
        progressContainer.add(mapProgressBar, progressConstraints);
        actions.add(progressContainer, BorderLayout.CENTER);
        add(actions, BorderLayout.NORTH);
        JPanel resultViews = new JPanel(new CardLayout());
        resultViews.add(new JScrollPane(resultTable), "table");
        JPanel logViews = new JPanel(new BorderLayout(4, 0));
        JScrollPane solveScroll = new JScrollPane(solveLog);
        JScrollPane mapScroll = new JScrollPane(mapLog);
        solveScroll.setBorder(javax.swing.BorderFactory.createTitledBorder("Solver log"));
        mapScroll.setBorder(javax.swing.BorderFactory.createTitledBorder("Map generation log"));
        JSplitPane logSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, solveScroll, mapScroll);
        logSplit.setResizeWeight(0.75);
        logSplit.setDividerLocation(0.75);
        logViews.add(logSplit, BorderLayout.CENTER);
        resultViews.add(logViews, "log");
        JComboBox<String> viewSelector = new JComboBox<>(
                new String[] {"Table view", "Live solve log"});
        viewSelector.setToolTipText("Choose how completed solves are displayed.");
        viewSelector.addActionListener(event -> {
            String view = (String) viewSelector.getSelectedItem();
            ((CardLayout) resultViews.getLayout()).show(
                    resultViews, "Live solve log".equals(view) ? "log" : "table");
            viewChanged.accept(view);
        });
        JPanel viewSelectorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        viewSelectorPanel.add(new JLabel("Results view:"));
        viewSelectorPanel.add(viewSelector);
        add(resultViews, BorderLayout.CENTER);
        JPanel paging = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        paging.add(new JLabel("Results per page:"));
        JComboBox<Integer> pageSize = new JComboBox<>(new Integer[] {10, 25, 50, 100});
        pageSize.setSelectedItem(25);
        pageSize.addActionListener(event ->
                pageSizeChanged.accept((Integer) pageSize.getSelectedItem()));
        paging.add(pageSize);
        paging.add(button("-1", () -> pageDelta.accept(-1)));
        paging.add(button("-10", () -> pageDelta.accept(-10)));
        paging.add(button("-100", () -> pageDelta.accept(-100)));
        JTextField pageNumber = new JTextField("1", 4);
        pageNumber.addActionListener(event -> {
            try {
                pageJump.accept(Integer.parseInt(pageNumber.getText().trim()));
            } catch (NumberFormatException ex) {
                pageNumber.setText("1");
            }
        });
        paging.add(new JLabel("Go to:"));
        paging.add(pageNumber);
        paging.add(button("+1", () -> pageDelta.accept(1)));
        paging.add(button("+10", () -> pageDelta.accept(10)));
        paging.add(button("+100", () -> pageDelta.accept(100)));
        JCheckBox followLatest = new JCheckBox("Follow latest results");
        followLatest.setToolTipText(
                "Keep the table on the final page as new solver results arrive.");
        followLatest.addActionListener(event ->
                followLatestChanged.accept(followLatest.isSelected()));
        paging.add(followLatest);
        paging.add(pageInfo);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(viewSelectorPanel, BorderLayout.WEST);
        footer.add(memoryInfo, BorderLayout.CENTER);
        footer.add(paging, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
    }

    void setPageInfo(String text) {
        pageInfo.setText(text);
    }

    void setMemoryInfo(String text) {
        memoryInfo.setText(text);
    }

    void setPaused(boolean paused) {
        pauseButton.setText(paused ? "Resume" : "Pause");
    }

    private static JButton button(String label, Runnable action) {
        JButton button = new JButton(label);
        button.addActionListener(event -> action.run());
        return button;
    }
}
