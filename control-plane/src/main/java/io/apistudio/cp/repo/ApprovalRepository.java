package io.apistudio.cp.repo;

import io.apistudio.cp.domain.Approval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApprovalRepository extends JpaRepository<Approval, String> {

    List<Approval> findByStatusOrderByCreatedAtDesc(Approval.Status status);

    List<Approval> findAllByOrderByCreatedAtDesc();

    List<Approval> findByApiIdOrderByCreatedAtDesc(String apiId);

    Optional<Approval> findFirstByVersionIdAndEnvironmentAndStatus(
            String versionId, String environment, Approval.Status status);
}