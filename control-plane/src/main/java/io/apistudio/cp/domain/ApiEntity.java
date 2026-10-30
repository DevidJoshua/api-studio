package io.apistudio.cp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Definisi API (BRD FR-API-01/02/03/07).
 *
 * <p>API menyimpan metadata dan kebijakan tingkat API; logika eksekusi ada di
 * {@link ApiVersion}. Status API diturunkan dari versi yang aktif di tiap
 * environment, bukan disimpan sebagai kolom terpisah agar tidak dapat
 * menyimpang dari kenyataan.
 */
@Entity
@Table(name = "api", indexes = {
        @Index(name = "idx_api_path_method", columnList = "path,method"),
        @Index(name = "idx_api_group", columnList = "groupId")
})
public class ApiEntity {

    @Id
    public String id = UUID.randomUUID().toString();

    public String groupId;

    @Column(nullable = false)
    public String name;

    /** Path publik, misalnya {@code /v1/payments}. */
    @Column(nullable = false)
    public String path;

    /** Method HTTP: GET/POST/PUT/PATCH/DELETE. */
    @Column(nullable = false)
    public String method = "POST";

    @Column(length = 2000)
    public String description;

    public String tags = "";

    /** true bila dinonaktifkan; tidak menerima traffic (FR-API-01). */
    public boolean disabled;

    /** Versi aktif per environment, format {@code env=versionId;...}. */
    @Column(length = 2000)
    public String activeVersions = "";

    /** JSON kebijakan: auth, rate limit, IP whitelist, CORS (FR-API-03). */
    @Column(length = 4000)
    public String policyJson = "{}";

    public Instant createdAt = Instant.now();

    public String createdBy;

    public Instant updatedAt = Instant.now();

    public String updatedBy;

    public ApiEntity() {
    }

    public ApiEntity(String groupId, String name, String path, String method, String description,
                     String tags, String createdBy) {
        this.groupId = groupId;
        this.name = name;
        this.path = path;
        this.method = method;
        this.description = description;
        this.tags = tags == null ? "" : tags;
        this.createdBy = createdBy;
    }
}
