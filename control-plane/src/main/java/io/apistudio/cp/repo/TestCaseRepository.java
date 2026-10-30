package io.apistudio.cp.repo;

import io.apistudio.cp.domain.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestCaseRepository extends JpaRepository<TestCase, String> {

    List<TestCase> findByApiIdOrderByCreatedAtAsc(String apiId);

    List<TestCase> findByApiIdAndEnabledTrueOrderByCreatedAtAsc(String apiId);
}