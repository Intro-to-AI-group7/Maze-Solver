package ui;

import javax.swing.table.DefaultTableModel;

/**
 * Read-only table model for measured experiment results.
 */
final class ExperimentResultTableModel extends DefaultTableModel {
    private static final long serialVersionUID = 1L;

    static final String[] COLUMNS = {
        "Configuration", "Iteration", "Algorithm", "Solved", "Path Cost",
        "Path Length", "Nodes Explored", "Runtime (ms)", "Map Source", "Seed", "Review"
    };

    ExperimentResultTableModel() {
        super(COLUMNS, 0);
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return column == 10;
    }
}
