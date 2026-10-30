package io.apistudio.cp.repo;

import io.apistudio.cp.domain.Consumer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsumerRepository extends JpaRepository<Consumer, String> {
}