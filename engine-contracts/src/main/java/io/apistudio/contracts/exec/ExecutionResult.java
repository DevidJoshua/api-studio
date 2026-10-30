package io.apistudio.contracts.exec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hasil eksekusi flow yang dikembalikan Flow Engine ke Control Plane/gateway
 * (BRD Bagian 5.3 langkah 5).
 *
 * @param status        status HTTP response
 * @param statusText    teks status (mis. "OK")
 * @param headers       header response
 * @param body          body response
 * @param traceId       id korelasi run
 * @param trace         jejak per node
 * @param durationMs    durasi total
 * @param logs          log tingkat flow
 * @param touchedTables tabel sandbox yang disentuh (FR-SIM-07)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecutionResult(
        int status,
        String statusText,
        Map<String, String> headers,
        Object body,
        String traceId,
        List<TraceStep> trace,
        long durationMs,
        List<String> logs,
        List<String> touchedTables
) {

    public ExecutionResult {
        headers = headers == null ? Map.of() : new LinkedHashMap<>(headers);
        trace = trace == null ? List.of() : List.copyOf(trace);
        logs = logs == null ? List.of() : List.copyOf(logs);
        touchedTables = touchedTables == null ? List.of() : List.copyOf(touchedTables);
    }

    /** True bila flow berakhir dengan kegagalan server. */
    public boolean failed() {
        return status >= 500;
    }

    public static ExecutionResult error(int status, String message, String traceId, List<TraceStep> trace,
                                        long durationMs, List<String> logs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", status >= 500 ? "FLOW_EXECUTION_FAILED" : "REQUEST_REJECTED");
        body.put("message", message);
        return new ExecutionResult(status, statusTextOf(status), Map.of("Content-Type", "application/json"),
                body, traceId, trace, durationMs, logs, List.of());
    }

    private static String statusTextOf(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 503 -> "Service Unavailable";
            default -> "Status " + status;
        };
    }
}
