package ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Window;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.SwingUtilities;
import javax.swing.DefaultListCellRenderer;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;

/**
 * Shared visual language for the Swing screens.
 */
final class UITheme {
    private static final Color LIGHT_BACKGROUND = new Color(0xF4F7FB);
    private static final Color LIGHT_SURFACE = Color.WHITE;
    private static final Color LIGHT_TEXT = new Color(0x1F2937);
    private static final Color LIGHT_MUTED = new Color(0x64748B);
    private static final Color LIGHT_BORDER = new Color(0xD8E0EA);
    private static final Color DARK_BACKGROUND = new Color(0x111827);
    private static final Color DARK_SURFACE = new Color(0x1F2937);
    private static final Color DARK_TEXT = new Color(0xE5E7EB);
    private static final Color DARK_MUTED = new Color(0xA5B4C7);
    private static final Color DARK_BORDER = new Color(0x374151);
    static final Color ACCENT = new Color(0x3B82F6);
    private static boolean dark;
    private static final List<JComboBox<?>> styledComboBoxes = new ArrayList<>();
    private static final List<JTextField> styledTextFields = new ArrayList<>();

    private UITheme() {
    }

    static void install() {
        UIManager.put("Button.arc", 10);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("TextField.margin", new java.awt.Insets(6, 8, 6, 8));
        refreshAllWindows();
    }

    static boolean isDark() {
        return dark;
    }

    static void toggle() {
        dark = !dark;
        MazeRenderer.refreshTheme();
        refreshAllWindows();
    }

    static JButton iconButton(boolean trash, Runnable action) {
        JButton button = new JButton();
        button.setIcon(new RowActionIcon(trash));
        button.setPreferredSize(new java.awt.Dimension(38, 32));
        button.setToolTipText(trash ? "Delete selected rows" : "Duplicate selected rows");
        button.getAccessibleContext().setAccessibleName(
                trash ? "Delete selected rows" : "Duplicate selected rows");
        button.addActionListener(event -> action.run());
        return button;
    }

    static void styleComboBox(JComboBox<?> comboBox) {
        if (!styledComboBoxes.contains(comboBox)) {
            styledComboBoxes.add(comboBox);
        }
        comboBox.setBackground(surface());
        comboBox.setForeground(text());
        comboBox.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index,
                    boolean selected, boolean focused) {
                JLabel cell = (JLabel) super.getListCellRendererComponent(
                        list, value, index, selected, focused);
                cell.setBackground(selected ? ACCENT : surface());
                cell.setForeground(selected ? Color.WHITE : text());
                return cell;
            }
        });
        comboBox.revalidate();
        comboBox.repaint();
    }

    static void styleTextField(JTextField textField) {
        if (!styledTextFields.contains(textField)) {
            styledTextFields.add(textField);
        }
        textField.setBackground(surface());
        textField.setForeground(text());
        textField.setCaretColor(text());
        textField.setSelectionColor(ACCENT);
        textField.setSelectedTextColor(Color.WHITE);
        textField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border()),
                BorderFactory.createEmptyBorder(2, 5, 2, 5)));
        textField.repaint();
    }

    private static final class RowActionIcon implements Icon {
        private final boolean trash;

        RowActionIcon(boolean trash) {
            this.trash = trash;
        }

        @Override
        public int getIconWidth() {
            return 20;
        }

        @Override
        public int getIconHeight() {
            return 20;
        }

        @Override
        public void paintIcon(Component component, java.awt.Graphics graphics, int x, int y) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            g.setColor(component.isEnabled() ? text() : muted());
            g.setStroke(new java.awt.BasicStroke(2f));
            int centerX = x + 10;
            int top = y + 3;
            if (trash) {
                g.drawRect(centerX - 5, top + 4, 10, 11);
                g.drawLine(centerX - 7, top + 1, centerX + 7, top + 1);
                g.drawLine(centerX - 3, top - 2, centerX + 3, top - 2);
            } else {
                g.drawRect(centerX - 6, top + 1, 10, 12);
                g.drawRect(centerX - 2, top + 5, 10, 12);
            }
            g.dispose();
        }
    }

    static void apply(Component component) {
        component.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                apply(child);
            }
        }
        if (component instanceof JTable) {
            JTable table = (JTable) component;
            table.setBackground(surface());
            table.setForeground(text());
            table.setGridColor(border());
            table.setSelectionBackground(selectionBackground());
            table.setSelectionForeground(selectionText());
            table.getTableHeader().setBackground(surface());
            table.getTableHeader().setForeground(text());
        } else if (component instanceof AbstractButton
                && !(component instanceof JCheckBox)) {
            AbstractButton button = (AbstractButton) component;
            button.setForeground(text());
            button.setBackground(surface());
            button.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(border()),
                    BorderFactory.createEmptyBorder(5, 10, 5, 10)));
            button.setFocusPainted(false);
        } else if (component instanceof JComboBox) {
            JComboBox<?> comboBox = (JComboBox<?>) component;
            styleComboBox(comboBox);
        } else if (component instanceof JTextField || component instanceof JSpinner) {
            JComponent input = (JComponent) component;
            if (component instanceof JTextField) {
                styleTextField((JTextField) component);
            } else {
                input.setBackground(surface());
                input.setForeground(text());
                input.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(border()),
                        BorderFactory.createEmptyBorder(2, 5, 2, 5)));
            }
        } else if (component instanceof JCheckBox) {
            JCheckBox checkBox = (JCheckBox) component;
            checkBox.setBackground(background());
            checkBox.setForeground(text());
        } else if (component instanceof JComponent) {
            JComponent swingComponent = (JComponent) component;
            swingComponent.setBackground(
                    component instanceof javax.swing.JPanel ? background() : surface());
            swingComponent.setForeground(text());
        }
    }

    static Color background() {
        return dark ? DARK_BACKGROUND : LIGHT_BACKGROUND;
    }

    static Color surface() {
        return dark ? DARK_SURFACE : LIGHT_SURFACE;
    }

    static Color text() {
        return dark ? DARK_TEXT : LIGHT_TEXT;
    }

    static Color muted() {
        return dark ? DARK_MUTED : LIGHT_MUTED;
    }

    static Color border() {
        return dark ? DARK_BORDER : LIGHT_BORDER;
    }

    static Color selectionBackground() {
        return dark ? new Color(0x3B526F) : new Color(0xC7DDF5);
    }

    static Color selectionText() {
        return dark ? DARK_TEXT : LIGHT_TEXT;
    }

    private static void refreshAllWindows() {
        UIManager.put("Panel.background", background());
        UIManager.put("Panel.foreground", text());
        UIManager.put("Label.foreground", text());
        UIManager.put("Table.background", surface());
        UIManager.put("Table.foreground", text());
        UIManager.put("TableHeader.background", surface());
        UIManager.put("TableHeader.foreground", text());
        UIManager.put("TextField.background", surface());
        UIManager.put("TextField.foreground", text());
        UIManager.put("ComboBox.background", surface());
        UIManager.put("ComboBox.foreground", text());
        UIManager.put("ComboBox.selectionBackground", ACCENT);
        UIManager.put("ComboBox.selectionForeground", Color.WHITE);
        UIManager.put("List.background", surface());
        UIManager.put("List.foreground", text());
        UIManager.put("List.selectionBackground", ACCENT);
        UIManager.put("List.selectionForeground", Color.WHITE);
        UIManager.put("Spinner.background", surface());
        UIManager.put("Spinner.foreground", text());
        UIManager.put("Button.background", surface());
        UIManager.put("Button.foreground", text());
        for (Window window : Window.getWindows()) {
            if (window.isDisplayable()) {
                SwingUtilities.updateComponentTreeUI(window);
                apply(window);
                window.repaint();
            }
        }
        for (JComboBox<?> comboBox : styledComboBoxes) {
            styleComboBox(comboBox);
        }
        for (JTextField textField : styledTextFields) {
            styleTextField(textField);
        }
    }
}
