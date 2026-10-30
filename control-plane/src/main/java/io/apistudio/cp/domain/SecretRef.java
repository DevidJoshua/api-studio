package io.apistudio.cp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Referensi secret (BRD FR-SEC-02).
 *
 * <p>Prinsip: flow hanya menyimpan <b>nama</b> secret. Nilai disimpan
 * terenkripsi AES-GCM untuk mode lokal; pada produksi {@code vaultPath}
 * dipakai dan nilai tidak disimpan di database sama sekali.
 */
@Entity
@Table(name = "secret_ref", indexes = {
        @Index(name = "idx_secret_name_env", columnList = "name,environment", unique = true)
})
public class SecretRef {

    @Id
    public String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    public String name;

    @Column(nullable = false)
    public String environment = "dev";

    /** Nilai terenkripsi (Base64: IV + ciphertext + tag). Null bila memakai vault. */
    @Column(length = 4000)
    public String encryptedValue;

    /** Petunjuk masked untuk UI, mis. {@code ****abcd}. */
    public String hint;

    /** Jalur vault bila nilai dikelola vault/KMS. */
    public String vaultPath;

    public String description;

    public Instant createdAt = Instant.now();

    public String createdBy;

    public Instant updatedAt = Instant.now();

    public String updatedBy;

    public SecretRef() {
    }

    public SecretRef(String name, String environment, String description, String createdBy) {
        this.name = name;
        this.environment = environment;
        this.description = description;
        this.createdBy = createdBy;
    }
}
