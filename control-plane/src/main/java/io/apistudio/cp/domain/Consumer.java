package io.apistudio.cp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumer API dan hak aksesnya (BRD FR-API-05).
 *
 * <p>API key disimpan sebagai hash SHA-256; nilai asli hanya ditampilkan sekali
 * saat pembuatan. Kuota harian dan daftar API yang diizinkan membatasi akses.
 */
@Entity
@Table(name = "consumer")
public class Consumer {

    @Id
    public String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    public String name;

    @Column(length = 2000)
    public String description;

    /** Hash SHA-256 API key; nilai asli tidak disimpan. */
    public String apiKeyHash;

    /** Petunjuk masked, mis. {@code ****abcd}. */
    public String apiKeyHint;

    /** Daftar apiId yang dizinkan, dipisah koma; kosong berarti semua. */
    @Column(length = 4000)
    public String allowedApiIds = "";

    /** Kuota harian; 0 = tanpa batas. */
    public long dailyQuota;

    public boolean enabled = true;

    public Instant createdAt = Instant.now();

    public String createdBy;

    public Consumer() {
    }

    public Consumer(String name, String description, String createdBy) {
        this.name = name;
        this.description = description;
        this.createdBy = createdBy;
    }
}
