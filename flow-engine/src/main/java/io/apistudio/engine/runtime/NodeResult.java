package io.apistudio.engine.runtime;

import java.util.Map;

/**
 * Hasil eksekusi satu node: port keluar dan output.
 *
 * @param port   port tujuan berikutnya; {@code null} berarti flow berhenti di node ini
 * @param output nilai output node (boleh null)
 */
public record NodeResult(String port, Object output) {

    /** Node melanjutkan ke port {@code out}. */
    public static NodeResult out(Object output) {
        return new NodeResult("out", output);
    }

    /** Node melanjutkan ke port tertentu (mis. {@code true}/{@code false}). */
    public static NodeResult to(String port, Object output) {
        return new NodeResult(port, output);
    }

    /** Node menghentikan alur (mis. RESPONSE). */
    public static NodeResult stop(Object output) {
        return new NodeResult(null, output);
    }

    /** Output berupa map agar mudah digabung. */
    public Map<String, Object> outputMap() {
        return output instanceof Map<?, ?> map
                ? io.apistudio.engine.expr.Json.mapOrEmpty(map)
                : Map.of();
    }
}
