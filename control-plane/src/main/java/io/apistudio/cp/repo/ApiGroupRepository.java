package io.apistudio.cp.repo;

import io.apistudio.cp.domain.ApiGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApiGroupRepository extends JpaRepository<ApiGroup, String> {

    List<ApiGroup> findAllByOrderByNameAsc();
}