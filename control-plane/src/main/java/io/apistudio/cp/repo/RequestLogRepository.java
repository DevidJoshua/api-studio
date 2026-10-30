package io.apistudio.cp.repo;

import io.apistudio.cp.domain.RequestLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RequestLogRepository extends JpaRepository<RequestLog, String> {

    List<RequestLog> findTop100ByOrderByCreatedAtDesc();

    List<RequestLog> findTop50ByApiIdOrderByCreatedAtDesc(String apiId);

    Optional<RequestLog> findFirstByTraceIdOrderByCreatedAtDesc(String traceId);
}