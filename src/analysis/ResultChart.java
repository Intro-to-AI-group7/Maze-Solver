package analysis;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/** Dependency-free chart rendering shared by Swing and headless PNG exports. */
public final class ResultChart {
    /** Available chart presentations. */
    public enum Type { BAR, LINE, SCATTER, BOX }
    /** Aggregation for bar and line points. Box plots always show the distribution. */
    public enum Aggregate { MEDIAN, MEAN, MIN, MAX }
    /** Category ordering. Numeric axes always retain numeric order. */
    public enum Order { AXIS, VALUE_ASC, VALUE_DESC }
    /** Raw observations, trimmed map means, or a direct side-by-side comparison. */
    public enum View { ITERATIONS, NORMALIZED, OVERLAY, BOTH }
    /** Complete immutable chart controls. */
    public record Spec(Type type, ResultQuery.Field x, ResultStatistics.Metric metric,
            Aggregate aggregate, Order order, boolean logY, boolean errorBars, boolean normalizeMaps, boolean showLegend) {
        /** Validates combinations before starting a render. */
        public Spec {
            java.util.Objects.requireNonNull(type); java.util.Objects.requireNonNull(x);
            java.util.Objects.requireNonNull(metric); java.util.Objects.requireNonNull(aggregate);
            java.util.Objects.requireNonNull(order);
            if ((type == Type.LINE || type == Type.SCATTER) && !x.isNumeric()) {
                throw new IllegalArgumentException("Line and scatter charts need a numeric X field.");
            }
        }
        /** Creates a chart over individual runs for compatibility with simple callers. */
        public Spec(Type type, ResultQuery.Field x, ResultStatistics.Metric metric,
                Aggregate aggregate, Order order, boolean logY, boolean errorBars) {
            this(type, x, metric, aggregate, order, logY, errorBars, false, true);
        }
        /** Creates a chart with an explicitly selected normalization mode. */
        public Spec(Type type, ResultQuery.Field x, ResultStatistics.Metric metric,
                Aggregate aggregate, Order order, boolean logY, boolean errorBars, boolean normalizeMaps) {
            this(type, x, metric, aggregate, order, logY, errorBars, normalizeMaps, true);
        }
    }
    private static final Color[] COLORS = {new Color(0x2563EB), new Color(0xD97706),
        new Color(0x059669), new Color(0xDC2626), new Color(0x7C3AED), new Color(0x0891B2),
        new Color(0xBE185D), new Color(0x475569), new Color(0x65A30D), new Color(0x9333EA),
        new Color(0x9A3412), new Color(0x0F766E), new Color(0x1E40AF), new Color(0x991B1B), new Color(0x581C87)};
    private ResultChart() { }
    private record Point(Object x, String series, ResultStatistics.Stats stats, double value,
            boolean typical, ResultRecord observation) {
        private Point(Object x, String series, ResultStatistics.Stats stats, double value) {
            this(x, series, stats, value, false, null);
        }
        private Point(Object x, String series, ResultStatistics.Stats stats, double value, boolean typical) {
            this(x, series, stats, value, typical, null);
        }
    }

    /** Renders a report image; scatter previews explicitly sample at most 4,000 points. */
    public static BufferedImage render(List<ResultRecord> selected, List<ResultRecord> all,
            Spec spec, String caption, int width, int height) {
        return render(selected, all, spec, caption, width, height, List.of(), false);
    }
    private static BufferedImage render(List<ResultRecord> selected, List<ResultRecord> all,
            Spec spec, String caption, int width, int height, List<ResultRecord> rawOverlay, boolean overlay) {
        validateDimensions(width, height);
        var baselines = new ResultStatistics.Baselines(all);
        List<Point> points = new ArrayList<>();
        int eligible = 0;
        if (spec.type == Type.SCATTER) {
            List<ResultRecord> valid = selected.stream().filter(r -> spec.x.value(r) != null
                    && spec.metric.value(r, baselines) != null
                    && (!spec.logY || spec.metric.value(r, baselines) > 0)).toList();
            List<Point> candidates = new ArrayList<>();
            if (spec.normalizeMaps) {
                Map<String, List<ResultRecord>> maps = new LinkedHashMap<>();
                int unknown = 0;
                for (ResultRecord r : valid) {
                    String key = (r.mapId().isBlank() ? "unknown-" + unknown++ : r.mapId()) + ":" + series(r);
                    maps.computeIfAbsent(key, ignored -> new ArrayList<>()).add(r);
                }
                for (var group : maps.values()) {
                    double x = group.stream().mapToDouble(r -> ((Number) spec.x.value(r)).doubleValue()).average().orElseThrow();
                    var stats = ResultStatistics.stats(group, spec.metric, baselines);
                    candidates.add(new Point(x, series(group.get(0)), stats, stats.mean(), overlay));
                }
            } else {
                for (ResultRecord r : valid) {
                    double value = spec.metric.value(r, baselines);
                    candidates.add(new Point(spec.x.value(r), series(r), ResultStatistics.Stats.of(List.of(value)), value));
                }
            }
            eligible = candidates.size();
            int count = Math.min(4000, candidates.size());
            for (int i = 0; i < count; i++) {
                points.add(candidates.get((int) ((long) i * candidates.size() / count)));
            }
        } else {
            Map<List<Object>, List<ResultRecord>> groups = new LinkedHashMap<>();
            if (spec.normalizeMaps) {
                Map<String, List<ResultRecord>> perMap = new LinkedHashMap<>();
                for (ResultRecord r : selected) {
                    perMap.computeIfAbsent(r.mapId() + ":" + series(r), ignored -> new ArrayList<>()).add(r);
                }
                for (var rows : perMap.values()) {
                    Object x = spec.x.isNumeric()
                            ? rows.stream().map(r -> spec.x.value(r)).filter(java.util.Objects::nonNull)
                                    .mapToDouble(v -> ((Number) v).doubleValue()).average().stream().boxed().findFirst().orElse(null)
                            : spec.x.value(rows.get(0));
                    if (x != null) { groups.computeIfAbsent(List.of(x, series(rows.get(0))),
                            ignored -> new ArrayList<>()).addAll(rows); }
                }
            } else {
                for (ResultRecord r : selected) {
                    Object x = spec.x.value(r);
                    if (x != null) { groups.computeIfAbsent(List.of(x, series(r)), ignored -> new ArrayList<>()).add(r); }
                }
            }
            for (var entry : groups.entrySet()) {
                var stats = spec.normalizeMaps
                        ? ResultStatistics.normalizedStats(entry.getValue(), spec.metric, baselines)
                        : ResultStatistics.stats(entry.getValue(), spec.metric, baselines);
                eligible += stats.count();
                double value = aggregate(stats, spec.aggregate);
                if (stats.count() > 0 && (!spec.logY || (value > 0
                        && (spec.type != Type.BOX || stats.min() > 0)))) {
                    points.add(new Point(entry.getKey().get(0), entry.getKey().get(1).toString(), stats, value, overlay));
                }
            }
        }
        if (overlay) {
            for (ResultRecord r : rawOverlay) {
                Double value = spec.metric.value(r, baselines);
                Object x = spec.x.value(r);
                if (value != null && x != null && (!spec.logY || value > 0)) {
                    points.add(new Point(x, series(r), ResultStatistics.Stats.of(List.of(value)), value, false, r));
                }
            }
        }
        List<String> series = points.stream().map(Point::series).distinct().sorted().toList();
        if (series.size() > COLORS.length) { throw new IllegalArgumentException("Filter to at most 15 algorithm/execution series."); }
        List<Object> categories = new ArrayList<>(points.stream().map(Point::x).distinct().toList());
        Comparator<Object> axisOrder = spec.x.isNumeric()
                ? Comparator.comparingDouble(v -> ((Number) v).doubleValue()) : Comparator.comparing(Object::toString);
        categories.sort(axisOrder);
        if (spec.type == Type.BAR || spec.type == Type.BOX) {
            if (categories.size() > 40) { throw new IllegalArgumentException("Filter or group to at most 40 chart categories."); }
            if (spec.order != Order.AXIS) {
                Map<Object, Double> values = new LinkedHashMap<>();
                for (Object category : categories) {
                    values.put(category, points.stream().filter(p -> p.x.equals(category))
                            .filter(p -> !overlay || p.typical).mapToDouble(Point::value).average().orElse(0));
                }
                Comparator<Object> byValue = Comparator.comparingDouble(values::get);
                categories.sort(spec.order == Order.VALUE_DESC ? byValue.reversed().thenComparing(axisOrder)
                        : byValue.thenComparing(axisOrder));
            }
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE); g.fillRect(0, 0, width, height);
            g.setColor(new Color(0x172554)); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
            g.drawString(spec.metric + " by " + spec.x + " - " + (overlay ? "OVERLAY" : spec.type), 30, 35);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12)); g.setColor(Color.DARK_GRAY);
            String maps = selected.stream().anyMatch(r -> r.mapId().isBlank()) ? "unknown"
                    : Long.toString(selected.stream().map(ResultRecord::mapId).distinct().count());
            g.drawString((overlay ? "Raw runs: " + rawOverlay.size() + " | retained: " : "Runs: ")
                    + selected.size() + " | distinct maps: " + maps + " | metric samples: " + eligible
                    + (spec.type == Type.SCATTER ? " | plotted: " + points.size() : " | statistic: " + spec.aggregate)
                    + (spec.logY ? " | log10 Y (positive values only)" : ""), 30, 58);
            g.drawString(spec.normalizeMaps
                    ? "One mean per map/algorithm/execution; maps receive equal weight. Other conditions pooled within each group."
                    : "Individual runs; repeated solves are not independent maps. Other conditions pooled within each group.", 30, 77);
            drawWrapped(g, caption.isBlank() ? "Filters: none; sort: arrival order" : caption, 30, 96, width - 60, 3);
            int left = 110, top = 165, right = width - 40, bottom = height - 165;
            if (points.isEmpty()) {
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
                g.drawString("No eligible measurements. Check filters, UCS baselines, and axis scale.", left, top + 60);
                return image;
            }
            double ymin = spec.logY ? Double.POSITIVE_INFINITY : 0;
            double ymax = spec.logY ? Double.NEGATIVE_INFINITY : 0;
            for (Point p : points) {
                double low = spec.type == Type.BOX ? p.stats.min() : p.value;
                double high = spec.type == Type.BOX ? p.stats.max() : p.value;
                if (spec.errorBars && spec.aggregate == Aggregate.MEAN && Double.isFinite(p.stats.stddev())) {
                    low -= p.stats.stddev(); high += p.stats.stddev();
                }
                if (!spec.logY || low > 0) { ymin = Math.min(ymin, transform(low, spec.logY)); }
                ymax = Math.max(ymax, transform(high, spec.logY));
            }
            if (!Double.isFinite(ymin)) { ymin = ymax - 1; }
            if (ymax <= ymin) { ymax = ymin + 1; }
            double pad = (ymax - ymin) * .08;
            ymax += pad; if (ymin < 0 || spec.logY) { ymin -= pad; }
            final double minY = ymin, maxY = ymax;
            java.util.function.DoubleToIntFunction yPixel = value -> bottom
                    - (int) ((transform(value, spec.logY) - minY) / (maxY - minY) * (bottom - top));
            double xmin = spec.x.isNumeric() ? ((Number) categories.get(0)).doubleValue() : 0;
            double xmax = spec.x.isNumeric() ? ((Number) categories.get(categories.size() - 1)).doubleValue() : 1;
            if (xmax <= xmin) { xmax = xmin + 1; }
            final double minX = xmin, maxX = xmax;
            java.util.function.Function<Point, Integer> xPixel = p -> spec.type == Type.LINE || spec.type == Type.SCATTER
                    ? left + (int) ((((Number) p.x).doubleValue() - minX) / (maxX - minX) * (right - left))
                    : left + (int) ((categories.indexOf(p.x) + .5) / categories.size() * (right - left));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            for (int i = 0; i <= 5; i++) {
                int y = bottom - i * (bottom - top) / 5;
                g.setColor(new Color(0xE2E8F0)); g.drawLine(left, y, right, y);
                double value = minY + i * (maxY - minY) / 5;
                g.setColor(Color.DARK_GRAY); g.drawString(format(spec.logY ? Math.pow(10, value) : value), 16, y + 4);
            }
            g.setColor(Color.DARK_GRAY); g.drawLine(left, top, left, bottom); g.drawLine(left, bottom, right, bottom);
            if (spec.type == Type.LINE || spec.type == Type.SCATTER) {
                for (int i = 0; i <= 5; i++) {
                    g.drawString(format(minX + i * (maxX - minX) / 5), left + i * (right - left) / 5 - 15, bottom + 22);
                }
            } else {
                for (int i = 0; i < categories.size(); i++) {
                    int x = left + (int) ((i + .5) / categories.size() * (right - left));
                    Graphics2D label = (Graphics2D) g.create();
                    label.translate(x, bottom + 10); label.rotate(Math.PI / 6);
                    String text = categories.get(i).toString();
                    label.drawString(text.length() > 24 ? text.substring(0, 21) + "..." : text, 0, 0); label.dispose();
                }
            }
            boolean barOverlay = overlay && spec.type == Type.BAR;
            if (barOverlay) {
                drawBarOverlay(g, points, categories, series, left, right,
                        spec.logY ? bottom : yPixel.applyAsInt(0), yPixel);
            } else if (overlay) {
                drawNumericOverlayTraces(g, points, xPixel, yPixel);
            }
            g.setStroke(new BasicStroke(2f));
            for (String name : barOverlay ? List.<String>of() : series) {
                g.setColor(seriesColor(name));
                List<Point> line = points.stream().filter(p -> p.series.equals(name)).sorted(
                        Comparator.comparing((Point p) -> p.x, axisOrder).thenComparing(Point::typical)).toList();
                Point previous = null;
                for (Point p : line) {
                    int x = xPixel.apply(p), y = yPixel.applyAsInt(p.value);
                    int slot = Math.max(2, (right - left) / Math.max(1, categories.size()) / (series.size() + 1));
                    int barWidth = Math.min(42, slot - 1);
                    if (spec.type == Type.BAR || spec.type == Type.BOX) {
                        x += (int) ((series.indexOf(name) - (series.size() - 1) / 2.0) * slot);
                    }
                    if (overlay) {
                        if (p.typical) {
                            int[] xs = {x, x + 8, x, x - 8};
                            int[] ys = {y - 8, y, y + 8, y};
                            g.fillPolygon(xs, ys, 4);
                            g.setColor(Color.BLACK); g.drawPolygon(xs, ys, 4);
                            g.setColor(seriesColor(name));
                        } else {
                            g.setColor(new Color(seriesColor(name).getRGB() & 0xFFFFFF | 0x70000000, true));
                            g.fillOval(x - 2, y - 2, 4, 4);
                            g.setColor(seriesColor(name));
                        }
                    } else if (spec.type == Type.BAR) {
                        int zero = spec.logY ? bottom : yPixel.applyAsInt(0);
                        g.fillRect(x - barWidth / 2, Math.min(y, zero), barWidth, Math.max(1, Math.abs(zero - y)));
                    } else if (spec.type == Type.BOX) {
                        int q1 = yPixel.applyAsInt(p.stats.q1()), q3 = yPixel.applyAsInt(p.stats.q3());
                        g.drawLine(x, yPixel.applyAsInt(p.stats.min()), x, yPixel.applyAsInt(p.stats.max()));
                        g.fillRect(x - barWidth / 2, q3, barWidth, Math.max(1, q1 - q3));
                        g.setColor(Color.BLACK); g.drawLine(x - barWidth / 2, yPixel.applyAsInt(p.stats.median()),
                                x + barWidth / 2, yPixel.applyAsInt(p.stats.median()));
                        g.setColor(seriesColor(name));
                    } else {
                        if (spec.type == Type.LINE && previous != null) {
                            g.drawLine(xPixel.apply(previous), yPixel.applyAsInt(previous.value), x, y);
                        }
                        g.fillOval(x - 3, y - 3, 6, 6);
                    }
                    if ((!overlay || p.typical) && spec.errorBars && spec.aggregate == Aggregate.MEAN && spec.type != Type.BOX
                            && spec.type != Type.SCATTER && Double.isFinite(p.stats.stddev())
                            && (!spec.logY || p.value - p.stats.stddev() > 0)) {
                        int lo = yPixel.applyAsInt(p.value - p.stats.stddev()), hi = yPixel.applyAsInt(p.value + p.stats.stddev());
                        g.drawLine(x, lo, x, hi); g.drawLine(x - 4, lo, x + 4, lo); g.drawLine(x - 4, hi, x + 4, hi);
                    }
                    previous = p;
                }
            }
            g.setColor(Color.DARK_GRAY);
            g.drawString(spec.x.toString(), (left + right) / 2, height - 66);
            g.drawString(spec.metric.toString(), left, top - 12);
            int legendX = 30, legendY = height - 55;
            for (String name : spec.showLegend ? series : List.<String>of()) {
                long retained = selected.stream().filter(r -> series(r).equals(name)).count();
                long original = overlay ? rawOverlay.stream().filter(r -> series(r).equals(name)).count() : retained;
                String legend = name + (overlay ? " (raw " + original + ", retained " + retained + ")" : " (runs " + retained + ")");
                int needed = g.getFontMetrics().stringWidth(legend) + 36;
                if (legendX + needed > width - 30) { legendY += 18; legendX = 30; }
                g.setColor(seriesColor(name)); g.fillRect(legendX, legendY - 9, 12, 10);
                g.setColor(Color.DARK_GRAY); g.drawString(legend, legendX + 18, legendY); legendX += needed;
            }
            g.drawString(barOverlay ? "Solid bars: normalized means. Faint bars: raw runs."
                    : overlay ? "Dots and lines: raw runs in iteration order. Diamonds: normalized means."
                    : spec.type == Type.BOX ? "Boxes: Q1 / median / Q3; whiskers: min / max."
                    : spec.errorBars && spec.aggregate == Aggregate.MEAN ? "Error bars: +/- one sample SD (not confidence intervals)."
                    : "", 30, height - 7);
        } finally { g.dispose(); }
        return image;
    }

    private record NumericTraceKey(String suite, String configuration, String map, String series) { }

    private static void drawNumericOverlayTraces(Graphics2D g, List<Point> points,
            java.util.function.Function<Point, Integer> xPixel,
            java.util.function.DoubleToIntFunction yPixel) {
        Map<NumericTraceKey, List<Point>> traces = new LinkedHashMap<>();
        for (Point point : points) {
            if (point.observation == null) { continue; }
            ResultRecord row = point.observation;
            var result = row.result();
            var key = new NumericTraceKey(result.getSuiteName(), result.getConfigurationId(),
                    row.mapId(), point.series);
            traces.computeIfAbsent(key, ignored -> new ArrayList<>()).add(point);
        }
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (var entry : traces.entrySet()) {
            List<Point> trace = entry.getValue();
            if (trace.size() < 2) { continue; }
            trace.sort(Comparator.comparingInt(point -> point.observation.result().getIteration()));
            Color color = seriesColor(entry.getKey().series);
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 115));
            var path = new java.awt.geom.Path2D.Float();
            Point first = trace.get(0);
            path.moveTo(xPixel.apply(first), yPixel.applyAsInt(first.value));
            for (int i = 1; i < trace.size(); i++) {
                Point point = trace.get(i);
                path.lineTo(xPixel.apply(point), yPixel.applyAsInt(point.value));
            }
            g.draw(path);
        }
    }

    private static void drawBarOverlay(Graphics2D g, List<Point> points, List<Object> categories,
            List<String> series, int left, int right, int baseline,
            java.util.function.DoubleToIntFunction yPixel) {
        Map<Object, Map<String, List<Point>>> groups = new LinkedHashMap<>();
        for (Point point : points) {
            groups.computeIfAbsent(point.x, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(point.series, ignored -> new ArrayList<>()).add(point);
        }
        double categoryWidth = (right - left) / (double) categories.size();
        for (int category = 0; category < categories.size(); category++) {
            Map<String, List<Point>> categoryGroups = groups.get(categories.get(category));
            List<String> names = series.stream().filter(categoryGroups::containsKey).toList();
            double laneWidth = categoryWidth * .8 / names.size();
            double start = left + (category + .1) * categoryWidth;
            for (String name : names) {
                List<Point> group = categoryGroups.get(name);
                double center = start + laneWidth / 2;
                double groupWidth = Math.min(140, laneWidth * .9);
                Color color = seriesColor(name);
                List<Point> raw = group.stream().filter(point -> point.observation != null)
                        .sorted(Comparator.comparingInt(p -> p.observation.result().getIteration())).toList();
                int meanWidth = Math.max(1, (int) (groupWidth * .36));
                double rawWidth = (groupWidth - meanWidth) / Math.max(1, raw.size());
                int leftBars = raw.size() / 2;
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 55));
                for (int i = 0; i < raw.size(); i++) {
                    Point point = raw.get(i);
                    double offset = i < leftBars
                            ? -meanWidth / 2.0 - rawWidth * (leftBars - i - .5)
                            : meanWidth / 2.0 + rawWidth * (i - leftBars + .5);
                    int x = (int) Math.round(center + offset);
                    int y = yPixel.applyAsInt(point.value);
                    fillBar(g, x, Math.max(1, (int) rawWidth - 1), baseline, y);
                }
                g.setColor(color);
                for (Point point : group) {
                    if (point.typical) { fillBar(g, (int) Math.round(center), meanWidth, baseline, yPixel.applyAsInt(point.value)); }
                }
                start += laneWidth;
            }
        }
    }

    private static void fillBar(Graphics2D g, int center, int width, int baseline, int value) {
        g.fillRect(center - width / 2, Math.min(baseline, value), width, Math.max(1, Math.abs(baseline - value)));
    }
    /** Exports an opaque high-resolution PNG with labels, filters, and sample counts. */
    public static void export(List<ResultRecord> selected, List<ResultRecord> all,
            Spec spec, String caption, Path file, int width, int height) throws IOException {
        BufferedImage image = render(selected, all, spec, caption, width, height);
        Files.createDirectories(file.toAbsolutePath().getParent());
        if (!ImageIO.write(image, "png", file.toFile())) { throw new IOException("PNG writer unavailable."); }
    }

    /** Renders either dataset or both, using the same source and filter selection. */
    public static BufferedImage renderView(List<ResultRecord> selected, List<ResultRecord> all,
            Spec spec, View view, ResultStatistics.Outliers outliers,
            ResultStatistics.Metric outlierMetric, String caption, int width, int height) {
        validateDimensions(width, height);
        Spec raw = new Spec(spec.type, spec.x, spec.metric, spec.aggregate, spec.order, spec.logY, spec.errorBars, false, spec.showLegend);
        if (view == View.ITERATIONS) {
            return render(selected, all, raw, "All iterations. " + caption, width, height);
        }
        if (selected.stream().anyMatch(r -> r.mapId().isBlank())) {
            throw new IllegalArgumentException("Normalized views need map identities. Choose ITERATIONS for legacy CSV, or rerun/export the suite.");
        }
        Spec normalized = new Spec(spec.type, spec.x, spec.metric, Aggregate.MEAN, spec.order, spec.logY, spec.errorBars, true, spec.showLegend);
        var prepared = ResultStatistics.prepare(selected, all, outliers, outlierMetric);
        String normalizedCaption = "Normalized: original=" + selected.size() + ", excluded=" + prepared.excluded()
                + ", retained=" + prepared.rows().size() + "; outliers=" + outliers + " on " + outlierMetric + ". " + caption;
        if (view == View.NORMALIZED) { return render(prepared.rows(), all, normalized, normalizedCaption, width, height); }
        if (view == View.OVERLAY) {
            Spec overlay = new Spec(spec.x.isNumeric() ? Type.SCATTER : Type.BAR,
                    spec.x, spec.metric, Aggregate.MEAN, spec.order, spec.logY, false, true, spec.showLegend);
            return render(prepared.rows(), all, overlay, normalizedCaption, width, height, selected, true);
        }
        BufferedImage combined = new BufferedImage(width * 2, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = combined.createGraphics();
        try {
            g.drawImage(render(selected, all, raw, "All iterations. " + caption, width, height), 0, 0, null);
            g.drawImage(render(prepared.rows(), all, normalized, normalizedCaption, width, height), width, 0, null);
        } finally { g.dispose(); }
        return combined;
    }
    private static void validateDimensions(int width, int height) {
        if (width < 640 || height < 480 || width > 4096 || height > 4096) {
            throw new IllegalArgumentException("Chart dimensions must be 640..4096 by 480..4096 pixels.");
        }
    }

    /** Writes the selected all-iteration/normalized comparison to PNG. */
    public static void exportView(List<ResultRecord> selected, List<ResultRecord> all,
            Spec spec, View view, ResultStatistics.Outliers outliers, ResultStatistics.Metric outlierMetric,
            String caption, Path file, int width, int height) throws IOException {
        BufferedImage image = renderView(selected, all, spec, view, outliers, outlierMetric, caption, width, height);
        Files.createDirectories(file.toAbsolutePath().getParent());
        if (!ImageIO.write(image, "png", file.toFile())) { throw new IOException("PNG writer unavailable."); }
    }
    private static String series(ResultRecord r) { return r.result().getAlgorithm().name() + " / " + r.execution(); }
    private static Color seriesColor(String name) {
        int algorithm = maze.search.MazeSolver.Algorithm.valueOf(name.split(" / ")[0]).ordinal();
        int mode = name.contains("FIXED_PARALLEL") ? 1 : name.contains("ADAPTIVE_PARALLEL") ? 2 : 0;
        return COLORS[(algorithm * 3 + mode) % COLORS.length];
    }
    private static double aggregate(ResultStatistics.Stats s, Aggregate aggregate) {
        return switch (aggregate) { case MEAN -> s.mean(); case MEDIAN -> s.median(); case MIN -> s.min(); case MAX -> s.max(); };
    }
    private static double transform(double value, boolean log) { return log ? Math.log10(value) : value; }
    private static String format(double value) { return String.format(Locale.ROOT, Math.abs(value) >= 10000 ? "%.2e" : "%.3g", value); }
    private static void drawWrapped(Graphics2D g, String text, int x, int y, int width, int lines) {
        String remaining = text.replace('\n', ' ');
        for (int line = 0; line < lines && !remaining.isEmpty(); line++) {
            int end = remaining.length();
            while (end > 1 && g.getFontMetrics().stringWidth(remaining.substring(0, end)) > width) { end--; }
            g.drawString(remaining.substring(0, end), x, y + line * 15); remaining = remaining.substring(end);
        }
    }
}
