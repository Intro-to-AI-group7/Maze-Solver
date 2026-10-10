package ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.Objects;
import javax.swing.JButton;
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
            Runnable removeRow,
            Runnable saveSuite,
            Runnable loadSuite,
            Runnable back) {
        super(new BorderLayout(6, 6));
        Objects.requireNonNull(suiteNameField);
        Objects.requireNonNull(configTable);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(new JLabel("Suite name:"));
        controls.add(suiteNameField);
        controls.add(button("Add Row", addRow));
        controls.add(button("Remove Row", removeRow));
        controls.add(button("Save Suite", saveSuite));
        controls.add(button("Load Suite", loadSuite));
        controls.add(button("Back to Main Menu", back));
        add(controls, BorderLayout.NORTH);

        configTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        configTable.setRowHeight(26);
        configTable.getTableHeader().setDefaultRenderer(new WrappedHeaderRenderer());
        configTable.getTableHeader().setPreferredSize(new Dimension(0, 42));
        add(new JScrollPane(configTable), BorderLayout.CENTER);
    }

    private static JButton button(String label, Runnable action) {
        JButton button = new JButton(label);
        button.addActionListener(event -> action.run());
        return button;
    }
}
