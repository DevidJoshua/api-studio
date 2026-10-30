package io.apistudio.cp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Catatan audit setiap perubahan (BRD FR-SEC-03, NFR-07).
 *
 * <p>Menyimpan aktor, aksi, entitas terkait, serta diff sebelum/sesudah
 * sehingga perubahan dapat ditelusuri, termasuk perubahan kode Java.
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_created", columnList = "createdAt"),
        @Index(name = "idx_audit_entity", columnList = "entityType,entityId")
})
public class AuditLog {

    @Id
    public String id = UUID.randomUUID().toString();

    /** Aksi: API_CREATED, VERSION_PUBLISHED, DEPLOY_ACTIVATED, dsb. */
    @Column(nullable = false)
    public String action;

    @Column(nullable = false)
    public String entityType;

    public String entityId;

    @Column(length = 2000)
    public String summary;

    /** JSON diff sebelum/sesudah (dipotong agar hemat ruang). */
    @Column(length = 40000)
    public String diffJson;

    public String actorUsername;

    public String actorRole;

    public String ipAddress;

    public Instant createdAt = Instant.now();

    public AuditLog() {
    }

    public AuditLog(String action, String entityType, String entityId, String summary,
                    String actorUsername, String actorRole) {
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.summary = summary;
        this.actorUsername = actorUsername;
        this.actorRole = actorRole;
    }
}
