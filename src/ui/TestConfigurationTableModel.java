package ui;

import javax.swing.table.DefaultTableModel;
import java.util.HashSet;
import java.util.Set;

/**
 * Table model for editable experiment configurations.
 *
 * <p>Column values intentionally remain ordinary objects because the window
 * installs typed editors for enum, numeric, boolean, map, and algorithm cells.
 */
final class TestConfigurationTableModel extends DefaultTableModel {
    private static final long serialVersionUID = 1L;

    static final String[] COLUMNS = {
        "ID / Drag", "Algorithms", "Iterations", "Map Source", "Seed / Map",
        "Rows", "Columns", "Blocked %", "Reachability", "Cost Mode",
        "Min Cost", "Max Cost", "Symmetric Cost", "Endpoint Mode",
        "Start Row", "Start Col", "Goal Row", "Goal Col", "Target Distance",
        "Tolerance", "Embedded Map"
    };

    static final String[] COLUMN_TOOLTIPS = {
        "Unique test identifier. Drag from this column to reorder rows.",
        "Algorithms to run for each generated map.",
        "Number of times to repeat this test configuration.",
        "Whether the row uses an automatic seed, manual seed, or saved map.",
        "Seed value or saved map name.",
        "Maze height in cells.",
        "Maze width in cells.",
        "Percentage of interior cells blocked by walls.",
        "Whether generated maps are guaranteed solvable, unsolvable, or natural.",
        "Movement-cost distribution. Saved maps use the movement costs embedded in the map.",
        "Lowest movement cost used when generating weighted maps.",
        "Highest movement cost used when generating weighted maps.",
        "Use the same movement cost in both directions.",
        "How the start and goal positions are selected. Opposite corners uses the two interior corners.",
        "Fixed start row, when using fixed endpoints.",
        "Fixed start column, when using fixed endpoints.",
        "Fixed goal row, when using fixed endpoints.",
        "Fixed goal column, when using fixed endpoints.",
        "Target Manhattan distance, when using distance endpoints.",
        "Allowed distance variation around the target.",
        "Embedded saved-map contents used by this row."
    };

    TestConfigurationTableModel() {
        super(COLUMNS, 0);
    }

    static String tooltipForColumn(int column) {
        return column >= 0 && column < COLUMN_TOOLTIPS.length
                ? COLUMN_TOOLTIPS[column] : null;
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return true;
    }

    void moveRow(int sourceRow, int targetRow) {
        if (sourceRow == targetRow) {
            return;
        }
        Object[] values = new Object[getColumnCount()];
        for (int column = 0; column < getColumnCount(); column++) {
            values[column] = getValueAt(sourceRow, column);
        }
        removeRow(sourceRow);
        int insertionRow = sourceRow < targetRow ? targetRow - 1 : targetRow;
        insertRow(insertionRow, values);
    }

    int[] duplicateRows(int[] selectedRows) {
        Set<String> ids = new HashSet<>();
        for (int row = 0; row < getRowCount(); row++) {
            ids.add(String.valueOf(getValueAt(row, 0)));
        }

        int[] duplicatedRows = new int[selectedRows.length];
        for (int index = 0; index < selectedRows.length; index++) {
            int sourceRow = selectedRows[index] + index;
            Object[] values = new Object[getColumnCount()];
            for (int column = 0; column < getColumnCount(); column++) {
                values[column] = getValueAt(sourceRow, column);
            }
            String baseId = String.valueOf(values[0]);
            String duplicateId = baseId + "-copy";
            int suffix = 2;
            while (!ids.add(duplicateId)) {
                duplicateId = baseId + "-copy-" + suffix++;
            }
            values[0] = duplicateId;
            int duplicateRow = sourceRow + 1;
            insertRow(duplicateRow, values);
            duplicatedRows[index] = duplicateRow;
        }
        return duplicatedRows;
    }
}
