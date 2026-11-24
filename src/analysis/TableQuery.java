package analysis;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Typed filter and sort syntax shared by the configuration and results tables. */
public final class TableQuery<R, F extends TableQuery.Column<R>> {
    /** A queryable column and its value for a row. */
    public interface Column<R> {
        boolean isNumeric();
        Object value(R row);
    }

    /** A sort key in priority order. */
    public record Sort<F>(F field, boolean descending) { }

    private static final Pattern RULE = Pattern.compile("^([a-zA-Z_]+)\\s*(!=|>=|<=|=|>|<|~)\\s*(.+)$");
    private final List<Predicate<R>> rules = new ArrayList<>();
    private final List<Sort<F>> sorts = new ArrayList<>();

    /** Validates both expressions against the columns supplied by the caller. */
    public TableQuery(String filters, String sorting, Function<String, F> fieldFor) {
        if (!filters.isBlank()) {
            for (String expression : filters.split(";", -1)) {
                Matcher match = RULE.matcher(expression.trim());
                if (!match.matches()) {
                    throw new IllegalArgumentException("Expected field operator value: " + expression);
                }
                F field = fieldFor.apply(match.group(1));
                String operator = match.group(2);
                List<String> values = List.of(match.group(3).trim().split("\\|", -1));
                if (values.stream().anyMatch(String::isBlank)) {
                    throw new IllegalArgumentException("Filter values cannot be blank.");
                }
                if (field.isNumeric()) {
                    values.forEach(TableQuery::number);
                    if (operator.equals("~")) {
                        throw new IllegalArgumentException("Contains requires a text field.");
                    }
                } else if (!List.of("=", "!=", "~").contains(operator)) {
                    throw new IllegalArgumentException("Text fields support =, !=, or ~.");
                }
                rules.add(row -> matches(field.value(row), operator, values, field.isNumeric()));
            }
        }
        if (!sorting.isBlank()) {
            for (String expression : sorting.split(",", -1)) {
                String[] parts = expression.trim().split(":", -1);
                if (parts.length > 2 || (parts.length == 2
                        && !parts[1].equalsIgnoreCase("asc") && !parts[1].equalsIgnoreCase("desc"))) {
                    throw new IllegalArgumentException("Expected field:asc or field:desc: " + expression);
                }
                sorts.add(new Sort<>(fieldFor.apply(parts[0]), parts.length == 2
                        && parts[1].equalsIgnoreCase("desc")));
            }
        }
    }

    /** Tests a row against all rules. */
    public boolean matches(R row) {
        return rules.stream().allMatch(rule -> rule.test(row));
    }

    /** Returns the keys in priority order. */
    public List<Sort<F>> sorts() {
        return List.copyOf(sorts);
    }

    /** Returns the field name from a filter clause, including a clause with an unfinished value. */
    public static String filterField(String clause) {
        return clause.trim().split("[!=<>~]", 2)[0].trim();
    }

    private static boolean matches(Object actual, String operator, List<String> values, boolean numeric) {
        if (actual == null) { return false; }
        if (operator.equals("~")) {
            String text = actual.toString().toLowerCase(Locale.ROOT);
            return values.stream().anyMatch(value -> text.contains(value.toLowerCase(Locale.ROOT)));
        }
        if (operator.equals("!=")) {
            return values.stream().noneMatch(value -> compare(actual, value, numeric) == 0);
        }
        return values.stream().anyMatch(value -> {
            int comparison = compare(actual, value, numeric);
            return switch (operator) {
                case "=" -> comparison == 0;
                case ">" -> comparison > 0;
                case "<" -> comparison < 0;
                case ">=" -> comparison >= 0;
                case "<=" -> comparison <= 0;
                default -> false;
            };
        });
    }

    private static int compare(Object first, String second, boolean numeric) {
        return numeric ? number(first).compareTo(number(second))
                : first.toString().compareToIgnoreCase(second);
    }

    private static BigDecimal number(Object value) {
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Expected a finite number: " + value, ex);
        }
    }
}
