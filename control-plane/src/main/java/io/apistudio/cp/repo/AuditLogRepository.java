package io.apistudio.cp.repo;

import io.apistudio.cp.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, String> {

    List<AuditLog> findTop200ByOrderByCreatedAtDesc();

    List<AuditLog> findTop100ByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, String entityId);
}