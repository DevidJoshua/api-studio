package io.apistudio.engine.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Masking otomatis data sensitif pada trace dan log (BRD FR-SEC-04, FR-SIM-03).
 *
 * <p>Strategi: menutupi nilai berdasarkan nama kunci yang lazim membawa
 * kredensial/data pribadi, bukan berdasarkan isi nilainya. Ini deterministik,
 * murah, dan tidak mengubah struktur data.
 */
public final class Masking {

    /** Pengganti nilai sensitif. */
    public static final String MASK = "***";

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "pwd", "secret", "token", "access_token", "refresh_token",
            "authorization", "auth", "signature", "x-signature", "api_key", "apikey",
            "x-api-key", "client_secret", "private_key", "privatekey", "credential",
            "cookie", "set-cookie", "session", "cardnumber", "card_number", "cvv", "pin",
            "accountnumber", "account_no", "npwp", "nik"
    );

    private static final int MAX_DEPTH = 12;

    private Masking() {
    }

    /** True bila nama kunci perlu dimask. */
    public static boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "_").replace(" ", "");
        if (SENSITIVE_KEYS.contains(normalized)) {
            return true;
        }
        for (String candidate : SENSITIVE_KEYS) {
            if (normalized.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Salin struktur dengan nilai sensitif dimask. */
    public static Object mask(Object value) {
        return mask(value, 0);
    }

    private static Object mask(Object value, int depth) {
        if (value == null || depth > MAX_DEPTH) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                result.put(key, isSensitive(key) ? MASK : mask(v, depth + 1));
            });
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(mask(item, depth + 1));
            }
            return result;
        }
        if (value instanceof String[] array) {
            List<Object> result = new ArrayList<>(array.length);
            for (String item : array) {
                result.add(item);
            }
            return result;
        }
        return value;
    }

    /** Mask nilai header HTTP untuk log. */
    public static Map<String, String> maskHeaders(Map<String, String> headers) {
        if (headers == null) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        headers.forEach((k, v) -> result.put(k, isSensitive(k) ? MASK : v));
        return result;
    }
}
