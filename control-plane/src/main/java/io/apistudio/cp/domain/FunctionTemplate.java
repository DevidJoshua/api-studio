package io.apistudio.cp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Function template yang dapat dipakai ulang di flow (BRD FR-TPL-01..07).
 *
 * <p>Template memiliki implementasi bertipe: composite (sub-flow), Java, atau
 * script ekspresi. Katalog dibagi per kategori untuk memudahkan pencarian di
 * palette (FR-TPL-03).
 */
@Entity
@Table(name = "function_template", indexes = {
        @Index(name = "idx_template_key", columnList = "templateKey", unique = true),
        @Index(name = "idx_template_category", columnList = "category")
})
public class FunctionTemplate {

    /** Jenis implementasi template (FR-TPL-02). */
    public enum ImplType {
        /** Sub-flow yang tersusun dari node. */
        COMPOSITE,
        /** Kode Java. */
        JAVA,
        /** Ekspresi script (interpreter engine). */
        SCRIPT
    }

    @Id
    public String id = UUID.randomUUID().toString();

    /** Kunci unik tanpa versi, mis. {@code snapbi.signature}. */
    @Column(nullable = false)
    public String templateKey;

    @Column(nullable = false)
    public String name;

    /** Kategori: Utilities, Security, SNAP BI, Database, Integrasi. */
    @Column(nullable = false)
    public String category = "Utilities";

    @Column(length = 4000)
    public String description;

    public String tags = "";

    /** true bila template bawaan sistem (tidak dapat dihapus). */
    public boolean builtin;

    public Instant createdAt = Instant.now();

    public String createdBy;

    public FunctionTemplate() {
    }

    public FunctionTemplate(String templateKey, String name, String category, String description,
                            boolean builtin, String createdBy) {
        this.templateKey = templateKey;
        this.name = name;
        this.category = category;
        this.description = description;
        this.builtin = builtin;
        this.createdBy = createdBy;
    }
}
