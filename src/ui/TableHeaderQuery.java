package ui;

import analysis.TableQuery;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import javax.swing.JComboBox;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Applies actions from a table header through the same fields used for typed queries. */
final class TableHeaderQuery {
    static final String HEADER_HINT = "Click to sort, Shift-click to add a sort key, or right-click for sort and filter options.";

    private final JTextField filters;
    private final JTextField sorting;
    private final Runnable apply;

    TableHeaderQuery(JTextField filters, JTextField sorting, Runnable apply) {
        this.filters = filters;
        this.sorting = sorting;
        this.apply = apply;
    }

    void install(JTable table, IntFunction<? extends Enum<?>> fieldAt) {
        var header = table.getTableHeader();
        header.setToolTipText(HEADER_HINT);
        MouseAdapter listener = new MouseAdapter() {
            private boolean dragged;

            @Override public void mousePressed(MouseEvent event) {
                dragged = false;
                showMenu(event);
            }
            @Override public void mouseReleased(MouseEvent event) {
                showMenu(event);
            }
            @Override public void mouseDragged(MouseEvent event) {
                dragged = true;
            }
            @Override public void mouseClicked(MouseEvent event) {
                Enum<?> field = field(event);
                if (field != null && !dragged && SwingUtilities.isLeftMouseButton(event)
                        && !event.isPopupTrigger() && event.getClickCount() == 1) {
                    toggleSort(field, event.isShiftDown());
                }
            }
            private void showMenu(MouseEvent event) {
                if (!event.isPopupTrigger()) { return; }
                Enum<?> field = field(event);
                if (field instanceof TableQuery.Column<?> column) {
                    int viewColumn = header.columnAtPoint(event.getPoint());
                    menu(table, table.getColumnName(viewColumn), field, column.isNumeric())
                            .show(header, event.getX(), event.getY());
                }
            }
            private Enum<?> field(MouseEvent event) {
                int viewColumn = header.columnAtPoint(event.getPoint());
                return viewColumn < 0 ? null : fieldAt.apply(table.convertColumnIndexToModel(viewColumn));
            }
        };
        header.addMouseListener(listener);
        header.addMouseMotionListener(listener);
    }

    JPopupMenu menu(JTable table, String title, Enum<?> field, boolean numeric) {
        String key = key(field);
        JPopupMenu menu = new JPopupMenu();
        add(menu, "Sort ascending", true, () -> setSort(key, "asc", false));
        add(menu, "Sort descending", true, () -> setSort(key, "desc", false));
        add(menu, "Add ascending sort key", true, () -> setSort(key, "asc", true));
        add(menu, "Add descending sort key", true, () -> setSort(key, "desc", true));
        add(menu, "Clear column sort", sortClauses().stream().anyMatch(part -> isSortFor(part, key)),
                () -> setSort(key, "", true));
        menu.addSeparator();
        add(menu, "Add filter…", true, () -> promptForFilter(table, title, field, numeric));
        add(menu, "Clear column filters", filterClauses().stream().anyMatch(part -> isFilterFor(part, key)),
                () -> {
                    filters.setText(String.join(";", filterClauses().stream()
                            .filter(part -> !isFilterFor(part, key)).toList()));
                    apply.run();
                });
        return menu;
    }

    void toggleSort(Enum<?> field, boolean additive) {
        String key = key(field);
        boolean ascending = sortClauses().stream().filter(part -> isSortFor(part, key))
                .findFirst().map(part -> !part.toLowerCase(Locale.ROOT).endsWith(":desc")).orElse(false);
        setSort(key, ascending ? "desc" : "asc", additive);
    }

    void addFilter(Enum<?> field, String operator, String value) {
        if (value.isBlank()) { return; }
        String expression = key(field) + operator + value.trim();
        filters.setText(filters.getText().isBlank() ? expression : filters.getText() + ";" + expression);
        apply.run();
    }

    private void promptForFilter(JTable table, String title, Enum<?> field, boolean numeric) {
        JComboBox<String> operator = new JComboBox<>(numeric
                ? new String[] {"=", "!=", ">", ">=", "<", "<="}
                : new String[] {"=", "!=", "~"});
        operator.setToolTipText("~ matches text containing the value.");
        JTextField value = new JTextField(22);
        value.setToolTipText("Use | to enter alternatives.");
        JPanel input = new JPanel();
        input.add(operator);
        input.add(value);
        value.setCaretColor(UITheme.text());
        SwingUtilities.invokeLater(value::requestFocusInWindow);
        if (JOptionPane.showConfirmDialog(table, input, "Filter " + title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            addFilter(field, (String) operator.getSelectedItem(), value.getText());
        }
    }

    private void setSort(String key, String direction, boolean additive) {
        List<String> clauses = new ArrayList<>();
        String replacement = direction.isEmpty() ? "" : key + ":" + direction;
        boolean replaced = false;
        if (additive) {
            for (String part : sortClauses()) {
                if (!isSortFor(part, key)) {
                    clauses.add(part);
                } else if (!replaced && !replacement.isEmpty()) {
                    clauses.add(replacement);
                    replaced = true;
                }
            }
        }
        if (!replaced && !replacement.isEmpty()) { clauses.add(replacement); }
        sorting.setText(String.join(",", clauses));
        apply.run();
    }

    private List<String> filterClauses() {
        return filters.getText().isBlank() ? List.of() : Arrays.asList(filters.getText().split(";", -1));
    }
    private List<String> sortClauses() {
        return sorting.getText().isBlank() ? List.of() : Arrays.asList(sorting.getText().split(",", -1));
    }
    private static boolean isSortFor(String clause, String key) {
        return clause.split(":", 2)[0].trim().equalsIgnoreCase(key);
    }
    private static boolean isFilterFor(String clause, String key) {
        return TableQuery.filterField(clause).equalsIgnoreCase(key);
    }
    private static String key(Enum<?> field) { return field.name().toLowerCase(Locale.ROOT); }

    private static void add(JPopupMenu menu, String label, boolean enabled, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.setEnabled(enabled);
        item.addActionListener(event -> action.run());
        menu.add(item);
    }
}
