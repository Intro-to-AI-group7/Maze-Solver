package ui;

import analysis.TableQuery;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.Comparator;
import javax.swing.ListSelectionModel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.RowFilter;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

import experiments.TestSuite;
import ui.TestConfigurationTableModel.Field;

/**
 * Configuration table with context-sensitive editability and disabled-cell
 * rendering for saved-map and endpoint modes.
 */
final class TestConfigurationTable extends JTable {
    private static final long serialVersionUID = 1L;
    private final transient TableRowSorter<TestConfigurationTableModel> sorter;

    TestConfigurationTable(TestConfigurationTableModel model) {
        super(model);
        sorter = new TableRowSorter<>(model) {
            @Override public void toggleSortOrder(int column) {
                // Header actions update the editable sort expression.
            }
        };
        for (Field field : Field.values()) {
            if (field.isNumeric()) {
                sorter.setComparator(field.ordinal(), Comparator.comparing(
                        TestConfigurationTableModel::numericValue, Comparator.nullsLast(Comparator.naturalOrder())));
            } else {
                sorter.setComparator(field.ordinal(), Comparator.comparing(Object::toString, String.CASE_INSENSITIVE_ORDER));
            }
        }
        sorter.setMaxSortKeys(Field.values().length);
        sorter.setSortsOnUpdates(true);
        setRowSorter(sorter);
        setTableHeader(new JTableHeader(getColumnModel()) {
            private static final long serialVersionUID = 1L;

            @Override
            public String getToolTipText(MouseEvent event) {
                int viewColumn = columnAtPoint(event.getPoint());
                if (viewColumn < 0) {
                    return null;
                }
                int modelColumn = convertColumnIndexToModel(viewColumn);
                Field field = Field.at(modelColumn);
                return TestConfigurationTableModel.tooltipForColumn(modelColumn)
                        + (field == null ? "" : " Query field: " + field.name().toLowerCase(java.util.Locale.ROOT) + ".")
                        + " " + TableHeaderQuery.HEADER_HINT;
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
                if (sorter.getSortKeys().isEmpty() && sorter.getRowFilter() == null
                        && event.getButton() == MouseEvent.BUTTON1
                        && columnAtPoint(event.getPoint()) == convertColumnIndexToView(0)) {
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

    void applyQuery(String filters, String sorting) {
        var query = new TableQuery<IntFunction<Object>, Field>(filters, sorting, Field::parse);
        if (isEditing() && !getCellEditor().stopCellEditing()) {
            throw new IllegalArgumentException("Finish editing the current cell before applying a query.");
        }
        sorter.setRowFilter(filters.isBlank() ? null : new RowFilter<>() {
            @Override public boolean include(Entry<? extends TestConfigurationTableModel, ? extends Integer> row) {
                return query.matches(row::getValue);
            }
        });
        sorter.setSortKeys(query.sorts().stream()
                .map(sort -> new RowSorter.SortKey(sort.field().ordinal(),
                        sort.descending() ? SortOrder.DESCENDING : SortOrder.ASCENDING))
                .distinct().toList());
    }

    int[] selectedModelRows() {
        return Arrays.stream(getSelectedRows()).map(this::convertRowIndexToModel).sorted().toArray();
    }

    void selectModelRow(int row) {
        int visibleRow = convertRowIndexToView(row);
        if (visibleRow >= 0) { addRowSelectionInterval(visibleRow, visibleRow); }
    }

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
                int modelRow = convertRowIndexToModel(clickedRow);
                JPopupMenu menu = new JPopupMenu();
                JMenuItem newItem = new JMenuItem("New Row Above");
                newItem.addActionListener(action ->
                        newRowAbove.accept(modelRow));
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
        if (draggedRow < 0 || !sorter.getSortKeys().isEmpty() || sorter.getRowFilter() != null) {
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
        column = convertColumnIndexToModel(column);
        if (isSavedMap(row) && column >= 5 && column <= 19) {
            return false;
        }
        if (column == 9) {
            return !isSavedMap(row);
        }
        if (column == 4) {
            return valueAt(row, 3) != TestSuite.MapSource.AUTOMATIC_SEED;
        }
        if (column >= 14 && column <= 17) {
            return valueAt(row, 13) == TestSuite.EndpointMode.FIXED;
        }
        if (column == 18) {
            return valueAt(row, 13) == TestSuite.EndpointMode.APPROXIMATE_DISTANCE;
        }
        return getModel().isCellEditable(convertRowIndexToModel(row), column);
    }

    @Override
    public Component prepareRenderer(
            TableCellRenderer renderer, int row, int column) {
        Component component = super.prepareRenderer(renderer, row, column);
        column = convertColumnIndexToModel(column);
        if (isSavedMap(row) && column >= 5 && column <= 19) {
            component.setEnabled(false);
            return component;
        }
        if (column == 9) {
            component.setEnabled(!isSavedMap(row));
            return component;
        }
        if (column == 4) {
            component.setEnabled(valueAt(row, 3) != TestSuite.MapSource.AUTOMATIC_SEED);
            return component;
        }
        boolean disabled = (column >= 14 && column <= 18)
                && ((column == 18 && valueAt(row, 13)
                        != TestSuite.EndpointMode.APPROXIMATE_DISTANCE)
                || (column < 18 && valueAt(row, 13)
                        != TestSuite.EndpointMode.FIXED));
        component.setEnabled(!disabled);
        return component;
    }

    private boolean isSavedMap(int row) {
        return valueAt(row, 3) == TestSuite.MapSource.SAVED_MAP;
    }

    private Object valueAt(int viewRow, int modelColumn) {
        return getModel().getValueAt(convertRowIndexToModel(viewRow), modelColumn);
    }
}
