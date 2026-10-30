package io.apistudio.cp.repo;

import io.apistudio.cp.domain.FunctionTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FunctionTemplateRepository extends JpaRepository<FunctionTemplate, String> {

    Optional<FunctionTemplate> findByTemplateKey(String templateKey);

    List<FunctionTemplate> findAllByOrderByCategoryAscNameAsc();
}