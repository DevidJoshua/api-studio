package io.apistudio.cp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Release bundle immutable (BRD FR-DEP-01, model data Bagian 8).
 *
 * <p>Bundle berisi snapshot lengkap: flow, bytecode, library, kebijakan, mock,
 * dan seed. {@code artifactHash} dihitung saat publish dan dipakai untuk
 * memverifikasi konsistensi antar instance (FR-DEP-07).
 */
@Entity
@Table(name = "release_bundle", indexes = {
        @Index(name = "idx_release_api", columnList = "apiId"),
        @Index(name = "idx_release_version", columnList = "versionId")
})
public class ReleaseBundle {

    @Id
    public String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    public String apiId;

    @Column(nullable = false)
    public String versionId;

    public int versionNumber;

    @Column(length = 200000)
    public String flowJson = "{}";

    @Column(length = 200000)
    public String javaSourcesJson = "{}";

    @Column(length = 400000)
    public String bytecodeJson = "{}";

    @Column(length = 4000)
    public String policyJson = "{}";

    @Column(length = 20000)
    public String mocksJson = "[]";

    @Column(length = 20000)
    public String seedsJson = "[]";

    /** SHA-256 dari gabungan flow + bytecode + policy + mocks + seeds. */
    public String artifactHash;

    /** Ringkasan test suite saat publish (FR-SIM-09 gate). */
    @Column(length = 20000)
    public String testSummaryJson = "{}";

    public Instant createdAt = Instant.now();

    public String createdBy;

    public String approvalId;

    public ReleaseBundle() {
    }
}
