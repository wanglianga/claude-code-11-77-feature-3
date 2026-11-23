package com.example.clearing.repo;

import com.example.clearing.domain.TaskRuleBinding;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRuleBindingRepository extends JpaRepository<TaskRuleBinding, Long> {

    List<TaskRuleBinding> findByTaskIdOrderBySeqAsc(Long taskId);

    long countByTaskId(Long taskId);
}
