package io.apistudio.cp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Deployment satu release ke satu environment (BRD FR-DEP-03..08).
 *
 * <p>Mendukung hot deploy (aktif berdampingan lalu routing dialihkan), drain,
 * canary bertahap, dan rollback dengan mengaktifkan kembali deployment
 * sebelumnya.
 */
@Entity
@Table(name = "deployment", indexes = {
        @Index(name = "idx_deployment_api_env", columnList = "apiId,environment"),
        @Index(name = "idx_deployment_status", columnList = "status")
})
public class Deployment {

    /** Status deployment. */
    public enum Status {
        /** Sedang dimuat ke instance, belum menerima traffic. */
        PENDING,
        /** Menerima traffic. */
        ACTIVE,
        /** Versi baru sudah aktif; deployment ini menunggu request selesai. */
        DRAINING,
        /** Tidak lagi aktif. */
        INACTIVE,
        /** Gagal dimuat/diaktifkan. */
        FAILED
    }

    /** Strategi aktivasi (FR-DEP-06). */
    public enum Strategy {
        /** Langsung dialihkan seluruhnya. */
        DIRECT,
        /** Bertahap dengan bobot persentase. */
        CANARY
    }

    @Id
    public String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    public String apiId;

    @Column(nullable = false)
    public String versionId;

    public String releaseId;

    @Column(nullable = false)
    public String environment;

    @Enumerated(EnumType.STRING)
    public Status status = Status.PENDING;

    @Enumerated(EnumType.STRING)
    public Strategy strategy = Strategy.DIRECT;

    /** Persentase traffic untuk canary; 100 untuk DIRECT. */
    public int canaryPercent = 100;

    public int versionNumber;

    /** Ringkasan kesiapan instance (FR-DEP-07). */
    @Column(length = 4000)
    public String readinessJson = "{}";

    @Column(length = 2000)
    public String message;

    public Instant createdAt = Instant.now();

    public String createdBy;

    public Instant activatedAt;

    public Instant drainedAt;

    public Deployment() {
    }

    public Deployment(String apiId, String versionId, String releaseId, String environment,
                      int versionNumber, String createdBy) {
        this.apiId = apiId;
        this.versionId = versionId;
        this.releaseId = releaseId;
        this.environment = environment;
        this.versionNumber = versionNumber;
        this.createdBy = createdBy;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
