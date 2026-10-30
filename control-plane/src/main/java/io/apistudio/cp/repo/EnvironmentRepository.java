package io.apistudio.cp.repo;

import io.apistudio.cp.domain.Environment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EnvironmentRepository extends JpaRepository<Environment, String> {

    Optional<Environment> findByCode(String code);

    List<Environment> findAllByOrderByCodeAsc();
}