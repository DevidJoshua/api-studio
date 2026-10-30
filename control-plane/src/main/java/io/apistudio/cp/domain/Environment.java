package io.apistudio.cp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Environment deployment: dev, staging, production (BRD FR-API-07, Bagian 10.1).
 *
 * <p>Sebuah environment menyimpan instance Flow Engine yang terdaftar, variabel
 * environment, serta flag kebijakan seperti wajib-approval dan canary.
 */
@Entity
@Table(name = "environment")
public class Environment {

    @Id
    public String id = UUID.randomUUID().toString();

    /** Kode unik: {@code dev}, {@code staging}, {@code production}. */
    @Column(nullable = false, unique = true)
    public String code;

    public String name;

    /** Publish ke environment ini wajib melalui approval (FR-DEP-02). */
    public boolean requiresApproval;

    /** Simulasi (mock + sandbox) diizinkan. */
    public boolean simulationEnabled = true;

    /** Canary/weighted routing diizinkan (FR-DEP-06). */
    public boolean canaryEnabled = true;

    /** Instance Flow Engine terdaftar: JSON {@code [{id,baseUrl,zone}]}. */
    @Column(length = 4000)
    public String instancesJson = "[]";

    /** Variabel environment (bukan rahasia): JSON map. */
    @Column(length = 4000)
    public String variablesJson = "{}";

    public String description;

    public Instant createdAt = Instant.now();

    public Environment() {
    }

    public Environment(String code, String name, boolean requiresApproval,
                       boolean simulationEnabled, boolean canaryEnabled) {
        this.code = code;
        this.name = name;
        this.requiresApproval = requiresApproval;
        this.simulationEnabled = simulationEnabled;
        this.canaryEnabled = canaryEnabled;
    }
}
