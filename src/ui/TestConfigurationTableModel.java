package ui;

import javax.swing.table.DefaultTableModel;

/**
 * Table model for editable experiment configurations.
 *
 * <p>Column values intentionally remain ordinary objects because the window
 * installs typed editors for enum, numeric, boolean, map, and algorithm cells.
 */
final class TestConfigurationTableModel extends DefaultTableModel {
    private static final long serialVersionUID = 1L;

    static final String[] COLUMNS = {
        "ID", "Rows", "Columns", "Blocked %", "Reachability", "Cost Mode",
        "Min Cost", "Max Cost", "Same Reverse Cost", "Endpoint Mode",
        "Start Row", "Start Col", "Goal Row", "Goal Col", "Target Distance",
        "Tolerance", "Seed / Map", "Map Source", "Iterations", "Algorithms", "Embedded Map"
    };

    TestConfigurationTableModel() {
        super(COLUMNS, 0);
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return true;
    }
}
