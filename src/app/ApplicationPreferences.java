package app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Stores user-level application preferences that are needed before the JVM
 * starts, such as the requested maximum heap size.
 */
public final class ApplicationPreferences {
    private static final String MAX_HEAP_GB_KEY = "maxHeapGb";
    private static final int DEFAULT_MAX_HEAP_GB = 0;
    private static final int MIN_MAX_HEAP_GB = 1;
    private static final int MAX_MAX_HEAP_GB = 64;

    private ApplicationPreferences() {
    }

    /**
     * Returns the configured heap size in gigabytes, or {@code 0} when the
     * launcher should calculate a safe heap from current available memory.
     *
     * @return configured heap size in gigabytes, or zero for automatic sizing
     */
    public static int getMaxHeapGb() {
        Properties properties = load();
        String value = properties.getProperty(MAX_HEAP_GB_KEY);
        if (value == null) {
            return DEFAULT_MAX_HEAP_GB;
        }
        try {
            int heapGb = Integer.parseInt(value.trim());
            return isValidMaxHeapGb(heapGb) ? heapGb : DEFAULT_MAX_HEAP_GB;
        } catch (NumberFormatException exception) {
            return DEFAULT_MAX_HEAP_GB;
        }
    }

    /**
     * Saves the requested maximum heap size. A value of {@code 0} enables
     * launch-time automatic sizing based on current available memory.
     *
     * @param heapGb explicit heap size in gigabytes, or zero for automatic sizing
     * @throws IOException if the preferences file cannot be written
     */
    public static void setMaxHeapGb(int heapGb) throws IOException {
        if (heapGb != DEFAULT_MAX_HEAP_GB && !isValidMaxHeapGb(heapGb)) {
            throw new IllegalArgumentException(
                    "Maximum heap must be 0 or between "
                            + MIN_MAX_HEAP_GB + " and " + MAX_MAX_HEAP_GB + " GB.");
        }
        Properties properties = load();
        if (heapGb == DEFAULT_MAX_HEAP_GB) {
            properties.remove(MAX_HEAP_GB_KEY);
        } else {
            properties.setProperty(MAX_HEAP_GB_KEY, Integer.toString(heapGb));
        }
        Path preferencesPath = preferencesPath();
        Files.createDirectories(preferencesPath.getParent());
        try (OutputStream output = Files.newOutputStream(preferencesPath)) {
            properties.store(output, "Maze-Solver preferences");
        }
    }

    /**
     * Returns the location of the preferences file for diagnostics and
     * launcher integration.
     *
     * @return user-level preferences file path
     */
    public static Path preferencesPath() {
        return Paths.get(System.getProperty("user.home"), ".maze-solver", "preferences.properties");
    }

    private static boolean isValidMaxHeapGb(int heapGb) {
        return heapGb >= MIN_MAX_HEAP_GB && heapGb <= MAX_MAX_HEAP_GB;
    }

    private static Properties load() {
        Properties properties = new Properties();
        Path path = preferencesPath();
        if (!Files.isRegularFile(path)) {
            return properties;
        }
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (IOException exception) {
            System.err.println("Could not read preferences from " + path + ": " + exception.getMessage());
        }
        return properties;
    }
}
