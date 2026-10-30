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
 * Versi semantik dari sebuah function template (BRD FR-TPL-04).
 *
 * <p>Flow mengunci ke versi tertentu (mis. {@code snapbi.signature@1}) dan
 * hanya naik versi secara eksplisit, sehingga perubahan template tidak
 * mengubah perilaku flow yang sudah berjalan.
 */
@Entity
@Table(name = "template_version", indexes = {
        @Index(name = "idx_tplver_template", columnList = "templateId"),
        @Index(name = "idx_tplver_key_version", columnList = "templateKey,versionNumber", unique = true)
})
public class TemplateVersion {

    @Id
    public String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    public String templateId;

    @Column(nullable = false)
    public String templateKey;

    /** Nomor versi semantik mulai dari 1. */
    public int versionNumber = 1;

    @Enumerated(EnumType.STRING)
    public FunctionTemplate.ImplType implType = FunctionTemplate.ImplType.SCRIPT;

    /**
     * Implementasi bergantung pada {@code implType}:
     * <ul>
     *   <li>SCRIPT &rarr; ekspresi tunggal, mis. {@code =sha256(inputs.payload)}</li>
     *   <li>COMPOSITE &rarr; FlowDefinition JSON</li>
     *   <li>JAVA &rarr; source Java</li>
     * </ul>
     */
    @Column(length = 100000)
    public String implementation = "";

    /** Definisi input bertipe (FR-TPL-01): {@code [{name,type,required,description}]}. */
    @Column(length = 20000)
    public String inputsJson = "[]";

    /** Definisi output bertipe. */
    @Column(length = 20000)
    public String outputJson = "{}";

    @Column(length = 4000)
    public String changeNote;

    /** true bila versi ini dipakai pemanggilan {@code key} tanpa versi eksplisit. */
    public boolean latest;

    public Instant createdAt = Instant.now();

    public String createdBy;

    public TemplateVersion() {
    }

    public TemplateVersion(String templateId, String templateKey, int versionNumber,
                           FunctionTemplate.ImplType implType, String implementation, String createdBy) {
        this.templateId = templateId;
        this.templateKey = templateKey;
        this.versionNumber = versionNumber;
        this.implType = implType;
        this.implementation = implementation;
        this.createdBy = createdBy;
    }

    public String qualifiedKey() {
        return templateKey + "@" + versionNumber;
    }
}
