package com.example.clearing.repo;

import com.example.clearing.domain.ResultPublication;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResultPublicationRepository extends JpaRepository<ResultPublication, Long> {

    List<ResultPublication> findByBusinessKeyOrderByRevisionSeqAsc(String businessKey);

    Optional<ResultPublication> findFirstByBusinessKeyOrderByRevisionSeqDesc(String businessKey);

    long countByBusinessKey(String businessKey);
}
