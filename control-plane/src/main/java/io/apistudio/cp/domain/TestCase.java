package io.apistudio.cp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Test case tersimpan untuk satu API (BRD FR-SIM-01/09/10).
 *
 * <p>Test case menyimpan request lengkap (method, path, header, query, body),
 * mock, seed database, dan assertion sehingga dapat dijalankan ulang sebagai
 * bagian test suite yang menjadi syarat publish.
 */
@Entity
@Table(name = "test_case", indexes = {
        @Index(name = "idx_testcase_api", columnList = "apiId"),
        @Index(name = "idx_testcase_version", columnList = "versionId")
})
public class TestCase {

    @Id
    public String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    public String apiId;

    /** Versi tempat test dibuat; dapat dijalankan pada versi lain. */
    public String versionId;

    @Column(nullable = false)
    public String name;

    @Column(length = 2000)
    public String description;

    public String method = "POST";

    public String path;

    @Column(length = 20000)
    public String headersJson = "{}";

    @Column(length = 20000)
    public String queryJson = "{}";

    @Column(length = 100000)
    public String bodyJson = "{}";

    /** Mock dependensi (FR-SIM-04). */
    @Column(length = 20000)
    public String mocksJson = "[]";

    /** Seed database sandbox (FR-SIM-05). */
    @Column(length = 20000)
    public String seedJson = "[]";

    /** Assertion (FR-SIM-06/07/08). */
    @Column(length = 20000)
    public String assertionsJson = "[]";

    /** Data-driven test dari CSV (FR-SIM-10); baris dipisah newline. */
    @Column(length = 100000)
    public String csvData;

    public boolean enabled = true;

    /** true bila kegagalan test menghalangi publish. */
    public boolean blocking = true;

    public Instant createdAt = Instant.now();

    public String createdBy;

    public Instant updatedAt = Instant.now();

    public String updatedBy;

    public TestCase() {
    }

    public TestCase(String apiId, String versionId, String name, String createdBy) {
        this.apiId = apiId;
        this.versionId = versionId;
        this.name = name;
        this.createdBy = createdBy;
    }
}
