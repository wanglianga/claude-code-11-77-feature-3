package com.example.clearing.service;

import com.example.clearing.domain.RuleSnapshot;
import com.example.clearing.domain.RuleSnapshotStatus;
import com.example.clearing.repo.RuleSnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 规则快照库：规则服务每次发布目录/待遇规则，都在此固化不可变快照。
 *  - 版本号唯一，previousVersion 维护版本链；
 *  - effectiveFrom 为未来日期时先以 DRAFT 入库，跨午夜后由 activateDue() 激活；
 *  - 新版本激活时旧生效版本置 SUPERSEDED；人工撤回置 WITHDRAWN，已绑定任务仍可复现。
 */
@Service
public class RuleSnapshotService {

    private final RuleSnapshotRepository snapshotRepository;
    private final LogicalClock clock;
    private final ObjectMapper objectMapper;

    public RuleSnapshotService(RuleSnapshotRepository snapshotRepository,
            LogicalClock clock,
            ObjectMapper objectMapper) {
        this.snapshotRepository = snapshotRepository;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /** 发布规则快照：到生效日的立即生效，未来生效日的入草稿等待跨午夜激活 */
    @Transactional
    public RuleSnapshot publish(String version, BigDecimal reimbursementRate,
            LocalDate effectiveFrom, String description) {
        if (snapshotRepository.findByVersion(version).isPresent()) {
            throw new IllegalArgumentException("规则版本已存在，快照不可变: " + version);
        }
        if (reimbursementRate == null
                || reimbursementRate.signum() <= 0
                || reimbursementRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("统筹支付比例必须在 (0,1] 区间");
        }
        LocalDate from = effectiveFrom == null ? clock.today() : effectiveFrom;
        Optional<RuleSnapshot> chainHead = latestSnapshot();

        RuleSnapshot snapshot = new RuleSnapshot();
        snapshot.setVersion(version);
        snapshot.setPreviousVersion(chainHead.map(RuleSnapshot::getVersion).orElse(null));
        snapshot.setReimbursementRate(reimbursementRate);
        snapshot.setRuleBody(toBody(version, reimbursementRate, from, description));
        snapshot.setEffectiveFrom(from);
        snapshot.setPublishedAt(Instant.now());

        boolean due = !from.isAfter(clock.today());
        if (due) {
            snapshot.setStatus(RuleSnapshotStatus.EFFECTIVE);
            snapshot.setBecameEffectiveAt(Instant.now());
            supersedeCurrentEffective(version);
        } else {
            snapshot.setStatus(RuleSnapshotStatus.DRAFT);
        }
        return snapshotRepository.save(snapshot);
    }

    /**
     * 跨午夜激活：到生效日的 DRAFT 快照转 EFFECTIVE，原生效版本转 SUPERSEDED。
     * 在每个调度周期开头执行，保证“规则跨午夜生效”自动发生。
     */
    @Transactional
    public List<RuleSnapshot> activateDue() {
        List<RuleSnapshot> due = snapshotRepository
                .findByStatusAndEffectiveFromLessThanEqual(RuleSnapshotStatus.DRAFT, clock.today());
        for (RuleSnapshot snapshot : due) {
            supersedeCurrentEffective(snapshot.getVersion());
            snapshot.setStatus(RuleSnapshotStatus.EFFECTIVE);
            snapshot.setBecameEffectiveAt(Instant.now());
            snapshotRepository.save(snapshot);
        }
        return due;
    }

    /** 人工撤回：DRAFT/EFFECTIVE 可撤回；已绑定任务的历史复现不受影响 */
    @Transactional
    public RuleSnapshot withdraw(String version, String reason) {
        RuleSnapshot snapshot = snapshotRepository.findByVersion(version)
                .orElseThrow(() -> new IllegalArgumentException("规则版本不存在: " + version));
        if (snapshot.getStatus() == RuleSnapshotStatus.WITHDRAWN) {
            throw new IllegalStateException("规则版本 " + version + " 已撤回，不能重复撤回");
        }
        if (snapshot.getStatus() == RuleSnapshotStatus.SUPERSEDED) {
            throw new IllegalStateException("规则版本 " + version + " 已被替代，不能撤回（版本链不可改写）");
        }
        snapshot.setStatus(RuleSnapshotStatus.WITHDRAWN);
        snapshot.setWithdrawnAt(Instant.now());
        snapshot.setWithdrawReason(reason);
        return snapshotRepository.save(snapshot);
    }

    public Optional<RuleSnapshot> currentEffective() {
        return snapshotRepository.findByStatus(RuleSnapshotStatus.EFFECTIVE).stream().findFirst();
    }

    public String currentEffectiveVersion() {
        return currentEffective().map(RuleSnapshot::getVersion).orElse("NONE");
    }

    public RuleSnapshot requireVersion(String version) {
        return snapshotRepository.findByVersion(version)
                .orElseThrow(() -> new IllegalArgumentException("规则快照不存在: " + version));
    }

    public List<RuleSnapshot> list() {
        return snapshotRepository.findAllByOrderByEffectiveFromAscIdAsc();
    }

    public Optional<RuleSnapshot> find(String version) {
        return snapshotRepository.findByVersion(version);
    }

    private Optional<RuleSnapshot> latestSnapshot() {
        List<RuleSnapshot> all = snapshotRepository.findAllByOrderByEffectiveFromAscIdAsc();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    private void supersedeCurrentEffective(String newVersion) {
        for (RuleSnapshot old : snapshotRepository.findByStatus(RuleSnapshotStatus.EFFECTIVE)) {
            if (!old.getVersion().equals(newVersion)) {
                old.setStatus(RuleSnapshotStatus.SUPERSEDED);
                snapshotRepository.save(old);
            }
        }
    }

    private String toBody(String version, BigDecimal rate, LocalDate effectiveFrom, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("reimbursementRate", rate);
        body.put("effectiveFrom", effectiveFrom.toString());
        body.put("description", description == null ? "" : description);
        body.put("frozenAt", Instant.now().toString());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("规则正文序列化失败", e);
        }
    }
}
