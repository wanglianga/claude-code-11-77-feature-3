package com.example.clearing.repo;

import com.example.clearing.domain.ShadowRecalc;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShadowRecalcRepository extends JpaRepository<ShadowRecalc, Long> {

    List<ShadowRecalc> findByTaskIdOrderByIdDesc(Long taskId);

    List<ShadowRecalc> findByTargetVersion(String targetVersion);
}
