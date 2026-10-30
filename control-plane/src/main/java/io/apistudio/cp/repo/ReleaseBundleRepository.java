package io.apistudio.cp.repo;

import io.apistudio.cp.domain.ReleaseBundle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReleaseBundleRepository extends JpaRepository<ReleaseBundle, String> {

    List<ReleaseBundle> findByApiIdOrderByCreatedAtDesc(String apiId);

    Optional<ReleaseBundle> findFirstByApiIdAndVersionIdOrderByCreatedAtDesc(String apiId, String versionId);
}