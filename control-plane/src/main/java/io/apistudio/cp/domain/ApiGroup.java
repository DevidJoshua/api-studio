package io.apistudio.cp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Grup/proyek API (BRD FR-API-02).
 *
 * <p>Grup adalah unit organisasi utama di Studio: pengelompokan berdasarkan
 * microservice atau domain bisnis, dengan warna untuk identitas visual.
 */
@Entity
@Table(name = "api_group", indexes = {
        @Index(name = "idx_group_name", columnList = "name")
})
public class ApiGroup {

    @Id
    public String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    public String name;

    @Column(length = 2000)
    public String description;

    /** Warna aksen untuk UI (mis. {@code indigo}). */
    public String color = "indigo";

    public String createdBy;

    public Instant createdAt = Instant.now();

    public ApiGroup() {
    }

    public ApiGroup(String name, String description, String createdBy) {
        this.name = name;
        this.description = description;
        this.createdBy = createdBy;
    }
}
