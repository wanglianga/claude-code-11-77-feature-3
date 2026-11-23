package com.example.clearing.service;

import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.RecalcTrigger;
import com.example.clearing.domain.ShadowRecalc;
import com.example.clearing.domain.ShadowRecalcStatus;
import com.example.clearing.domain.Stage;
import com.example.clearing.dto.BillPayload;
import com.example.clearing.repo.LedgerEntryRepository;
import com.example.clearing.repo.ShadowRecalcRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 影子复算器：规则新版本发布后，用新快照对存量任务的同一报文重新计算，
 * 只算不写账——不产生分录、不改变任务状态，差额写入 shadow_recalc 供差异裁决队列。
 */
@Service
public class ShadowRecalculator {

    private final ShadowRecalcRepository recalcRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final RuleCalculationService calculationService;
    private final DownstreamSimulator simulator;

    public ShadowRecalculator(ShadowRecalcRepository recalcRepository,
            LedgerEntryRepository ledgerRepository,
            RuleCalculationService calculationService,
            DownstreamSimulator simulator) {
        this.recalcRepository = recalcRepository;
        this.ledgerRepository = ledgerRepository;
        this.calculationService = calculationService;
        this.simulator = simulator;
    }

    /** 对单个任务做影子复算（目标版本必须已在快照库中） */
    @Transactional
    public ShadowRecalc recalc(ClearingTask task, String targetVersion, RecalcTrigger trigger, String remark) {
        BillPayload payload = simulator.parse(task.getPayload());
        BigDecimal original = originalAmount(task, payload);
        BigDecimal shadow = calculationService.calculate(payload, targetVersion);

        ShadowRecalc recalc = new ShadowRecalc();
        recalc.setTaskId(task.getId());
        recalc.setBusinessKey(task.getBusinessKey());
        recalc.setBillVersion(task.getBillVersion());
        recalc.setContentHash(task.getContentHash());
        recalc.setBaseVersion(task.getRuleVersion());
        recalc.setTargetVersion(targetVersion);
        recalc.setOriginalAmount(original);
        recalc.setShadowAmount(shadow);
        recalc.setDeltaAmount(shadow.subtract(original));
        recalc.setTriggerType(trigger);
        recalc.setStatus(ShadowRecalcStatus.CALCULATED);
        recalc.setRemark(remark);
        return recalcRepository.save(recalc);
    }

    /** 批量影子复算（新版本激活时扫描存量任务） */
    @Transactional
    public List<ShadowRecalc> recalcBatch(List<ClearingTask> tasks, String targetVersion,
            RecalcTrigger trigger, String remark) {
        return tasks.stream()
                .map(t -> recalc(t, targetVersion, trigger, remark))
                .toList();
    }

    /** 任务是否已冻结：待遇计算已执行（扣款分录已入账）即进入冻结范围 */
    public boolean isFrozen(ClearingTask task) {
        if (task.getStage() != Stage.CALC) {
            return true;
        }
        return ledgerRepository.findByOperationNo(LedgerService.debitOpNo(task)).isPresent();
    }

    /** 原口径金额：优先取已入账扣款，未执行则按当前绑定版本计算 */
    private BigDecimal originalAmount(ClearingTask task, BillPayload payload) {
        return ledgerRepository.findByOperationNo(LedgerService.debitOpNo(task))
                .map(e -> e.getAmount())
                .orElseGet(() -> calculationService.calculate(payload, task.getRuleVersion()));
    }

    public List<ShadowRecalc> byTask(Long taskId) {
        return recalcRepository.findByTaskIdOrderByIdDesc(taskId);
    }

    public List<ShadowRecalc> all() {
        return recalcRepository.findAll();
    }
}
