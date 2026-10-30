package io.apistudio.cp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Pengguna platform dan perannya (BRD Bagian 4, FR-SEC-01).
 *
 * <p>Field bersifat publik dan sederhana karena entity ini murni pembawa data;
 * semua aturan bisnis ada di service.
 */
@Entity
@Table(name = "app_user", indexes = {
        @Index(name = "idx_user_username", columnList = "username", unique = true)
})
public class AppUser {

    @Id
    public String id = UUID.randomUUID().toString();

    @Column(nullable = false, unique = true)
    public String username;

    /** Hash BCrypt; kosong untuk pengguna SSO. */
    public String passwordHash;

    public String displayName;

    public String email;

    /** Nama peran sesuai BRD Bagian 4. */
    public String role = "DESIGNER";

    public boolean enabled = true;

    /** true bila dibuat sistem (bootstrap). */
    @Column(name = "is_system_user")
    public boolean systemUser;

    public String ssoSubject;

    public Instant createdAt = Instant.now();

    public Instant lastLoginAt;

    public AppUser() {
    }

    public AppUser(String username, String displayName, String email, String role) {
        this.username = username;
        this.displayName = displayName;
        this.email = email;
        this.role = role;
    }
}
