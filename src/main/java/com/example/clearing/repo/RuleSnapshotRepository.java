package com.example.clearing.repo;

import com.example.clearing.domain.RuleSnapshot;
import com.example.clearing.domain.RuleSnapshotStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleSnapshotRepository extends JpaRepository<RuleSnapshot, Long> {

    Optional<RuleSnapshot> findByVersion(String version);

    List<RuleSnapshot> findByStatus(RuleSnapshotStatus status);

    /** 已到生效日但仍为草稿的快照（跨午夜生效扫描） */
    List<RuleSnapshot> findByStatusAndEffectiveFromLessThanEqual(RuleSnapshotStatus status, LocalDate date);

    List<RuleSnapshot> findAllByOrderByEffectiveFromAscIdAsc();
}
