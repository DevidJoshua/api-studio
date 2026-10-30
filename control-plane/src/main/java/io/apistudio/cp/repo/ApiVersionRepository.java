package io.apistudio.cp.repo;

import io.apistudio.cp.domain.ApiVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiVersionRepository extends JpaRepository<ApiVersion, String> {

    List<ApiVersion> findByApiIdOrderByVersionNumberDesc(String apiId);

    List<ApiVersion> findByApiIdAndStatusOrderByVersionNumberDesc(String apiId, ApiVersion.Status status);

    Optional<ApiVersion> findFirstByApiIdOrderByVersionNumberDesc(String apiId);

    long countByApiId(String apiId);
}