package io.apistudio.cp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Log satu request runtime (BRD FR-SEC-05).
 *
 * <p>Menyimpan status, latensi, traceId, dan trace per node sehingga
 * investigasi dapat dilakukan dari Studio tanpa sistem log eksternal.
 */
@Entity
@Table(name = "request_log", indexes = {
        @Index(name = "idx_reqlog_created", columnList = "createdAt"),
        @Index(name = "idx_reqlog_api", columnList = "apiId"),
        @Index(name = "idx_reqlog_trace", columnList = "traceId")
})
public class RequestLog {

    @Id
    public String id = UUID.randomUUID().toString();

    public String apiId;

    public String versionId;

    public String environment = "dev";

    public String method;

    public String path;

    public int status;

    public long latencyMs;

    public String traceId;

    @Column(length = 100000)
    public String traceJson = "[]";

    @Column(length = 4000)
    public String errorMessage;

    public Instant createdAt = Instant.now();

    public RequestLog() {
    }
}
