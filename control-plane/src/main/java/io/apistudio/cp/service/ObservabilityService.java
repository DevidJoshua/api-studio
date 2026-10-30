package io.apistudio.cp.service;

import io.apistudio.cp.domain.ApiMetric;
import io.apistudio.cp.domain.RequestLog;
import io.apistudio.cp.repo.ApiMetricRepository;
import io.apistudio.cp.repo.RequestLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Observability: log request dan metrik agregat (BRD FR-SEC-05/06).
 *
 * <p>Metrik disimpan sebagai bucket per menit per API per environment agar
 * jumlah baris tetap kecil. Di produksi, metrik yang sama diekspor ke
 * Prometheus/OTel; di sini model datanya dibuat sederhana tetapi lengkap
 * (throughput, error rate, latensi rata-rata dan maksimum).
 */
@Service
public class ObservabilityService {

    private final RequestLogRepository logs;
    private final ApiMetricRepository metrics;
    private final JsonStore json;

    public ObservabilityService(RequestLogRepository logs, ApiMetricRepository metrics, JsonStore json) {
        this.logs = logs;
        this.metrics = metrics;
        this.json = json;
    }

    /** Catat satu request runtime ke log dan metrik. */
    @Transactional
    public void recordRequest(String apiId,
                              String versionId,
                              String environment,
                              String method,
                              String path,
                              int status,
                              long latencyMs,
                              String traceId,
                              Object trace,
                              String errorMessage) {
        RequestLog log = new RequestLog();
        log.apiId = apiId;
        log.versionId = versionId;
        log.environment = environment;
        log.method = method;
        log.path = path;
        log.status = status;
        log.latencyMs = latencyMs;
        log.traceId = traceId;
        log.traceJson = trace == null ? "[]" : json.write(trace);
        log.errorMessage = errorMessage;
        logs.save(log);

        Instant bucket = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        ApiMetric metric = metrics.findByApiIdAndEnvironmentAndBucket(apiId, environment, bucket)
                .orElseGet(() -> new ApiMetric(apiId, environment, bucket));
        metric.requestCount++;
        // 5xx dan 0 (kegagalan eksekusi) dihitung sebagai error
        if (status >= 500 || status == 0) {
            metric.errorCount++;
        }
        metric.totalLatencyMs += latencyMs;
        metric.maxLatencyMs = Math.max(metric.maxLatencyMs, latencyMs);
        metrics.save(metric);
    }

    public List<RequestLog> recentLogs() {
        return logs.findTop100ByOrderByCreatedAtDesc();
    }

    public List<RequestLog> logsForApi(String apiId) {
        return logs.findTop50ByApiIdOrderByCreatedAtDesc(apiId);
    }

    public RequestLog byTraceId(String traceId) {
        return logs.findFirstByTraceIdOrderByCreatedAtDesc(traceId).orElse(null);
    }

    /** Seri waktu metrik untuk grafik dan ringkasan. */
    public Map<String, Object> series(String apiId, String environment, int minutes) {
        int window = minutes <= 0 ? 60 : minutes;
        Instant after = Instant.now().minus(window, ChronoUnit.MINUTES);
        List<ApiMetric> data = apiId == null || apiId.isBlank()
                ? metrics.findByBucketAfterOrderByBucketAsc(after)
                : metrics.findByApiIdAndBucketAfterOrderByBucketAsc(apiId, after);

        List<Map<String, Object>> points = new ArrayList<>();
        long totalRequests = 0;
        long totalErrors = 0;
        long totalLatency = 0;
        long maxLatency = 0;
        for (ApiMetric metric : data) {
            if (environment != null && !environment.isBlank()
                    && !environment.equals(metric.environment)) {
                continue;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("bucket", metric.bucket);
            point.put("apiId", metric.apiId);
            point.put("environment", metric.environment);
            point.put("requests", metric.requestCount);
            point.put("errors", metric.errorCount);
            point.put("avgLatencyMs", Math.round(metric.avgLatency()));
            point.put("maxLatencyMs", metric.maxLatencyMs);
            point.put("errorRate", Math.round(metric.errorRate() * 10000.0) / 100.0);
            points.add(point);

            totalRequests += metric.requestCount;
            totalErrors += metric.errorCount;
            totalLatency += metric.totalLatencyMs;
            maxLatency = Math.max(maxLatency, metric.maxLatencyMs);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRequests", totalRequests);
        summary.put("totalErrors", totalErrors);
        summary.put("avgLatencyMs", totalRequests == 0 ? 0 : Math.round((double) totalLatency / totalRequests));
        summary.put("maxLatencyMs", maxLatency);
        summary.put("errorRate", totalRequests == 0 ? 0
                : Math.round((double) totalErrors / totalRequests * 10000.0) / 100.0);
        summary.put("windowMinutes", window);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("points", points);
        result.put("summary", summary);
        return result;
    }

    /**
     * Alert berbasis ambang (FR-SEC-06).
     *
     * <p>Dihitung saat dibaca dari bucket 30 menit terakhir; pada MVP tidak ada
     * evaluator latar belakang.
     */
    public List<Map<String, Object>> alerts(String apiId, double errorRateThreshold, long latencyThreshold) {
        Instant after = Instant.now().minus(30, ChronoUnit.MINUTES);
        List<ApiMetric> data = apiId == null || apiId.isBlank()
                ? metrics.findByBucketAfterOrderByBucketAsc(after)
                : metrics.findByApiIdAndBucketAfterOrderByBucketAsc(apiId, after);

        Map<String, ApiMetric> latestByApi = new LinkedHashMap<>();
        for (ApiMetric metric : data) {
            latestByApi.put(metric.apiId + "|" + metric.environment, metric);
        }

        List<Map<String, Object>> alerts = new ArrayList<>();
        for (ApiMetric metric : latestByApi.values()) {
            if (metric.errorRate() * 100 > errorRateThreshold) {
                Map<String, Object> alert = new LinkedHashMap<>();
                alert.put("type", "ERROR_RATE");
                alert.put("severity", "HIGH");
                alert.put("apiId", metric.apiId);
                alert.put("environment", metric.environment);
                alert.put("message", "Error rate " + Math.round(metric.errorRate() * 10000.0) / 100.0
                        + "% melebihi ambang " + errorRateThreshold + "%");
                alerts.add(alert);
            }
            if (metric.maxLatencyMs > latencyThreshold) {
                Map<String, Object> alert = new LinkedHashMap<>();
                alert.put("type", "LATENCY");
                alert.put("severity", "MEDIUM");
                alert.put("apiId", metric.apiId);
                alert.put("environment", metric.environment);
                alert.put("message", "Latensi maksimum " + metric.maxLatencyMs
                        + " ms melebihi ambang " + latencyThreshold + " ms");
                alerts.add(alert);
            }
        }
        return alerts;
    }

    /** View log untuk API. */
    public Map<String, Object> logView(RequestLog log, boolean includeTrace) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", log.id);
        view.put("apiId", log.apiId);
        view.put("versionId", log.versionId);
        view.put("environment", log.environment);
        view.put("method", log.method);
        view.put("path", log.path);
        view.put("status", log.status);
        view.put("latencyMs", log.latencyMs);
        view.put("traceId", log.traceId);
        view.put("errorMessage", log.errorMessage);
        view.put("createdAt", log.createdAt);
        if (includeTrace) {
            view.put("trace", json.readAny(log.traceJson));
        }
        return view;
    }
}
