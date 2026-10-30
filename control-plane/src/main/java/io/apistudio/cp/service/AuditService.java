package io.apistudio.cp.service;

import io.apistudio.cp.domain.AuditLog;
import io.apistudio.cp.repo.AuditLogRepository;
import io.apistudio.cp.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pencatatan audit untuk setiap perubahan (BRD FR-SEC-03, NFR-07).
 *
 * <p>Audit bersifat append-only dan mencatat aktor beserta ringkasan
 * perubahan. Diff lengkap dapat disertakan untuk perubahan sensitif seperti
 * kode Java.
 */
@Service
public class AuditService {

    /** Batas ukuran diff agar tidak membengkakkan database. */
    private static final int MAX_DIFF_LENGTH = 40000;

    private final AuditLogRepository repository;
    private final JsonStore json;

    public AuditService(AuditLogRepository repository, JsonStore json) {
        this.repository = repository;
        this.json = json;
    }

    /** Catat aksi tanpa diff. */
    @Transactional
    public void record(CurrentUser actor, String action, String entityType, String entityId, String summary) {
        record(actor, action, entityType, entityId, summary, null);
    }

    /** Catat aksi beserta diff. */
    @Transactional
    public void record(CurrentUser actor, String action, String entityType, String entityId,
                       String summary, Object diff) {
        AuditLog log = new AuditLog(
                action,
                entityType,
                entityId,
                summary,
                actor == null ? "system" : actor.username(),
                actor == null || actor.role() == null ? "SYSTEM" : actor.role().name());
        if (diff != null) {
            String encoded = json.writeOr(diff, "{}");
            log.diffJson = encoded.length() > MAX_DIFF_LENGTH
                    ? encoded.substring(0, MAX_DIFF_LENGTH) + "…(dipotong)"
                    : encoded;
        }
        repository.save(log);
    }

    /** Daftar audit terbaru. */
    public List<AuditLog> recent() {
        return repository.findTop200ByOrderByCreatedAtDesc();
    }

    /** Audit untuk satu entitas. */
    public List<AuditLog> forEntity(String entityType, String entityId) {
        if (entityType == null || entityType.isBlank()) {
            return recent();
        }
        return repository.findTop100ByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId);
    }

    /** Bentuk view untuk API. */
    public Map<String, Object> view(AuditLog log) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", log.id);
        view.put("action", log.action);
        view.put("entityType", log.entityType);
        view.put("entityId", log.entityId);
        view.put("summary", log.summary);
        view.put("actor", log.actorUsername);
        view.put("actorRole", log.actorRole);
        view.put("createdAt", log.createdAt == null ? Instant.now().toString() : log.createdAt.toString());
        if (log.diffJson != null && !log.diffJson.isBlank()) {
            view.put("diff", json.readAny(log.diffJson));
        }
        return view;
    }
}
