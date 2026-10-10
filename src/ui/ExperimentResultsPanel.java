package ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Objects;
import java.util.function.IntConsumer;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;

/**
 * Layout and actions for experiment execution controls and results.
 */
@SuppressWarnings("auxiliaryclass")
final class ExperimentResultsPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    ExperimentResultsPanel(
            JTable resultTable,
            JLabel statusLabel,
            Runnable runSuite,
            Runnable cancelRun,
            Runnable exportResults,
            Runnable exportMaps,
            IntConsumer reviewResult) {
        super(new BorderLayout(4, 4));
        Objects.requireNonNull(resultTable);
        Objects.requireNonNull(statusLabel);

        resultTable.getColumnModel().getColumn(10)
                .setCellRenderer(new ReviewButtonRenderer());
        resultTable.getColumnModel().getColumn(10)
                .setCellEditor(new ReviewButtonEditor(reviewResult));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(button("Run Tests", runSuite));
        actions.add(button("Cancel", cancelRun));
        actions.add(button("Export CSV", exportResults));
        actions.add(button("Export Maps", exportMaps));
        actions.add(statusLabel);
        add(actions, BorderLayout.NORTH);
        add(new JScrollPane(resultTable), BorderLayout.CENTER);
    }

    private static JButton button(String label, Runnable action) {
        JButton button = new JButton(label);
        button.addActionListener(event -> action.run());
        return button;
    }
}
