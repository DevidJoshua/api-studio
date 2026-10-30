package io.apistudio.engine.expr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper navigasi dan serialisasi struktur JSON-like (Map/List/primitif).
 *
 * <p>Semua data flow berasal dari JSON, sehingga Map/List adalah bentuk
 * dominan. Helper ini memusatkan konversi, akses path, dan serialisasi agar
 * expression engine dan node executor tidak menduplikasi logika yang sama.
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private Json() {
    }

    /** Mapper bersama untuk engine. */
    public static ObjectMapper writer() {
        return MAPPER;
    }

    /** Serialisasi ke String JSON; gagal serialisasi &rarr; {@code String.valueOf(value)}. */
    public static String encode(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    /** Parse String JSON menjadi objek; mengembalikan {@code fallback} bila gagal. */
    public static Object decode(String json, Object fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return fallback;
        }
    }

    /** Cast aman ke Map&lt;String,Object&gt;. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return null;
    }

    /** Cast aman ke List&lt;Object&gt;. */
    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>((List<Object>) list);
        }
        return null;
    }

    /** Map kosong bila nilai bukan map. */
    public static Map<String, Object> mapOrEmpty(Object value) {
        Map<String, Object> map = asMap(value);
        return map == null ? new LinkedHashMap<>() : map;
    }

    /** True bila nilai adalah angka (Number apa pun). */
    public static boolean isNumber(Object value) {
        return value instanceof Number;
    }

    /**
     * Ambil nilai pada path bertitik.
     *
     * @param root akar struktur
     * @param path mis. {@code request.body.amount.value}
     * @return nilai, atau {@code null} bila path tidak ada
     */
    public static Object at(Object root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return root;
        }
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else if (current instanceof List<?> list) {
                int index = parseIndex(segment);
                if (index < 0 || index >= list.size()) {
                    return null;
                }
                current = list.get(index);
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** Set nilai pada path bertitik, membuat map antara bila perlu. */
    @SuppressWarnings("unchecked")
    public static void setAt(Map<String, Object> root, String path, Object value) {
        if (root == null || path == null || path.isBlank()) {
            return;
        }
        String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map)) {
                Map<String, Object> created = new LinkedHashMap<>();
                current.put(parts[i], created);
                current = created;
            } else {
                current = (Map<String, Object>) next;
            }
        }
        current.put(parts[parts.length - 1], value);
    }

    /** Konversi String ke angka bila memungkinkan, selain itu kembalikan apa adanya. */
    public static Object coerceNumber(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return value;
        }
        try {
            if (trimmed.contains(".") || trimmed.contains("e") || trimmed.contains("E")) {
                return Double.parseDouble(trimmed);
            }
            long asLong = Long.parseLong(trimmed);
            if (asLong >= Integer.MIN_VALUE && asLong <= Integer.MAX_VALUE) {
                return (int) asLong;
            }
            return asLong;
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static int parseIndex(String segment) {
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
