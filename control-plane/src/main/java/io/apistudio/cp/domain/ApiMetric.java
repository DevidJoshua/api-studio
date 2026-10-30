package io.apistudio.cp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Metrik agregat per API per environment per menit (BRD FR-SEC-05).
 *
 * <p>Agregasi per menit menjaga jumlah baris tetap kecil sehingga dashboard
 * dapat membaca seri waktu tanpa sistem metrik eksternal. Di produksi, metrik
 * yang sama diekspor ke Prometheus/OTel.
 */
@Entity
@Table(name = "api_metric", indexes = {
        @Index(name = "idx_metric_lookup", columnList = "apiId,environment,bucket", unique = true),
        @Index(name = "idx_metric_bucket", columnList = "bucket")
})
public class ApiMetric {

    @Id
    public String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    public String apiId;

    @Column(nullable = false)
    public String environment = "dev";

    /** Awal bucket menit. */
    @Column(nullable = false)
    public Instant bucket;

    public long requestCount;

    public long errorCount;

    public long totalLatencyMs;

    public long maxLatencyMs;

    public ApiMetric() {
    }

    public ApiMetric(String apiId, String environment, Instant bucket) {
        this.apiId = apiId;
        this.environment = environment;
        this.bucket = bucket;
    }

    /** Rata-rata latensi bucket ini (ms). */
    public double avgLatency() {
        return requestCount == 0 ? 0 : (double) totalLatencyMs / requestCount;
    }

    /** Rasio error 0..1. */
    public double errorRate() {
        return requestCount == 0 ? 0 : (double) errorCount / requestCount;
    }
}
