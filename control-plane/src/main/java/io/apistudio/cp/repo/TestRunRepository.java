package io.apistudio.cp.repo;

import io.apistudio.cp.domain.TestRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestRunRepository extends JpaRepository<TestRun, String> {

    List<TestRun> findTop50ByApiIdOrderByCreatedAtDesc(String apiId);

    List<TestRun> findByTestCaseIdOrderByCreatedAtDesc(String testCaseId);
}