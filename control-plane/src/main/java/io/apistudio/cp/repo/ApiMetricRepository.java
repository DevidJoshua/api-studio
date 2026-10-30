package io.apistudio.cp.repo;

import io.apistudio.cp.domain.ApiMetric;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ApiMetricRepository extends JpaRepository<ApiMetric, String> {

    Optional<ApiMetric> findByApiIdAndEnvironmentAndBucket(String apiId, String environment, Instant bucket);

    List<ApiMetric> findByBucketAfterOrderByBucketAsc(Instant after);

    List<ApiMetric> findByApiIdAndBucketAfterOrderByBucketAsc(String apiId, Instant after);
}