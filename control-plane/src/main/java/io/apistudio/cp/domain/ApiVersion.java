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
 * Versi API (BRD FR-API-04, model data Bagian 8).
 *
 * <p>Setiap versi memuat definisi flow, kode Java per node, seed/mock, dan
 * snapshot kebijakan. Versi menjadi <b>immutable</b> setelah PUBLISHED, sesuai
 * prinsip "rilis immutable dan berversi" (Bagian 5.2 poin 3).
 */
@Entity
@Table(name = "api_version", indexes = {
        @Index(name = "idx_version_api", columnList = "apiId"),
        @Index(name = "idx_version_status", columnList = "status")
})
public class ApiVersion {

    /** Siklus hidup versi. */
    public enum Status {
        /** Sedang diedit; belum menerima traffic. */
        DRAFT,
        /** Sudah di-publish menjadi release; immutable. */
        PUBLISHED,
        /** Tidak dipakai untuk deploy baru tetapi mungkin masih melayani. */
        DEPRECATED
    }

    @Id
    public String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    public String apiId;

    /** Nomor versi monoton per API, mulai dari 1. */
    public int versionNumber;

    @Enumerated(EnumType.STRING)
    public Status status = Status.DRAFT;

    /** Definisi flow sebagai JSON kontrak FlowDefinition. */
    @Column(length = 200000)
    public String flowJson = "{}";

    /** Kode Java per node: {@code { nodeId: { source, libraries[] } }}. */
    @Column(length = 200000)
    public String javaSourcesJson = "{}";

    /** Bytecode hasil kompilasi per node (Base64). */
    @Column(length = 400000)
    public String bytecodeJson = "{}";

    /** Snapshot kebijakan API saat versi dibuat. */
    @Column(length = 4000)
    public String policyJson = "{}";

    /** Mock default untuk simulasi (FR-SIM-04). */
    @Column(length = 20000)
    public String mocksJson = "[]";

    /** Seed database sandbox default (FR-SIM-05). */
    @Column(length = 20000)
    public String seedsJson = "[]";

    @Column(length = 4000)
    public String changeNote;

    public Instant createdAt = Instant.now();

    public String createdBy;

    public Instant publishedAt;

    public String publishedBy;

    public Instant deprecatedAt;

    public ApiVersion() {
    }

    public ApiVersion(String apiId, int versionNumber, String createdBy) {
        this.apiId = apiId;
        this.versionNumber = versionNumber;
        this.createdBy = createdBy;
    }

    public boolean isDraft() {
        return status == Status.DRAFT;
    }

    public boolean isPublished() {
        return status == Status.PUBLISHED;
    }

    public String label() {
        return "v" + versionNumber;
    }

    /** Versi "$7$-karakter" untuk nama class hasil kompilasi. */
    public String artifactSuffix() {
        return id.replace("-", "").substring(0, 8);
    }
}
