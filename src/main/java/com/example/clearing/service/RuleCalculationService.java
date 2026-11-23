package com.example.clearing.service;

import com.example.clearing.domain.RuleSnapshot;
import com.example.clearing.dto.BillPayload;
import com.example.clearing.repo.RuleSnapshotRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

/**
 * 待遇计算口径：按指定规则快照的统筹支付比例计算。
 * 正式待遇计算与影子复算都走这一处，保证两条路径口径一致、可比对。
 */
@Service
public class RuleCalculationService {

    /** 快照库缺失时的兜底口径（与历史版本 2026.11 一致），仅用于防御 */
    public static final BigDecimal FALLBACK_RATE = new BigDecimal("0.7000");

    private final RuleSnapshotRepository snapshotRepository;

    public RuleCalculationService(RuleSnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }

    /** 报文费用总额 */
    public BigDecimal totalAmount(BillPayload payload) {
        return payload.getItems().stream()
                .map(i -> i.getPrice().multiply(BigDecimal.valueOf(i.getQty())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 按规则版本计算统筹支付金额：总额 × 快照比例，四舍五入保留两位 */
    public BigDecimal calculate(BillPayload payload, String ruleVersion) {
        BigDecimal rate = rateOf(ruleVersion);
        return totalAmount(payload).multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal rateOf(String ruleVersion) {
        if (ruleVersion == null) {
            return FALLBACK_RATE;
        }
        return snapshotRepository.findByVersion(ruleVersion)
                .map(RuleSnapshot::getReimbursementRate)
                .orElse(FALLBACK_RATE);
    }
}
