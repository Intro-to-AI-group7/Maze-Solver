package ui;

import java.awt.Component;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.IntConsumer;
import javax.swing.AbstractCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.filechooser.FileNameExtensionFilter;

import experiments.TestSuite;
import experiments.TestSuiteIO;
import maze.map.Maze;
import maze.search.MazeSolver;

/**
 * Reusable editors and renderers used by the experiment tables.
 */
final class TestSuiteTableComponents {
    private TestSuiteTableComponents() {
    }

    static Set<MazeSolver.Algorithm> parseAlgorithms(String value) {
        EnumSet<MazeSolver.Algorithm> algorithms =
                EnumSet.noneOf(MazeSolver.Algorithm.class);
        for (String name : value.split(",")) {
            algorithms.add(MazeSolver.Algorithm.valueOf(name.trim().toUpperCase()));
        }
        return algorithms;
    }

    static String algorithmsText(Set<MazeSolver.Algorithm> algorithms) {
        StringBuilder text = new StringBuilder();
        for (MazeSolver.Algorithm algorithm : algorithms) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(algorithm.name());
        }
        return text.toString();
    }
}

final class WrappedHeaderRenderer extends DefaultTableCellRenderer {
    private static final long serialVersionUID = 1L;

    @Override
    public Component getTableCellRendererComponent(
            JTable table, Object value, boolean selected, boolean focused,
            int row, int column) {
        JLabel label = (JLabel) super.getTableCellRendererComponent(
                table, value, selected, focused, row, column);
        label.setHorizontalAlignment(CENTER);
        label.setVerticalAlignment(CENTER);
        label.setText("<html><div style='text-align:center'>" + value + "</div></html>");
        if (table.getModel() instanceof TestConfigurationTableModel) {
            label.setToolTipText(TestConfigurationTableModel.tooltipForColumn(
                    table.convertColumnIndexToModel(column)));
        }
        return label;
    }
}

final class JComboBoxEditor<T> extends AbstractCellEditor implements TableCellEditor {
    private static final long serialVersionUID = 1L;
    private final JComboBox<T> comboBox;

    JComboBoxEditor(T[] values) {
        comboBox = new JComboBox<>(values);
        UITheme.styleComboBox(comboBox);
    }

    @Override
    public Component getTableCellEditorComponent(
            JTable table, Object value, boolean selected, int row, int column) {
        comboBox.setSelectedItem(value);
        return comboBox;
    }

    @Override
    public Object getCellEditorValue() {
        return comboBox.getSelectedItem();
    }
}

final class IntegerEditor extends JTextField implements TableCellEditor {
    private static final long serialVersionUID = 1L;
    private final javax.swing.DefaultCellEditor delegate;

    IntegerEditor() {
        delegate = new javax.swing.DefaultCellEditor(this);
        delegate.setClickCountToStart(1);
        UITheme.styleTextField(this);
    }

    @Override
    public Component getTableCellEditorComponent(
            JTable table, Object value, boolean selected, int row, int column) {
        setText(String.valueOf(value));
        selectAll();
        return this;
    }

    @Override
    public Object getCellEditorValue() {
        try {
            return new BigDecimal(getText().trim())
                    .setScale(0, RoundingMode.DOWN).toPlainString();
        } catch (NumberFormatException ex) {
            return "0";
        }
    }

    @Override public boolean isCellEditable(java.util.EventObject event) {
        return delegate.isCellEditable(event);
    }
    @Override public boolean shouldSelectCell(java.util.EventObject event) {
        return delegate.shouldSelectCell(event);
    }
    @Override public boolean stopCellEditing() { return delegate.stopCellEditing(); }
    @Override public void cancelCellEditing() { delegate.cancelCellEditing(); }
    @Override public void addCellEditorListener(
            javax.swing.event.CellEditorListener listener) {
        delegate.addCellEditorListener(listener);
    }
    @Override public void removeCellEditorListener(
            javax.swing.event.CellEditorListener listener) {
        delegate.removeCellEditorListener(listener);
    }
}

final class EnumComboBoxEditor<T extends Enum<T>>
        extends AbstractCellEditor implements TableCellEditor {
    private static final long serialVersionUID = 1L;
    private final JComboBox<String> comboBox;
    private final T[] values;

    EnumComboBoxEditor(T[] values, String[] labels) {
        this.values = values;
        comboBox = new JComboBox<>(labels);
        UITheme.styleComboBox(comboBox);
    }

    @Override
    public Component getTableCellEditorComponent(
            JTable table, Object value, boolean selected, int row, int column) {
        for (int index = 0; index < values.length; index++) {
            if (values[index].equals(value)) {
                comboBox.setSelectedIndex(index);
                break;
            }
        }
        return comboBox;
    }

    @Override
    public Object getCellEditorValue() {
        return values[comboBox.getSelectedIndex()];
    }
}

final class EnumLabelRenderer extends DefaultTableCellRenderer {
    private static final long serialVersionUID = 1L;
    private final String[] labels;

    EnumLabelRenderer(String[] labels) {
        this.labels = labels;
    }

    @Override
    public Component getTableCellRendererComponent(
            JTable table, Object value, boolean selected, boolean focused,
            int row, int column) {
        JLabel label = (JLabel) super.getTableCellRendererComponent(
                table, value, selected, focused, row, column);
        int ordinal = ((Enum<?>) value).ordinal();
        label.setText(ordinal < labels.length ? labels[ordinal] : value.toString());
        return label;
    }
}

final class BooleanEditor extends AbstractCellEditor implements TableCellEditor {
    private static final long serialVersionUID = 1L;
    private final JCheckBox checkBox = new JCheckBox();

    BooleanEditor() {
        checkBox.setHorizontalAlignment(JCheckBox.CENTER);
        checkBox.addActionListener(event -> fireEditingStopped());
    }

    @Override
    public Component getTableCellEditorComponent(
            JTable table, Object value, boolean selected, int row, int column) {
        checkBox.setSelected(Boolean.TRUE.equals(value));
        return checkBox;
    }

    @Override
    public Object getCellEditorValue() {
        return checkBox.isSelected();
    }
}

final class BooleanRenderer extends JCheckBox implements TableCellRenderer {
    private static final long serialVersionUID = 1L;

    BooleanRenderer() {
        setHorizontalAlignment(JCheckBox.CENTER);
        setOpaque(true);
    }

    @Override
    public Component getTableCellRendererComponent(
            JTable table, Object value, boolean selected, boolean focused,
            int row, int column) {
        setSelected(Boolean.TRUE.equals(value));
        setBackground(selected ? table.getSelectionBackground() : table.getBackground());
        setForeground(selected ? table.getSelectionForeground() : table.getForeground());
        return this;
    }
}

final class MapValueEditor extends AbstractCellEditor implements TableCellEditor {
    private static final long serialVersionUID = 1L;
    private final JPanel editorPanel = new JPanel(new BorderLayout(2, 0));
    private final JTextField seedField = new JTextField();
    private final JButton button = new JButton("Import map...");
    private JTable table;
    private int row;

    MapValueEditor() {
        UITheme.styleTextField(seedField);
        editorPanel.setOpaque(true);
        editorPanel.add(seedField, BorderLayout.CENTER);
        editorPanel.add(button, BorderLayout.EAST);
        button.addActionListener(event -> {
            if (table.getValueAt(row, 3) != TestSuite.MapSource.SAVED_MAP) {
                fireEditingStopped();
                return;
            }
            JFileChooser chooser = new JFileChooser(
                    TestSuiteIO.defaultExportDirectory().toFile());
            chooser.setFileFilter(new FileNameExtensionFilter(
                    "Maze Solver maps (*.msmap)", "msmap"));
            if (chooser.showOpenDialog(table) == JFileChooser.APPROVE_OPTION) {
                try {
                    String name = chooser.getSelectedFile().getName();
                    if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".msmap")) {
                        throw new java.io.IOException("Please select a .msmap map file.");
                    }
                    String contents = java.nio.file.Files.readString(
                            chooser.getSelectedFile().toPath());
                    Maze imported = Maze.importFromText(contents);
                    table.getModel().setValueAt(imported.getLength(), row, 5);
                    table.getModel().setValueAt(imported.getWidth(), row, 6);
                    table.getModel().setValueAt(contents, row, 20);
                    table.getModel().setValueAt(name, row, 4);
                    fireEditingStopped();
                } catch (java.io.IOException | IllegalArgumentException ex) {
                    JOptionPane.showMessageDialog(table, "Could not read map: "
                            + ex.getMessage(), "Map Import Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        });
    }

    @Override
    public Component getTableCellEditorComponent(
            JTable table, Object value, boolean selected, int row, int column) {
        this.table = table;
        this.row = row;
        boolean savedMap = table.getValueAt(row, 3) == TestSuite.MapSource.SAVED_MAP;
        seedField.setText(String.valueOf(value));
        seedField.selectAll();
        button.setVisible(savedMap);
        seedField.setVisible(!savedMap);
        return editorPanel;
    }

    @Override
    public Object getCellEditorValue() {
        return seedField.isVisible() ? seedField.getText().trim() : button.getText();
    }
}

final class MapValueRenderer extends JButton implements TableCellRenderer {
    private static final long serialVersionUID = 1L;
    private final JLabel label = new JLabel();

    MapValueRenderer() {
        setOpaque(true);
        label.setOpaque(true);
    }

    @Override
    public Component getTableCellRendererComponent(
            JTable table, Object value, boolean selected, boolean focused,
            int row, int column) {
        boolean savedMap = table.getValueAt(row, 3) == TestSuite.MapSource.SAVED_MAP;
        String text = String.valueOf(value);
        if (savedMap) {
            setText(text);
            setEnabled(true);
            setBackground(selected ? table.getSelectionBackground() : table.getBackground());
            setForeground(selected ? table.getSelectionForeground() : table.getForeground());
            return this;
        }
        label.setText(text);
        label.setEnabled(table.getValueAt(row, 3) != TestSuite.MapSource.AUTOMATIC_SEED);
        label.setBackground(selected ? table.getSelectionBackground() : table.getBackground());
        label.setForeground(selected ? table.getSelectionForeground() : table.getForeground());
        return label;
    }
}

final class ReviewButtonRenderer extends JButton implements TableCellRenderer {
    private static final long serialVersionUID = 1L;

    ReviewButtonRenderer() {
        setText("Review");
        setOpaque(true);
        setFocusPainted(false);
    }

    @Override
    public Component getTableCellRendererComponent(
            JTable table, Object value, boolean selected, boolean focused,
            int row, int column) {
        setText("Review");
        setEnabled(true);
        setBackground(selected ? UITheme.selectionBackground() : UITheme.surface());
        setForeground(selected ? UITheme.selectionText() : UITheme.text());
        setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(UITheme.border()),
                javax.swing.BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        return this;
    }
}

@SuppressWarnings("serial")
final class ReviewButtonEditor extends AbstractCellEditor implements TableCellEditor {
    private static final long serialVersionUID = 1L;
    private final JButton button = new JButton("Review");
    private final IntConsumer reviewAction;
    private int row;

    ReviewButtonEditor(IntConsumer reviewAction) {
        this.reviewAction = reviewAction;
        button.setOpaque(true);
        button.setFocusPainted(false);
        button.addActionListener(event -> {
            reviewAction.accept(row);
            fireEditingStopped();
        });
    }

    @Override
    public Component getTableCellEditorComponent(
            JTable table, Object value, boolean selected, int row, int column) {
        this.row = table.convertRowIndexToModel(row);
        button.setBackground(UITheme.selectionBackground());
        button.setForeground(UITheme.selectionText());
        button.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(UITheme.border()),
                javax.swing.BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        return button;
    }

    @Override
    public Object getCellEditorValue() {
        return "Review";
    }
}

@SuppressWarnings("serial")
final class AlgorithmEditor extends AbstractCellEditor implements TableCellEditor {
    private static final long serialVersionUID = 1L;
    private final JButton button = new JButton();
    private Set<MazeSolver.Algorithm> selected =
            EnumSet.noneOf(MazeSolver.Algorithm.class);

    AlgorithmEditor() {
        button.addActionListener(event -> showPopup());
    }

    @Override
    public Component getTableCellEditorComponent(
            JTable table, Object value, boolean selected, int row, int column) {
        this.selected = TestSuiteTableComponents.parseAlgorithms(String.valueOf(value));
        updateButton();
        return button;
    }

    @Override
    public Object getCellEditorValue() {
        return TestSuiteTableComponents.algorithmsText(selected);
    }

    private void showPopup() {
        JPopupMenu popup = new JPopupMenu();
        JPanel choices = new JPanel(new GridLayout(0, 1));
        java.util.EnumMap<MazeSolver.Algorithm, JCheckBox> boxes =
                new java.util.EnumMap<>(MazeSolver.Algorithm.class);
        for (MazeSolver.Algorithm algorithm : MazeSolver.Algorithm.values()) {
            JCheckBox box = new JCheckBox(algorithm.name(), selected.contains(algorithm));
            boxes.put(algorithm, box);
            choices.add(box);
        }
        popup.add(choices);
        JButton apply = new JButton("Apply");
        apply.addActionListener(event -> {
            selected = EnumSet.noneOf(MazeSolver.Algorithm.class);
            for (MazeSolver.Algorithm algorithm : boxes.keySet()) {
                if (boxes.get(algorithm).isSelected()) {
                    selected.add(algorithm);
                }
            }
            updateButton();
            popup.setVisible(false);
            fireEditingStopped();
        });
        popup.add(apply);
        popup.show(button, 0, button.getHeight());
    }

    private void updateButton() {
        button.setText(TestSuiteTableComponents.algorithmsText(selected));
    }
}
