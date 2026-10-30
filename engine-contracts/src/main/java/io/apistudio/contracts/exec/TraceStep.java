package io.apistudio.contracts.exec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hasil eksekusi satu langkah node, dipakai untuk trace viewer (FR-SIM-02/03).
 *
 * @param nodeId     id node
 * @param nodeType   jenis node
 * @param label      judul node
 * @param status     status eksekusi
 * @param port       port keluar yang dipilih (null bila node tidak melanjutkan flow)
 * @param input      ringkasan input yang diterima node
 * @param output     output node setelah masking
 * @param durationMs durasi eksekusi
 * @param error      pesan error bila gagal
 * @param logs       log yang ditulis node
 * @param attempt    percobaan ke berapa (1 = percobaan pertama)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TraceStep(
        String nodeId,
        String nodeType,
        String label,
        Status status,
        String port,
        Object input,
        Object output,
        long durationMs,
        String error,
        List<String> logs,
        int attempt
) {

    public TraceStep {
        logs = logs == null ? List.of() : List.copyOf(logs);
        attempt = attempt <= 0 ? 1 : attempt;
    }

    /** Status satu langkah eksekusi. */
    public enum Status {
        /** Berhasil. */
        OK,
        /** Gagal setelah semua retry. */
        ERROR,
        /** Gagal lalu dicoba ulang dan berhasil/gagal. */
        RETRIED,
        /** Dilewati karena node nonaktif. */
        DISABLED,
        /** Tidak dieksekusi karena alur tidak melewatinya. */
        SKIPPED
    }

    public static TraceStep ok(String nodeId, String nodeType, String label, String port,
                               Object input, Object output, long durationMs, List<String> logs) {
        return new TraceStep(nodeId, nodeType, label, Status.OK, port, input, output, durationMs,
                null, logs, 1);
    }

    public static TraceStep error(String nodeId, String nodeType, String label,
                                  Object input, String error, long durationMs, List<String> logs) {
        return new TraceStep(nodeId, nodeType, label, Status.ERROR, null, input, null, durationMs,
                error, logs, 1);
    }

    public boolean failed() {
        return status == Status.ERROR;
    }

    /** Bentuk map untuk respons JSON (dipakai jobject mapper di Control Plane). */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodeId", nodeId);
        map.put("nodeType", nodeType);
        map.put("label", label);
        map.put("status", status.name());
        map.put("port", port);
        map.put("input", input);
        map.put("output", output);
        map.put("durationMs", durationMs);
        map.put("error", error);
        map.put("logs", logs);
        map.put("attempt", attempt);
        return map;
    }
}
