package io.apistudio.engine.expr;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Interpreter ekspresi ringan (BRD FR-FLOW-07), mirip subset JSONata.
 *
 * <p>Fitur yang didukung:
 * <ul>
 *   <li>Akses path: {@code request.body.amount.value}, {@code vars.x}, {@code response.status}.</li>
 *   <li>Literal: angka, string, {@code true}, {@code false}, {@code null}.</li>
 *   <li>Operator: {@code + - * / %}, perbandingan, {@code && || !}, ternary {@code a ? b : c}.</li>
 *   <li>Fungsi bawaan: {@code upper, lower, trim, length, number, string, now, timestamp,
 *       sha256, hmacSha256, base64, json, get}.</li>
 * </ul>
 *
 * <p>Instans dibuat per eksekusi flow sehingga dapat membawa resolver variabel,
 * {@code Clock} deterministik (untuk pengujian), dan fungsi kriptografi.
 */
public final class ExpressionEngine {

    private final Function<String, Object> variableResolver;
    private final Supplier<String> timestampSupplier;
    private final String hmacSecret;

    /**
     * @param variableResolver  resolver nama variabel tingkat atas (mis. {@code request}, {@code vars})
     * @param timestampSupplier sumber waktu (deterministik pada pengujian)
     * @param hmacSecret        secret untuk {@code hmacSha256}; boleh null
     */
    public ExpressionEngine(Function<String, Object> variableResolver,
                            Supplier<String> timestampSupplier,
                            String hmacSecret) {
        this.variableResolver = variableResolver;
        this.timestampSupplier = timestampSupplier == null ? () -> "" : timestampSupplier;
        this.hmacSecret = hmacSecret;
    }

    /**
     * Evaluasi satu ekspresi.
     *
     * @throws ExpressionException bila sintaks tidak valid
     */
    public Object eval(String expression) {
        if (expression == null) {
            return null;
        }
        String source = expression.trim();
        if (source.isEmpty()) {
            return null;
        }
        return new Parser(source, this).parse();
    }

    /**
     * Selesaikan template string {@code ${...}} dan path bertanda {@code =}.
     *
     * <p>Aturan:
     * <ul>
     *   <li>String diawali {@code =} &rarr; seluruh isi dievaluasi sebagai ekspresi
     *       dan hasilnya dipertahankan apa adanya (bukan dipaksa menjadi String).</li>
     *   <li>String memuat {@code ${...}} &rarr; setiap bagian diganti hasil ekspresi.</li>
     *   <li>Selain itu dikembalikan apa adanya.</li>
     * </ul>
     */
    public Object resolve(Object value) {
        if (!(value instanceof String text)) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((k, v) -> result.put(String.valueOf(k), resolve(v)));
                return result;
            }
            if (value instanceof List<?> list) {
                List<Object> result = new ArrayList<>();
                for (Object item : list) {
                    result.add(resolve(item));
                }
                return result;
            }
            return value;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("=") && !trimmed.startsWith("==")) {
            return eval(trimmed.substring(1));
        }
        if (trimmed.contains("${")) {
            return interpolate(text);
        }
        return text;
    }

    /** Ganti setiap {@code ${expr}} dengan hasil evaluasinya. */
    public String interpolate(String template) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < template.length()) {
            int start = template.indexOf("${", index);
            if (start < 0) {
                out.append(template, index, template.length());
                break;
            }
            out.append(template, index, start);
            int end = findClosing(template, start + 2);
            if (end < 0) {
                throw new ExpressionException("Template tidak tertutup: " + template);
            }
            String inner = template.substring(start + 2, end);
            Object value = eval(inner);
            out.append(value == null ? "" : stringify(value));
            index = end + 1;
        }
        return out.toString();
    }

    private static int findClosing(String text, int from) {
        int depth = 1;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Representasi String untuk penyisipan template. */
    public static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double d) {
            if (d == Math.rint(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
                return String.valueOf(d.longValue());
            }
            return String.valueOf(d);
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return Json.encode(value);
        }
        return String.valueOf(value);
    }

    // ------------------------------------------------------------------ parser

    /** Parser rekursif turun dengan presedensi operator standar. */
    private static final class Parser {

        private final String src;
        private final ExpressionEngine engine;
        private int pos;

        Parser(String src, ExpressionEngine engine) {
            this.src = src;
            this.engine = engine;
            this.pos = 0;
        }

        Object parse() {
            Object value = parseTernary();
            skipWhitespace();
            if (pos < src.length()) {
                throw new ExpressionException("Karakter tak terduga pada posisi " + pos + ": '" + src.charAt(pos) + "'");
            }
            return value;
        }

        private Object parseTernary() {
            Object condition = parseOr();
            skipWhitespace();
            if (peek() == '?') {
                pos++;
                Object whenTrue = parseTernary();
                skipWhitespace();
                expect(':');
                Object whenFalse = parseTernary();
                return truthy(condition) ? whenTrue : whenFalse;
            }
            return condition;
        }

        private Object parseOr() {
            Object left = parseAnd();
            while (true) {
                skipWhitespace();
                if (match("||")) {
                    Object right = parseAnd();
                    left = truthy(left) || truthy(right);
                } else {
                    return left;
                }
            }
        }

        private Object parseAnd() {
            Object left = parseEquality();
            while (true) {
                skipWhitespace();
                if (match("&&")) {
                    Object right = parseEquality();
                    left = truthy(left) && truthy(right);
                } else {
                    return left;
                }
            }
        }

        private Object parseEquality() {
            Object left = parseComparison();
            while (true) {
                skipWhitespace();
                if (match("==")) {
                    left = looseEquals(left, parseComparison());
                } else if (match("!=")) {
                    left = !looseEquals(left, parseComparison());
                } else {
                    return left;
                }
            }
        }

        private Object parseComparison() {
            Object left = parseAdditive();
            while (true) {
                skipWhitespace();
                if (match(">=")) {
                    left = compare(left, parseAdditive()) >= 0;
                } else if (match("<=")) {
                    left = compare(left, parseAdditive()) <= 0;
                } else if (match(">")) {
                    left = compare(left, parseAdditive()) > 0;
                } else if (match("<")) {
                    left = compare(left, parseAdditive()) < 0;
                } else {
                    return left;
                }
            }
        }

        private Object parseAdditive() {
            Object left = parseMultiplicative();
            while (true) {
                skipWhitespace();
                if (match("+")) {
                    left = add(left, parseMultiplicative());
                } else if (match("-")) {
                    left = subtract(left, parseMultiplicative());
                } else {
                    return left;
                }
            }
        }

        private Object parseMultiplicative() {
            Object left = parseUnary();
            while (true) {
                skipWhitespace();
                if (match("*")) {
                    left = multiply(left, parseUnary());
                } else if (match("/")) {
                    left = divide(left, parseUnary());
                } else if (match("%")) {
                    left = modulo(left, parseUnary());
                } else {
                    return left;
                }
            }
        }

        private Object parseUnary() {
            skipWhitespace();
            if (match("!")) {
                return !truthy(parseUnary());
            }
            if (match("-")) {
                return negate(parseUnary());
            }
            return parsePostfix();
        }

        private Object parsePostfix() {
            Object value = parsePrimary();
            while (true) {
                skipWhitespace();
                if (peek() == '.') {
                    pos++;
                    String name = readIdentifier();
                    if (name.isEmpty()) {
                        throw new ExpressionException("Nama properti diharapkan setelah '.' pada posisi " + pos);
                    }
                    value = member(value, name);
                } else if (peek() == '[') {
                    pos++;
                    Object index = parseTernary();
                    skipWhitespace();
                    expect(']');
                    value = index(value, index);
                } else {
                    return value;
                }
            }
        }

        private Object parsePrimary() {
            skipWhitespace();
            if (pos >= src.length()) {
                throw new ExpressionException("Ekspresi tidak lengkap");
            }
            char c = peek();

            if (c == '(') {
                pos++;
                Object value = parseTernary();
                skipWhitespace();
                expect(')');
                return value;
            }
            if (c == '\'' || c == '"') {
                return readString(c);
            }
            if (Character.isDigit(c)) {
                return readNumber();
            }
            String name = readIdentifier();
            if (name.isEmpty()) {
                throw new ExpressionException("Identifier diharapkan pada posisi " + pos);
            }
            skipWhitespace();
            if (peek() == '(') {
                pos++;
                List<Object> args = readArgs();
                return callFunction(name, args);
            }
            return switch (name) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                case "null" -> null;
                default -> engine.variableResolver.apply(name);
            };
        }

        private List<Object> readArgs() {
            List<Object> args = new ArrayList<>();
            skipWhitespace();
            if (peek() == ')') {
                pos++;
                return args;
            }
            while (true) {
                args.add(parseTernary());
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                    continue;
                }
                expect(')');
                return args;
            }
        }

        private Object callFunction(String name, List<Object> args) {
            String fn = name.toLowerCase(Locale.ROOT);
            return switch (fn) {
                case "upper", "uppercase" -> stringify(arg(args, 0)).toUpperCase(Locale.ROOT);
                case "lower", "lowercase" -> stringify(arg(args, 0)).toLowerCase(Locale.ROOT);
                case "trim" -> stringify(arg(args, 0)).trim();
                case "length" -> lengthOf(arg(args, 0));
                case "number" -> toNumber(arg(args, 0));
                case "string" -> stringify(arg(args, 0));
                case "now", "timestamp" -> engine.timestampSupplier.get();
                case "sha256" -> Crypto.sha256Hex(stringify(arg(args, 0)));
                case "hmacsha256", "hmac" -> Crypto.hmacSha256Hex(
                        engine.hmacSecret == null ? "" : engine.hmacSecret, stringify(arg(args, 0)));
                case "base64" -> Base64.getEncoder()
                        .encodeToString(stringify(arg(args, 0)).getBytes(StandardCharsets.UTF_8));
                case "json" -> Json.encode(arg(args, 0));
                case "get" -> Json.at(arg(args, 0), stringify(arg(args, 1)));
                case "isempty" -> isEmpty(arg(args, 0));
                case "coalesce" -> {
                    for (Object value : args) {
                        if (value != null) {
                            yield value;
                        }
                    }
                    yield null;
                }
                case "uuid" -> java.util.UUID.randomUUID().toString();
                default -> throw new ExpressionException("Fungsi tidak dikenal: " + name);
            };
        }

        private static Object arg(List<Object> args, int index) {
            return index < args.size() ? args.get(index) : null;
        }

        private static Object lengthOf(Object value) {
            if (value == null) {
                return 0;
            }
            if (value instanceof String s) {
                return s.length();
            }
            if (value instanceof List<?> l) {
                return l.size();
            }
            if (value instanceof Map<?, ?> m) {
                return m.size();
            }
            return stringify(value).length();
        }

        private static boolean isEmpty(Object value) {
            if (value == null) {
                return true;
            }
            if (value instanceof String s) {
                return s.isEmpty();
            }
            if (value instanceof List<?> l) {
                return l.isEmpty();
            }
            if (value instanceof Map<?, ?> m) {
                return m.isEmpty();
            }
            return false;
        }

        private Object member(Object target, String name) {
            if (target == null) {
                return null;
            }
            if (target instanceof Map<?, ?> map) {
                return map.get(name);
            }
            if (target instanceof List<?> list && "length".equals(name)) {
                return list.size();
            }
            if (target instanceof String s && "length".equals(name)) {
                return s.length();
            }
            return null;
        }

        private Object index(Object target, Object key) {
            if (target == null) {
                return null;
            }
            if (target instanceof List<?> list && key instanceof Number n) {
                int i = n.intValue();
                return i >= 0 && i < list.size() ? list.get(i) : null;
            }
            if (target instanceof Map<?, ?> map) {
                return map.get(stringify(key));
            }
            if (target instanceof String s && key instanceof Number n) {
                int i = n.intValue();
                return i >= 0 && i < s.length() ? String.valueOf(s.charAt(i)) : null;
            }
            return null;
        }

        private String readString(char quote) {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == quote) {
                    return sb.toString();
                }
                if (c == '\\' && pos < src.length()) {
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case '\\' -> sb.append('\\');
                        case '\'' -> sb.append('\'');
                        case '"' -> sb.append('"');
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new ExpressionException("String tidak tertutup");
        }

        private Object readNumber() {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    // '+'/'-' hanya valid sebagai bagian eksponen
                    if ((c == '+' || c == '-') && pos > start) {
                        char prev = src.charAt(pos - 1);
                        if (prev != 'e' && prev != 'E') {
                            break;
                        }
                    }
                    pos++;
                } else {
                    break;
                }
            }
            String text = src.substring(start, pos);
            try {
                if (text.contains(".") || text.contains("e") || text.contains("E")) {
                    return Double.parseDouble(text);
                }
                long asLong = Long.parseLong(text);
                return asLong >= Integer.MIN_VALUE && asLong <= Integer.MAX_VALUE ? (int) asLong : asLong;
            } catch (NumberFormatException e) {
                throw new ExpressionException("Angka tidak valid: " + text);
            }
        }

        private String readIdentifier() {
            skipWhitespace();
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                    pos++;
                } else {
                    break;
                }
            }
            return src.substring(start, pos);
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        private boolean match(String token) {
            skipWhitespace();
            if (src.startsWith(token, pos)) {
                pos += token.length();
                return true;
            }
            return false;
        }

        private void expect(char c) {
            skipWhitespace();
            if (peek() != c) {
                throw new ExpressionException("Diharapkan '" + c + "' pada posisi " + pos);
            }
            pos++;
        }

        // ------------------------------------------------------ operasi nilai

        static boolean truthy(Object value) {
            if (value == null) {
                return false;
            }
            if (value instanceof Boolean b) {
                return b;
            }
            if (value instanceof Number n) {
                return n.doubleValue() != 0;
            }
            if (value instanceof String s) {
                return !s.isEmpty();
            }
            if (value instanceof List<?> l) {
                return !l.isEmpty();
            }
            if (value instanceof Map<?, ?> m) {
                return !m.isEmpty();
            }
            return true;
        }

        static boolean looseEquals(Object a, Object b) {
            if (a == null || b == null) {
                return a == null && b == null;
            }
            if (a instanceof Number && b instanceof Number) {
                return ((Number) a).doubleValue() == ((Number) b).doubleValue();
            }
            if (a instanceof Number || b instanceof Number) {
                Double left = asDouble(a);
                Double right = asDouble(b);
                if (left != null && right != null) {
                    return left.doubleValue() == right.doubleValue();
                }
            }
            if (a instanceof Boolean || b instanceof Boolean) {
                return truthy(a) == truthy(b);
            }
            return stringify(a).equals(stringify(b));
        }

        static int compare(Object a, Object b) {
            Double left = asDouble(a);
            Double right = asDouble(b);
            if (left != null && right != null) {
                return Double.compare(left, right);
            }
            return stringify(a).compareTo(stringify(b));
        }

        private static Double asDouble(Object value) {
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

        /** Substitusi variabel di dalam string, lalu konversi ke angka bila memungkinkan. */
        private static Object numeric(Object value) {
            if (value instanceof Number) {
                return value;
            }
            if (value instanceof String s) {
                return Json.coerceNumber(s);
            }
            return value;
        }

        static Object add(Object a, Object b) {
            if (a instanceof Number || b instanceof Number) {
                Double left = asDouble(a);
                Double right = asDouble(b);
                if (left != null && right != null) {
                    return normalize(left + right);
                }
            }
            if (a instanceof String || b instanceof String) {
                return stringify(a) + stringify(b);
            }
            Double left = asDouble(a);
            Double right = asDouble(b);
            if (left != null && right != null) {
                return normalize(left + right);
            }
            return stringify(a) + stringify(b);
        }

        static Object subtract(Object a, Object b) {
            Double left = asDouble(numeric(a));
            Double right = asDouble(numeric(b));
            if (left == null || right == null) {
                throw new ExpressionException("Operator '-' membutuhkan angka");
            }
            return normalize(left - right);
        }

        static Object multiply(Object a, Object b) {
            Double left = asDouble(numeric(a));
            Double right = asDouble(numeric(b));
            if (left == null || right == null) {
                throw new ExpressionException("Operator '*' membutuhkan angka");
            }
            return normalize(left * right);
        }

        static Object divide(Object a, Object b) {
            Double left = asDouble(numeric(a));
            Double right = asDouble(numeric(b));
            if (left == null || right == null) {
                throw new ExpressionException("Operator '/' membutuhkan angka");
            }
            if (right == 0) {
                throw new ExpressionException("Pembagian dengan nol");
            }
            return normalize(left / right);
        }

        static Object modulo(Object a, Object b) {
            Double left = asDouble(numeric(a));
            Double right = asDouble(numeric(b));
            if (left == null || right == null) {
                throw new ExpressionException("Operator '%' membutuhkan angka");
            }
            if (right == 0) {
                throw new ExpressionException("Modulo dengan nol");
            }
            return normalize(left % right);
        }

        static Object negate(Object value) {
            Double number = asDouble(numeric(value));
            if (number == null) {
                throw new ExpressionException("Operator '-' membutuhkan angka");
            }
            return normalize(-number);
        }

        /**
         * Normalisasi hasil aritmetika: bulat tetap disajikan sebagai bilangan
         * bulat agar pembandingan dan serialisasi JSON stabil.
         */
        private static Object normalize(double value) {
            if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
                long asLong = (long) value;
                if (asLong >= Integer.MIN_VALUE && asLong <= Integer.MAX_VALUE) {
                    return (int) asLong;
                }
                return asLong;
            }
            return value;
        }

        static Object toNumber(Object value) {
            if (value instanceof Number) {
                return value;
            }
            return Json.coerceNumber(stringify(value));
        }
    }
}
