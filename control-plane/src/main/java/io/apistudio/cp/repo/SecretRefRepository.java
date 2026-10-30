package io.apistudio.cp.repo;

import io.apistudio.cp.domain.SecretRef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SecretRefRepository extends JpaRepository<SecretRef, String> {

    Optional<SecretRef> findByNameAndEnvironment(String name, String environment);

    List<SecretRef> findByEnvironmentOrderByNameAsc(String environment);

    List<SecretRef> findAllByOrderByNameAsc();
}