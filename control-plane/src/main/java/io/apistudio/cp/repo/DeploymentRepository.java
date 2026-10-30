package io.apistudio.cp.repo;

import io.apistudio.cp.domain.Deployment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeploymentRepository extends JpaRepository<Deployment, String> {

    List<Deployment> findByApiIdOrderByCreatedAtDesc(String apiId);

    List<Deployment> findByApiIdAndEnvironmentOrderByCreatedAtDesc(String apiId, String environment);

    List<Deployment> findByApiIdAndEnvironmentAndStatus(String apiId, String environment, Deployment.Status status);

    List<Deployment> findAllByOrderByCreatedAtDesc();

    Optional<Deployment> findFirstByApiIdAndEnvironmentAndStatusOrderByCreatedAtDesc(
            String apiId, String environment, Deployment.Status status);
}