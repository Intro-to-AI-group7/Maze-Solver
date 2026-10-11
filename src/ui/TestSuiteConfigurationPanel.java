package ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.Objects;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;

/**
 * Layout and actions for editing the suite name and test configurations.
 */
@SuppressWarnings("auxiliaryclass")
final class TestSuiteConfigurationPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    TestSuiteConfigurationPanel(
            JTextField suiteNameField,
            JTable configTable,
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
        controls.add(button("Back to Main Menu", back));
        add(controls, BorderLayout.NORTH);

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
