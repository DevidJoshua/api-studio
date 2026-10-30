package io.apistudio.cp.repo;

import io.apistudio.cp.domain.ApiEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiRepository extends JpaRepository<ApiEntity, String> {

    Optional<ApiEntity> findByPathAndMethod(String path, String method);

    List<ApiEntity> findByGroupIdOrderByNameAsc(String groupId);

    List<ApiEntity> findAllByOrderByNameAsc();
}