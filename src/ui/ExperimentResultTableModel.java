package ui;

import javax.swing.table.AbstractTableModel;
import analysis.ResultRecord;
import analysis.ResultQuery;
import java.util.List;

/**
 * Read-only table model for measured experiment results.
 */
final class ExperimentResultTableModel extends AbstractTableModel {
    private static final long serialVersionUID = 1L;

    static final String[] COLUMNS = {
        "Configuration", "Iteration", "Algorithm", "Solved", "Path Cost",
        "Path Length", "Nodes Explored", "Runtime (ms)", "Map Source", "Seed", "Map ID", "Rows", "Columns", "Execution", "Review"
    };

    private transient List<ResultRecord> records = List.of();

    void setPage(List<ResultRecord> rows) {
        records = List.copyOf(rows);
        fireTableDataChanged();
    }

    @Override public int getRowCount() { return records.size(); }
    @Override public int getColumnCount() { return COLUMNS.length; }
    @Override public String getColumnName(int column) { return COLUMNS[column]; }

    @Override public Object getValueAt(int row, int column) {
        ResultRecord record = recordAt(row);
        var result = record.result();
        return switch (column) {
            case 0 -> result.getConfigurationId();
            case 1 -> result.getIteration();
            case 2 -> result.getAlgorithm();
            case 3 -> result.isSolved();
            case 4 -> result.isSolved() ? result.getPathCost() : null;
            case 5 -> result.isSolved() ? result.getPathLength() : null;
            case 6 -> result.getNodesExplored();
            case 7 -> result.getRuntimeMs();
            case 8 -> result.getMapSource();
            case 9 -> result.getSeedLabel();
            case 10 -> record.mapId().isBlank() ? "UNKNOWN" : record.mapId();
            case 11 -> result.getRows();
            case 12 -> result.getColumns();
            case 13 -> record.execution();
            case 14 -> "Review";
            default -> throw new IndexOutOfBoundsException(column);
        };
    }

    ResultRecord recordAt(int row) { return records.get(row); }

    String copyValueAt(int row, int column) {
        Object value = getValueAt(row, column);
        return value == null ? "N/A" : value.toString();
    }

    String copyRowAt(int row) {
        var text = new java.util.StringJoiner("\t");
        for (int column = 0; column < getColumnCount(); column++) {
            if (!isCellEditable(row, column)) {
                text.add(copyValueAt(row, column).replace('\t', ' ').replace('\r', ' ').replace('\n', ' '));
            }
        }
        return text.toString();
    }

    static ResultQuery.Field fieldAt(int column) {
        return switch (column) {
            case 0 -> ResultQuery.Field.CONFIGURATION; case 1 -> ResultQuery.Field.ITERATION;
            case 2 -> ResultQuery.Field.ALGORITHM; case 3 -> ResultQuery.Field.SOLVED;
            case 4 -> ResultQuery.Field.COST; case 5 -> ResultQuery.Field.LENGTH;
            case 6 -> ResultQuery.Field.NODES; case 7 -> ResultQuery.Field.RUNTIME_MS;
            case 8 -> ResultQuery.Field.SOURCE; case 9 -> ResultQuery.Field.SEED;
            case 10 -> ResultQuery.Field.MAP; case 11 -> ResultQuery.Field.ROWS;
            case 12 -> ResultQuery.Field.COLUMNS; case 13 -> ResultQuery.Field.EXECUTION; default -> null;
        };
    }

    @Override public Class<?> getColumnClass(int column) {
        return switch (column) {
            case 1, 5, 6, 11, 12 -> Integer.class; case 4 -> Long.class;
            case 7 -> Double.class; case 3 -> Boolean.class; default -> Object.class;
        };
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return column == 14;
    }
}
