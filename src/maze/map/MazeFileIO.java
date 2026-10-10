package maze.map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Reads and writes the human-readable {@code .msmap} format.
 *
 * <p>This class is deliberately separate from {@link Maze}: the maze remains
 * the domain model while this class owns file-format concerns. The static
 * convenience methods on {@code Maze} delegate here for source compatibility.
 */
public final class MazeFileIO {
    private MazeFileIO() {
    }

    /**
     * Imports a map file.
     *
     * @param filePath source path
     * @return imported maze
     * @throws IOException if the file cannot be read
     */
    public static Maze importFromFile(String filePath) throws IOException {
        return importFromLines(Files.readAllLines(Paths.get(filePath)));
    }

    /**
     * Imports a complete map text representation.
     *
     * @param contents map text
     * @return imported maze
     * @throws IOException retained for compatibility with the legacy API
     */
    public static Maze importFromText(String contents) throws IOException {
        if (contents == null) {
            throw new IllegalArgumentException("Maze contents cannot be null.");
        }
        return importFromLines(contents.lines().collect(java.util.stream.Collectors.toList()));
    }

    /**
     * Exports a maze to a checked {@code .msmap} path.
     *
     * @param maze maze to export
     * @param filePath destination path
     * @throws IOException if validation or writing fails
     */
    public static void exportToFile(Maze maze, String filePath) throws IOException {
        if (filePath == null || !filePath.toLowerCase(java.util.Locale.ROOT)
                .endsWith(Maze.MAP_EXTENSION)) {
            throw new IOException("Maze files must use the " + Maze.MAP_EXTENSION + " extension.");
        }
        Files.writeString(Paths.get(filePath), exportToText(maze));
    }

    /**
     * Serializes a maze using the complete weighted map format.
     *
     * @param maze maze to serialize
     * @return map text
     */
    public static String exportToText(Maze maze) {
        if (maze == null) {
            throw new IllegalArgumentException("Maze cannot be null.");
        }
        if (maze.getStart() == null) {
            throw new IllegalStateException("Start position is not set.");
        }
        if (maze.getGoal() == null) {
            throw new IllegalStateException("End position is not set.");
        }

        StringBuilder output = new StringBuilder();
        output.append(maze.getLength()).append(System.lineSeparator());
        output.append(maze.getWidth()).append(System.lineSeparator());
        output.append("start: ").append(maze.getStart().row).append(",")
                .append(maze.getStart().col).append(System.lineSeparator());
        output.append("end: ").append(maze.getGoal().row).append(",")
                .append(maze.getGoal().col).append(System.lineSeparator());

        for (int row = 0; row < maze.getLength(); row++) {
            for (int col = 0; col < maze.getWidth(); col++) {
                output.append(maze.isOccupied(row, col) ? '1' : '0');
            }
            output.append(System.lineSeparator());
        }

        output.append(System.lineSeparator()).append("WEIGHTS")
                .append(System.lineSeparator());
        for (int row = 0; row < maze.getLength(); row++) {
            for (int col = 0; col < maze.getWidth(); col++) {
                Maze.Node node = maze.getNode(row, col);
                for (Maze.Dir direction : Maze.Dir.values()) {
                    if (direction.idx() > 0) {
                        output.append(",");
                    }
                    output.append(node == null ? 0 : node.getMoveCost(direction));
                }
                output.append(System.lineSeparator());
            }
        }
        return output.toString();
    }

    private static Maze importFromLines(List<String> lines) throws IOException {
        if (lines.size() < 4) {
            throw new IllegalArgumentException("Maze file must contain at least 4 header lines.");
        }

        int length = Integer.parseInt(lines.get(0).trim());
        int width = Integer.parseInt(lines.get(1).trim());
        if (length <= 0 || width <= 0) {
            throw new IllegalArgumentException("Maze dimensions must be positive.");
        }

        int layoutStart = 4;
        int layoutEnd = layoutStart + length;
        if (lines.size() < layoutEnd) {
            throw new IllegalArgumentException(
                    "Maze file does not contain the expected number of layout rows.");
        }

        int[] startPos = parseCoordinateLine(lines.get(2), "start");
        int[] endPos = parseCoordinateLine(lines.get(3), "end");
        Maze maze = new Maze(length, width);

        for (int row = 0; row < length; row++) {
            String layoutRow = lines.get(layoutStart + row).trim();
            if (layoutRow.length() != width) {
                throw new IllegalArgumentException("Layout row " + row + " has width "
                        + layoutRow.length() + " but expected " + width + ".");
            }
            for (int col = 0; col < width; col++) {
                char cell = layoutRow.charAt(col);
                if (cell == '1') {
                    maze.createCell(row, col, 0);
                } else if (cell != '0') {
                    throw new IllegalArgumentException("Invalid cell character '" + cell
                            + "' at row " + row + ", col " + col + ". Use only 0 or 1.");
                }
            }
        }

        int weightsMarker = findWeightsMarker(lines, layoutEnd);
        if (weightsMarker >= 0) {
            int firstWeightLine = weightsMarker + 1;
            int expectedWeightRows = length * width;
            if (lines.size() < firstWeightLine + expectedWeightRows) {
                throw new IllegalArgumentException("WEIGHTS section must contain exactly "
                        + expectedWeightRows + " rows.");
            }
            for (int row = 0; row < length; row++) {
                for (int col = 0; col < width; col++) {
                    int lineIndex = firstWeightLine + row * width + col;
                    int[] costs = parseWeightRow(lines.get(lineIndex), row, col);
                    validateWeightRow(maze, row, col, costs);
                    Maze.Node node = maze.getNode(row, col);
                    if (node != null) {
                        for (Maze.Dir direction : Maze.Dir.values()) {
                            node.setMoveCost(direction, costs[direction.idx()]);
                        }
                    }
                }
            }
            for (int i = firstWeightLine + expectedWeightRows; i < lines.size(); i++) {
                if (!lines.get(i).trim().isEmpty()) {
                    throw new IllegalArgumentException("Unexpected data after the WEIGHTS section at line "
                            + (i + 1) + ".");
                }
            }
        } else {
            for (int i = layoutEnd; i < lines.size(); i++) {
                if (!lines.get(i).trim().isEmpty()) {
                    throw new IllegalArgumentException("Unexpected data after the maze layout at line "
                            + (i + 1) + ". Expected WEIGHTS.");
                }
            }
        }

        if (!maze.isInBounds(startPos[0], startPos[1])
                || !maze.isOccupied(startPos[0], startPos[1])) {
            throw new IllegalArgumentException(
                    "Start position must be in bounds and on a path cell (1).");
        }
        if (!maze.isInBounds(endPos[0], endPos[1])
                || !maze.isOccupied(endPos[0], endPos[1])) {
            throw new IllegalArgumentException(
                    "End position must be in bounds and on a path cell (1).");
        }
        maze.setStart(startPos[0], startPos[1]);
        maze.setGoal(endPos[0], endPos[1]);
        return maze;
    }

    private static int[] parseCoordinateLine(String line, String label) {
        if (line == null) {
            throw new IllegalArgumentException("Missing line for " + label + " coordinates.");
        }
        String trimmed = line.trim();
        String prefix = label + ":";
        if (!trimmed.toLowerCase().startsWith(prefix)) {
            throw new IllegalArgumentException("Expected '" + prefix
                    + " row,col' but got: " + line);
        }
        String[] parts = trimmed.substring(prefix.length()).trim().split(",");
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Expected coordinates as row,col for " + label + ".");
        }
        return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
    }

    private static int findWeightsMarker(List<String> lines, int layoutEnd) {
        int index = layoutEnd;
        while (index < lines.size() && lines.get(index).trim().isEmpty()) {
            index++;
        }
        return index < lines.size() && "WEIGHTS".equalsIgnoreCase(lines.get(index).trim())
                ? index : -1;
    }

    private static int[] parseWeightRow(String line, int row, int col) {
        if (line == null || line.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing weights at (" + row + "," + col + ").");
        }
        String[] values = line.trim().split(",", -1);
        if (values.length != Maze.Dir.values().length) {
            throw new IllegalArgumentException(
                    "Expected four weights at (" + row + "," + col + ").");
        }
        int[] costs = new int[values.length];
        for (int index = 0; index < values.length; index++) {
            try {
                costs[index] = Integer.parseInt(values[index].trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid movement cost at (" + row + ","
                        + col + "), direction index " + index + ".", ex);
            }
            if (costs[index] < Maze.DISABLED_MOVEMENT_COST
                    || costs[index] > Maze.MAX_MOVEMENT_COST) {
                throw new IllegalArgumentException("Movement costs must be 0 or an integer from "
                        + Maze.MIN_MOVEMENT_COST + " to " + Maze.MAX_MOVEMENT_COST
                        + " at (" + row + "," + col + ").");
            }
        }
        return costs;
    }

    private static void validateWeightRow(Maze maze, int row, int col, int[] costs) {
        Maze.Node node = maze.getNode(row, col);
        for (Maze.Dir direction : Maze.Dir.values()) {
            boolean hasNeighbor = node != null && node.getNeighbor(direction) != null;
            int cost = costs[direction.idx()];
            if (!hasNeighbor && cost != Maze.DISABLED_MOVEMENT_COST) {
                throw new IllegalArgumentException("Weight at (" + row + "," + col + ") in "
                        + direction + " points to no traversable neighbor.");
            }
            if (hasNeighbor && cost == Maze.DISABLED_MOVEMENT_COST) {
                continue;
            }
        }
    }
}
