package com.example.clearing.repo;

import com.example.clearing.domain.ImpactNotice;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImpactNoticeRepository extends JpaRepository<ImpactNotice, Long> {

    List<ImpactNotice> findByDiffCaseId(Long diffCaseId);

    List<ImpactNotice> findByBusinessKeyOrderByIdAsc(String businessKey);
}
