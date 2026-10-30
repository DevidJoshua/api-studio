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
 * Permintaan persetujuan publish (BRD FR-DEP-02).
 *
 * <p>Menyimpan diff flow dan kode Java sehingga Reviewer dapat menilai
 * perubahan tanpa harus membandingkan manual (BRD UC-05 langkah 2).
 */
@Entity
@Table(name = "approval", indexes = {
        @Index(name = "idx_approval_status", columnList = "status"),
        @Index(name = "idx_approval_version", columnList = "versionId")
})
public class Approval {

    /** Status persetujuan. */
    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }

    @Id
    public String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    public String apiId;

    @Column(nullable = false)
    public String versionId;

    @Column(nullable = false)
    public String environment;

    @Enumerated(EnumType.STRING)
    public Status status = Status.PENDING;

    @Column(length = 4000)
    public String reason;

    /** Diff ringkas flow terhadap versi published terakhir. */
    @Column(length = 20000)
    public String flowDiffJson = "{}";

    /** Diff kode Java per node. */
    @Column(length = 20000)
    public String javaDiffJson = "{}";

    public Instant createdAt = Instant.now();

    public String requestedBy;

    public Instant decidedAt;

    public String decidedBy;

    @Column(length = 2000)
    public String decisionNote;

    public Approval() {
    }

    public Approval(String apiId, String versionId, String environment, String requestedBy) {
        this.apiId = apiId;
        this.versionId = versionId;
        this.environment = environment;
        this.requestedBy = requestedBy;
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }
}
