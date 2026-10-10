package ui;

import java.awt.Component;
import javax.swing.JTable;
import javax.swing.table.TableCellRenderer;

import experiments.TestSuite;

/**
 * Configuration table with context-sensitive editability and disabled-cell
 * rendering for saved-map and endpoint modes.
 */
final class TestConfigurationTable extends JTable {
    private static final long serialVersionUID = 1L;

    TestConfigurationTable(TestConfigurationTableModel model) {
        super(model);
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        if (isSavedMap(row) && column >= 1 && column <= 15) {
            return false;
        }
        if (column == 16) {
            return getValueAt(row, 17) != TestSuite.MapSource.AUTOMATIC_SEED;
        }
        if (column >= 10 && column <= 13) {
            return getValueAt(row, 9) == TestSuite.EndpointMode.FIXED;
        }
        if (column == 14) {
            return getValueAt(row, 9) == TestSuite.EndpointMode.APPROXIMATE_DISTANCE;
        }
        return super.isCellEditable(row, column);
    }

    @Override
    public Component prepareRenderer(
            TableCellRenderer renderer, int row, int column) {
        Component component = super.prepareRenderer(renderer, row, column);
        if (isSavedMap(row) && column >= 1 && column <= 15) {
            component.setEnabled(false);
            return component;
        }
        if (column == 16) {
            component.setEnabled(getValueAt(row, 17) != TestSuite.MapSource.AUTOMATIC_SEED);
            return component;
        }
        boolean disabled = (column >= 10 && column <= 14)
                && ((column == 14 && getValueAt(row, 9)
                        != TestSuite.EndpointMode.APPROXIMATE_DISTANCE)
                || (column < 14 && getValueAt(row, 9)
                        != TestSuite.EndpointMode.FIXED));
        component.setEnabled(!disabled);
        return component;
    }

    private boolean isSavedMap(int row) {
        return getValueAt(row, 17) == TestSuite.MapSource.SAVED_MAP;
    }
}
