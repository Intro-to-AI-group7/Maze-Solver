package ui;

import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.ListSelectionModel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import java.util.function.IntConsumer;

import experiments.TestSuite;

/**
 * Configuration table with context-sensitive editability and disabled-cell
 * rendering for saved-map and endpoint modes.
 */
final class TestConfigurationTable extends JTable {
    private static final long serialVersionUID = 1L;

    TestConfigurationTable(TestConfigurationTableModel model) {
        super(model);
        setTableHeader(new JTableHeader(getColumnModel()) {
            private static final long serialVersionUID = 1L;

            @Override
            public String getToolTipText(MouseEvent event) {
                int viewColumn = columnAtPoint(event.getPoint());
                if (viewColumn < 0) {
                    return null;
                }
                return TestConfigurationTableModel.tooltipForColumn(
                        convertColumnIndexToModel(viewColumn));
            }
        });
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent event) {
                moveDraggedRow(event);
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (event.getButton() == MouseEvent.BUTTON1
                        && event.getX() < getColumnModel().getColumn(0).getWidth()) {
                    draggedRow = rowAtPoint(event.getPoint());
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                draggedRow = -1;
            }
        });
    }

    private int draggedRow = -1;

    void installRowContextMenu(
            IntConsumer newRowAbove,
            Runnable duplicateRows,
            Runnable deleteRows) {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                showContextMenu(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                showContextMenu(event);
            }

            private void showContextMenu(MouseEvent event) {
                if (!event.isPopupTrigger()) {
                    return;
                }
                int clickedRow = rowAtPoint(event.getPoint());
                if (clickedRow < 0) {
                    return;
                }
                if (!isRowSelected(clickedRow)) {
                    setRowSelectionInterval(clickedRow, clickedRow);
                }
                JPopupMenu menu = new JPopupMenu();
                JMenuItem newItem = new JMenuItem("New Row Above");
                newItem.addActionListener(action ->
                        newRowAbove.accept(convertRowIndexToModel(clickedRow)));
                menu.add(newItem);
                JMenuItem duplicateItem = new JMenuItem("Duplicate Selected Rows");
                duplicateItem.setEnabled(getSelectedRowCount() > 0);
                duplicateItem.addActionListener(action -> duplicateRows.run());
                menu.add(duplicateItem);
                JMenuItem deleteItem = new JMenuItem("Delete Selected Rows");
                deleteItem.setEnabled(getSelectedRowCount() > 0);
                deleteItem.addActionListener(action -> deleteRows.run());
                menu.add(deleteItem);
                menu.show(TestConfigurationTable.this, event.getX(), event.getY());
            }
        });
    }

    private void moveDraggedRow(MouseEvent event) {
        if (draggedRow < 0) {
            return;
        }
        int targetRow = rowAtPoint(event.getPoint());
        if (targetRow < 0 || targetRow == draggedRow) {
            return;
        }
        int sourceRow = draggedRow;
        int[] selectedRows = getSelectedRows();
        ((TestConfigurationTableModel) getModel()).moveRow(sourceRow, targetRow);
        int newDraggedRow = sourceRow < targetRow ? targetRow - 1 : targetRow;
        draggedRow = newDraggedRow;
        clearSelection();
        for (int selectedRow : selectedRows) {
            int newRow = movedRowIndex(selectedRow, sourceRow, targetRow);
            addRowSelectionInterval(newRow, newRow);
        }
        if (getSelectedRowCount() == 0) {
            addRowSelectionInterval(newDraggedRow, newDraggedRow);
        }
    }

    private static int movedRowIndex(int row, int sourceRow, int targetRow) {
        if (row == sourceRow) {
            return sourceRow < targetRow ? targetRow - 1 : targetRow;
        }
        if (sourceRow < targetRow && row > sourceRow && row < targetRow) {
            return row - 1;
        }
        if (sourceRow > targetRow && row >= targetRow && row < sourceRow) {
            return row + 1;
        }
        return row;
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        if (isSavedMap(row) && column >= 5 && column <= 19) {
            return false;
        }
        if (column == 9) {
            return !isSavedMap(row);
        }
        if (column == 4) {
            return getValueAt(row, 3) != TestSuite.MapSource.AUTOMATIC_SEED;
        }
        if (column >= 14 && column <= 17) {
            return getValueAt(row, 13) == TestSuite.EndpointMode.FIXED;
        }
        if (column == 18) {
            return getValueAt(row, 13) == TestSuite.EndpointMode.APPROXIMATE_DISTANCE;
        }
        return super.isCellEditable(row, column);
    }

    @Override
    public Component prepareRenderer(
            TableCellRenderer renderer, int row, int column) {
        Component component = super.prepareRenderer(renderer, row, column);
        if (isSavedMap(row) && column >= 5 && column <= 19) {
            component.setEnabled(false);
            return component;
        }
        if (column == 9) {
            component.setEnabled(!isSavedMap(row));
            return component;
        }
        if (column == 4) {
            component.setEnabled(getValueAt(row, 3) != TestSuite.MapSource.AUTOMATIC_SEED);
            return component;
        }
        boolean disabled = (column >= 14 && column <= 18)
                && ((column == 18 && getValueAt(row, 13)
                        != TestSuite.EndpointMode.APPROXIMATE_DISTANCE)
                || (column < 18 && getValueAt(row, 13)
                        != TestSuite.EndpointMode.FIXED));
        component.setEnabled(!disabled);
        return component;
    }

    private boolean isSavedMap(int row) {
        return getValueAt(row, 3) == TestSuite.MapSource.SAVED_MAP;
    }
}
