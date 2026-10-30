package io.apistudio.cp.service;

import io.apistudio.contracts.exec.ExecutionResult;
import io.apistudio.contracts.exec.TraceStep;
import io.apistudio.engine.expr.Json;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Mesin assertion untuk hasil eksekusi flow (BRD FR-SIM-06/07/08).
 *
 * <p>Jenis assertion yang didukung:
 * <ul>
 *   <li>{@code response.status} — status HTTP</li>
 *   <li>{@code response.header} — nilai header (target = nama header)</li>
 *   <li>{@code response.jsonpath} — nilai pada path body (dukung notasi titik)</li>
 *   <li>{@code response.body} — kecocokan body penuh</li>
 *   <li>{@code response.schema} — keberadaan field wajib pada body</li>
 *   <li>{@code db.value} — hasil query sandbox dibandingkan dengan nilai harapan</li>
 *   <li>{@code db.rowcount} — jumlah baris hasil query</li>
 *   <li>{@code trace.nodeExecuted} — node tertentu dieksekusi</li>
 *   <li>{@code trace.nodeSkipped} — node tertentu tidak dieksekusi</li>
 *   <li>{@code trace.durationUnder} — durasi node/total di bawah ambang</li>
 * </ul>
 *
 * <p>Operator: {@code equals}, {@code notEquals}, {@code contains},
 * {@code startsWith}, {@code endsWith}, {@code matches}, {@code gt}, {@code gte},
 * {@code lt}, {@code lte}, {@code exists}, {@code notExists}.
 */
@Service
public class AssertionEngine {

    /**
     * Spesifikasi satu assertion.
     *
     * @param type     jenis assertion
     * @param target   subjek yang diperiksa (mis. path JSON, nama header, id node)
     * @param expected nilai harapan
     * @param operator operator pembanding
     * @param sql      query untuk assertion DB
     */
    public record AssertionSpec(String type, String target, Object expected, String operator, String sql) {
    }

    /** Hasil satu assertion. */
    public record AssertionResult(
            String type,
            String target,
            String operator,
            Object expected,
            Object actual,
            boolean passed,
            String message
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type);
            map.put("target", target);
            map.put("operator", operator);
            map.put("expected", expected);
            map.put("actual", actual);
            map.put("passed", passed);
            map.put("message", message);
            return map;
        }
    }

    /** Eksekutor query DB untuk assertion baca-saja (disediakan pemanggil). */
    @FunctionalInterface
    public interface DbQueryExecutor {
        List<Map<String, Object>> query(String sql, Map<String, Object> params);
    }

    /** Ringkasan hasil assertion. */
    public record Summary(int passed, int failed, List<AssertionResult> results) {
        public boolean allPassed() {
            return failed == 0;
        }
    }

    /** Jalankan seluruh assertion. */
    public Summary run(List<AssertionSpec> specs, ExecutionResult result,
                       DbQueryExecutor dbExecutor, Map<String, Object> dbParams) {
        List<AssertionResult> results = new ArrayList<>();
        if (specs == null || specs.isEmpty()) {
            return new Summary(0, 0, results);
        }
        for (AssertionSpec spec : specs) {
            if (spec == null || spec.type() == null) {
                continue;
            }
            results.add(evaluate(spec, result, dbExecutor, dbParams));
        }
        int passed = (int) results.stream().filter(AssertionResult::passed).count();
        return new Summary(passed, results.size() - passed, results);
    }

    /** Evaluasi satu assertion. */
    AssertionResult evaluate(AssertionSpec spec, ExecutionResult result,
                             DbQueryExecutor dbExecutor, Map<String, Object> dbParams) {
        String type = spec.type().trim().toLowerCase(Locale.ROOT);
        String operator = spec.operator() == null || spec.operator().isBlank()
                ? "equals"
                : spec.operator().trim().toLowerCase(Locale.ROOT);
        Object expected = spec.expected();

        try {
            switch (type) {
                case "response.status", "status" -> {
                    Object actual = result.status();
                    return compare(type, "status", operator, expected, actual);
                }
                case "response.header", "header" -> {
                    Object actual = headerValue(result, spec.target());
                    return compare(type, spec.target(), operator, expected, actual);
                }
                case "response.jsonpath", "jsonpath" -> {
                    Object actual = Json.at(result.body(), spec.target());
                    return compare(type, spec.target(), operator, expected, actual);
                }
                case "response.body", "body" -> {
                    Object actual = result.body();
                    return compare(type, "body", operator, expected, actual);
                }
                case "response.schema", "schema" -> {
                    return checkSchema(type, spec.target(), result.body());
                }
                case "db.value", "database.value" -> {
                    Object actual = dbValue(spec, dbExecutor, dbParams, true);
                    return compare(type, spec.target(), operator, expected, actual);
                }
                case "db.rowcount", "database.rowcount" -> {
                    Object actual = dbValue(spec, dbExecutor, dbParams, false);
                    return compare(type, spec.sql(), operator, expected, actual);
                }
                case "trace.nodeexecuted", "trace.node_executed" -> {
                    boolean executed = result.trace().stream()
                            .anyMatch(step -> step.nodeId().equals(spec.target())
                                    && step.status() == TraceStep.Status.OK);
                    if (operator.equals("notexists") || operator.equals("notfinished")) {
                        return new AssertionResult(type, spec.target(), operator, expected, !executed,
                                !executed, !executed ? "Node dieksekusi (tidak diharapkan)" : "OK");
                    }
                    return new AssertionResult(type, spec.target(), "executed", true, executed, executed,
                            executed ? "Node dieksekusi" : "Node tidak dieksekusi");
                }
                case "trace.nodeskipped", "trace.node_skipped" -> {
                    boolean executed = result.trace().stream()
                            .anyMatch(step -> step.nodeId().equals(spec.target()));
                    boolean skipped = !executed;
                    return new AssertionResult(type, spec.target(), "skipped", false, skipped, skipped,
                            skipped ? "Node dilewati" : "Node justru dieksekusi");
                }
                case "trace.durationunder", "trace.duration_under" -> {
                    long actual = result.trace().stream()
                            .filter(step -> spec.target() == null || step.nodeId().equals(spec.target()))
                            .mapToLong(TraceStep::durationMs).sum();
                    return compare(type, spec.target(), "lte", expected, actual);
                }
                case "trace.count" -> {
                    long actual = result.trace().stream()
                            .filter(step -> spec.target() == null || step.nodeType().equals(spec.target()))
                            .count();
                    return compare(type, spec.target(), operator, expected, actual);
                }
                default -> {
                    return new AssertionResult(type, spec.target(), operator, expected, null, false,
                            "Jenis assertion tidak dikenal: " + spec.type());
                }
            }
        } catch (RuntimeException e) {
            return new AssertionResult(type, spec.target(), operator, expected, null, false,
                    "Assertion gagal dievaluasi: " + e.getMessage());
        }
    }

    private Object headerValue(ExecutionResult result, String headerName) {
        if (headerName == null) {
            return null;
        }
        return result.headers().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(headerName))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    /** Ambil nilai dari DB: true = nilai kolom pertama, false = jumlah baris. */
    private Object dbValue(AssertionSpec spec, DbQueryExecutor executor,
                           Map<String, Object> params, boolean firstValue) {
        if (executor == null) {
            throw new IllegalStateException("Eksekutor DB tidak tersedia untuk assertion DB");
        }
        String sql = spec.sql();
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("Assertion DB memerlukan 'sql'");
        }
        List<Map<String, Object>> rows = executor.query(sql, params == null ? Map.of() : params);
        if (!firstValue) {
            return (long) rows.size();
        }
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        if (spec.target() != null && !spec.target().isBlank()) {
            return row.get(spec.target());
        }
        return row.values().stream().findFirst().orElse(null);
    }

    /** Pastikan field wajib ada pada body (target = "a.b,c"). */
    private AssertionResult checkSchema(String type, String target, Object body) {
        List<String> missing = new ArrayList<>();
        if (target != null && !target.isBlank()) {
            for (String path : target.split(",")) {
                String trimmed = path.trim();
                if (!trimmed.isEmpty() && Json.at(body, trimmed) == null) {
                    missing.add(trimmed);
                }
            }
        }
        boolean passed = missing.isEmpty();
        return new AssertionResult(type, target, "schema", "semua field ada",
                missing.isEmpty() ? "semua field ada" : missing, passed,
                passed ? "Schema sesuai" : "Field tidak ditemukan: " + missing);
    }

    /** Bandingkan nilai sesuai operator. */
    private AssertionResult compare(String type, String target, String operator,
                                    Object expected, Object actual) {
        boolean passed = switch (operator) {
            case "equals", "eq" -> looseEquals(actual, expected);
            case "notequals", "ne" -> !looseEquals(actual, expected);
            case "contains" -> contains(actual, expected);
            case "startswith" -> stringOf(actual).startsWith(stringOf(expected));
            case "endswith" -> stringOf(actual).endsWith(stringOf(expected));
            case "matches" -> stringOf(actual).matches(stringOf(expected));
            case "gt" -> compareNumbers(actual, expected) > 0;
            case "gte" -> compareNumbers(actual, expected) >= 0;
            case "lt" -> compareNumbers(actual, expected) < 0;
            case "lte" -> compareNumbers(actual, expected) <= 0;
            case "exists" -> actual != null;
            case "notexists" -> actual == null;
            default -> false;
        };
        String message = passed
                ? "OK"
                : "Diharapkan " + operator + " " + describe(expected) + " tetapi nilai sebenarnya "
                        + describe(actual);
        return new AssertionResult(type, target, operator, expected, actual, passed, message);
    }

    /** Perbandingan toleran antara angka, string numerik, dan boolean. */
    static boolean looseEquals(Object actual, Object expected) {
        if (actual == null && expected == null) {
            return true;
        }
        if (actual == null || expected == null) {
            return false;
        }
        Double actualNumber = asNumber(actual);
        Double expectedNumber = asNumber(expected);
        if (actualNumber != null && expectedNumber != null) {
            return actualNumber.doubleValue() == expectedNumber.doubleValue();
        }
        if (actual instanceof Boolean || expected instanceof Boolean) {
            return Boolean.parseBoolean(String.valueOf(actual))
                    == Boolean.parseBoolean(String.valueOf(expected));
        }
        return stringOf(actual).equals(stringOf(expected));
    }

    private static boolean contains(Object actual, Object expected) {
        if (actual == null) {
            return false;
        }
        if (actual instanceof List<?> list) {
            return list.stream().anyMatch(item -> looseEquals(item, expected));
        }
        if (actual instanceof Map<?, ?> map) {
            return map.containsKey(stringOf(expected));
        }
        return stringOf(actual).contains(stringOf(expected));
    }

    private static int compareNumbers(Object actual, Object expected) {
        Double left = asNumber(actual);
        Double right = asNumber(expected);
        if (left == null || right == null) {
            return stringOf(actual).compareTo(stringOf(expected));
        }
        return Double.compare(left, right);
    }

    private static Double asNumber(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String describe(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    /** Jenis assertion yang tersedia (untuk UI). */
    public static List<Map<String, Object>> supportedTypes() {
        List<Map<String, Object>> types = new ArrayList<>();
        for (String type : List.of("response.status", "response.header", "response.jsonpath",
                "response.body", "response.schema", "db.value", "db.rowcount",
                "trace.nodeExecuted", "trace.nodeSkipped", "trace.durationUnder", "trace.count")) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", type);
            types.add(item);
        }
        return types;
    }

    /** Bagian dari hasil yang sering dipakai assertion (untuk debugging). */
    static Map<String, Object> describeResult(ExecutionResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", result.status());
        map.put("headers", result.headers());
        map.put("body", result.body());
        map.put("traceSteps", result.trace().size());
        return map;
    }

    /** Bandingkan jumlah baris; pembanding tanpa type-safety berlebih. */
    static boolean rowCountEquals(Object actual, Object expected) {
        return Objects.equals(asNumber(actual), asNumber(expected));
    }
}
