package ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.Objects;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;

/**
 * Layout and actions for editing the suite name and test configurations.
 */
@SuppressWarnings("auxiliaryclass")
final class TestSuiteConfigurationPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    TestSuiteConfigurationPanel(
            JTextField suiteNameField,
            TestConfigurationTable configTable,
            Runnable addRow,
            Runnable duplicateRows,
            Runnable removeRows,
            Runnable saveSuite,
            Runnable loadSuite,
            Runnable back,
            JComboBox<?> executionModeCombo) {
        super(new BorderLayout(6, 6));
        Objects.requireNonNull(suiteNameField);
        Objects.requireNonNull(configTable);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(new JLabel("Suite name:"));
        controls.add(suiteNameField);
        controls.add(button("Add Row", addRow));
        JButton duplicateButton = UITheme.iconButton(false, duplicateRows);
        controls.add(duplicateButton);
        JButton removeButton = UITheme.iconButton(true, removeRows);
        controls.add(removeButton);
        duplicateButton.setEnabled(false);
        removeButton.setEnabled(false);
        configTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                boolean hasSelection = configTable.getSelectedRowCount() > 0;
                duplicateButton.setEnabled(hasSelection);
                removeButton.setEnabled(hasSelection);
            }
        });
        controls.add(button("Save Suite", saveSuite));
        controls.add(button("Load Suite", loadSuite));
        controls.add(new JLabel("Execution:"));
        controls.add(executionModeCombo);
        executionModeCombo.setToolTipText(
                "Choose sequential, fixed parallel, or adaptive parallel execution");
        JButton themeButton = button(UITheme.isDark() ? "Light" : "Dark", UITheme::toggle);
        themeButton.addActionListener(event ->
                themeButton.setText(UITheme.isDark() ? "Light" : "Dark"));
        themeButton.setToolTipText("Switch between light and dark mode");
        controls.add(themeButton);
        JToggleButton queryToggle = new JToggleButton("Sort / Filter");
        queryToggle.setToolTipText(TableHeaderQuery.HEADER_HINT);
        controls.add(queryToggle);
        controls.add(button("Back to Main Menu", back));

        JTextField filters = new JTextField(24);
        JTextField sorting = new JTextField(19);
        filters.setToolTipText("Separate rules with ; and alternatives with |. Example: algorithms~UCS;iterations>=10");
        sorting.setToolTipText("Sort keys in priority order. Example: rows:asc,iterations:desc");
        JPanel queryBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        Runnable apply = () -> {
            queryToggle.setSelected(true);
            queryBar.setVisible(true);
            try {
                configTable.applyQuery(filters.getText(), sorting.getText());
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Configuration query", JOptionPane.ERROR_MESSAGE);
            }
        };
        filters.addActionListener(event -> apply.run());
        sorting.addActionListener(event -> apply.run());
        JLabel filterLabel = new JLabel("Filters");
        filterLabel.setLabelFor(filters);
        queryBar.add(filterLabel);
        queryBar.add(filters);
        JLabel sortLabel = new JLabel("Sort");
        sortLabel.setLabelFor(sorting);
        queryBar.add(sortLabel);
        queryBar.add(sorting);
        queryBar.add(button("Apply", apply));
        queryBar.add(button("Reset", () -> {
            filters.setText("");
            sorting.setText("");
            apply.run();
        }));
        queryBar.setVisible(false);
        queryToggle.addActionListener(event -> queryBar.setVisible(queryToggle.isSelected()));
        JPanel header = new JPanel(new BorderLayout());
        header.add(controls, BorderLayout.NORTH);
        header.add(queryBar, BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);

        new TableHeaderQuery(filters, sorting, apply).install(configTable, TestConfigurationTableModel.Field::at);

        configTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        configTable.setRowHeight(26);
        configTable.getTableHeader().setDefaultRenderer(new WrappedHeaderRenderer());
        configTable.getTableHeader().setPreferredSize(new Dimension(0, 42));
        add(new JScrollPane(configTable), BorderLayout.CENTER);
        UITheme.apply(this);
    }

    private static JButton button(String label, Runnable action) {
        JButton button = new JButton(label);
        button.addActionListener(event -> action.run());
        return button;
    }
}
