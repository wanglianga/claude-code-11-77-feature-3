package com.example.clearing.repo;

import com.example.clearing.domain.DiffCaseStatus;
import com.example.clearing.domain.RuleDiffCase;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleDiffCaseRepository extends JpaRepository<RuleDiffCase, Long> {

    List<RuleDiffCase> findByStatus(DiffCaseStatus status);

    List<RuleDiffCase> findByTaskIdOrderByIdDesc(Long taskId);

    List<RuleDiffCase> findByBusinessKeyOrderByIdAsc(String businessKey);

    java.util.Optional<RuleDiffCase> findByCorrectionTaskId(Long correctionTaskId);
}
