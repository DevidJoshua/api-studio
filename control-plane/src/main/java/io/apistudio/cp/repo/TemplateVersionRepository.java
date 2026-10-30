package io.apistudio.cp.repo;

import io.apistudio.cp.domain.TemplateVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TemplateVersionRepository extends JpaRepository<TemplateVersion, String> {

    List<TemplateVersion> findByTemplateIdOrderByVersionNumberDesc(String templateId);

    Optional<TemplateVersion> findFirstByTemplateKeyOrderByVersionNumberDesc(String templateKey);

    Optional<TemplateVersion> findByTemplateKeyAndVersionNumber(String templateKey, int versionNumber);
}