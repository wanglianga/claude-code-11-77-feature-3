package com.example.clearing.service;

import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.Decision;
import com.example.clearing.domain.DiffCaseStatus;
import com.example.clearing.domain.ImpactNotice;
import com.example.clearing.domain.NoticeStatus;
import com.example.clearing.domain.RecalcTrigger;
import com.example.clearing.domain.RuleDiffCase;
import com.example.clearing.domain.RuleSnapshot;
import com.example.clearing.domain.ShadowRecalc;
import com.example.clearing.domain.ShadowRecalcStatus;
import com.example.clearing.domain.Stage;
import com.example.clearing.domain.TaskStatus;
import com.example.clearing.repo.ClearingTaskRepository;
import com.example.clearing.repo.ImpactNoticeRepository;
import com.example.clearing.repo.RuleDiffCaseRepository;
import com.example.clearing.repo.ShadowRecalcRepository;
import com.example.clearing.repo.TaskRuleBindingRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 规则治理编排：把规则快照库、任务绑定器、影子复算器、差异裁决队列、
 * 结果发布册与影响通知串成两条可选治理路径。
 *
 * 策略 A（口径统一）：未执行任务改绑最新规则重算；已冻结任务只能进裁决队列，
 *                  其已回传结果拒绝任何静默覆盖。
 * 策略 B（历史可复现）：入队时点规则版本绑定不动，裁决后另发更正任务，
 *                    维护双版本账本并向影响方发通知。
 */
@Service
public class RuleGovernanceService {

    /** 裁决动作 */
    public enum DiffAction {
        /** 批准未执行任务按新规则重算（冻结案件禁止） */
        APPROVE,
        /** 撤回案件：未执行任务恢复入队时点绑定，冻结案件原样关闭 */
        WITHDRAW,
        /** 驳回：与撤回同效，语义为不认可新口径 */
        REJECT,
        /** 另发更正任务（策略 B，冻结案件唯一可行路径） */
        ISSUE_CORRECTION
    }

    private final RuleSnapshotService snapshotService;
    private final TaskRuleBinder binder;
    private final ShadowRecalculator recalculator;
    private final ClearingTaskRepository taskRepository;
    private final TaskRuleBindingRepository bindingRepository;
    private final ShadowRecalcRepository shadowRepository;
    private final RuleDiffCaseRepository diffRepository;
    private final ImpactNoticeRepository noticeRepository;
    private final LogicalClock clock;
    private final EventLog eventLog;

    public RuleGovernanceService(RuleSnapshotService snapshotService,
            TaskRuleBinder binder,
            ShadowRecalculator recalculator,
            ClearingTaskRepository taskRepository,
            TaskRuleBindingRepository bindingRepository,
            ShadowRecalcRepository shadowRepository,
            RuleDiffCaseRepository diffRepository,
            ImpactNoticeRepository noticeRepository,
            LogicalClock clock,
            EventLog eventLog) {
        this.snapshotService = snapshotService;
        this.binder = binder;
        this.recalculator = recalculator;
        this.taskRepository = taskRepository;
        this.bindingRepository = bindingRepository;
        this.shadowRepository = shadowRepository;
        this.diffRepository = diffRepository;
        this.noticeRepository = noticeRepository;
        this.clock = clock;
        this.eventLog = eventLog;
    }

    // ---------- 规则发布与跨午夜激活 ----------

    /** 发布规则快照；立即生效的版本同步触发存量任务影子复算与差异立案 */
    @Transactional
    public RuleSnapshot publishRule(String version, BigDecimal rate, LocalDate effectiveFrom,
            String description) {
        RuleSnapshot snapshot = snapshotService.publish(version, rate, effectiveFrom, description);
        if (snapshot.getStatus() == com.example.clearing.domain.RuleSnapshotStatus.EFFECTIVE) {
            scanWithSnapshot(snapshot, RecalcTrigger.NEW_RULE_PUBLISH,
                    "新规则 " + version + " 立即生效，存量任务影子复算");
        }
        return snapshot;
    }

    /** 调度周期入口：激活跨午夜到期的草稿快照，并对新激活版本做影子复算扫描 */
    @Transactional
    public List<RuleSnapshot> runDueActivations() {
        List<RuleSnapshot> activated = snapshotService.activateDue();
        for (RuleSnapshot snapshot : activated) {
            scanWithSnapshot(snapshot, RecalcTrigger.NEW_RULE_PUBLISH,
                    "规则 " + snapshot.getVersion() + " 跨午夜生效（生效日 "
                            + snapshot.getEffectiveFrom() + "），存量任务影子复算");
        }
        return activated;
    }

    /** 用目标快照对全部在途/已结任务做影子复算，差额非零则入差异裁决队列 */
    @Transactional
    public List<RuleDiffCase> scanWithSnapshot(RuleSnapshot target, RecalcTrigger trigger, String remark) {
        return taskRepository.findAll().stream()
                .filter(t -> t.getStatus() != TaskStatus.CANCELLED
                        && t.getStatus() != TaskStatus.CONTENT_CONFLICT)
                .filter(t -> !target.getVersion().equals(t.getRuleVersion()))
                .map(t -> openDiffForTask(t, target.getVersion(), trigger, remark))
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    // ---------- 策略 A：未执行任务随最新规则统一重算 ----------

    /**
     * 策略 A：把所有未执行（CALC 阶段且无扣款分录）的在途任务改绑最新生效规则。
     * 已冻结任务不在此列，只能通过裁决队列走更正任务路径。
     */
    @Transactional
    public List<ClearingTask> applyLatestToUnexecuted(String reason) {
        String target = snapshotService.currentEffectiveVersion();
        if ("NONE".equals(target)) {
            throw new IllegalStateException("规则快照库尚无生效版本");
        }
        List<ClearingTask> rebound = new java.util.ArrayList<>();
        for (ClearingTask task : taskRepository.findAll()) {
            boolean unexecuted = task.getStage() == Stage.CALC
                    && !recalculator.isFrozen(task)
                    && task.getStatus() != TaskStatus.CANCELLED
                    && task.getStatus() != TaskStatus.CONTENT_CONFLICT;
            if (unexecuted && !target.equals(task.getRuleVersion())) {
                // 先按旧绑定口径影子复算（基准正确），再改绑；差额为零则无需立案
                ShadowRecalc recalc = recalculator.recalc(task, target,
                        RecalcTrigger.POLICY_LATEST_REBIND, "策略 A 改绑前影子复算：" + reason);
                binder.rebindToLatest(task, reason);
                taskRepository.save(task);
                if (recalc.getDeltaAmount().signum() != 0) {
                    openCase(task, recalc, false, "策略 A 统一口径改绑，差异留痕待裁决");
                }
                rebound.add(task);
            }
        }
        return rebound;
    }

    // ---------- 差异裁决 ----------

    @Transactional
    public RuleDiffCase adjudicate(Long caseId, DiffAction action, String note) {
        RuleDiffCase diffCase = diffRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("差异案件不存在: " + caseId));
        if (diffCase.getStatus() != DiffCaseStatus.OPEN) {
            throw new IllegalStateException("差异案件 " + caseId + " 已终结（" + diffCase.getStatus()
                    + "），不能重复裁决");
        }
        ClearingTask task = taskRepository.findById(diffCase.getTaskId()).orElseThrow();
        String suffix = (note == null || note.isBlank()) ? "" : "；备注：" + note;
        switch (action) {
            case APPROVE -> doApprove(diffCase, task, suffix);
            case WITHDRAW -> doWithdraw(diffCase, task, DiffCaseStatus.WITHDRAWN,
                    Decision.RULE_DIFF_WITHDRAWN, "人工裁决撤回", suffix);
            case REJECT -> doWithdraw(diffCase, task, DiffCaseStatus.REJECTED,
                    Decision.RULE_DIFF_REJECTED, "人工裁决驳回", suffix);
            case ISSUE_CORRECTION -> doIssueCorrection(diffCase, task, suffix);
        }
        return diffRepository.save(diffCase);
    }

    /** 批准：仅未冻结任务允许；冻结任务的批准等同于静默覆盖，必须拒绝 */
    private void doApprove(RuleDiffCase diffCase, ClearingTask task, String suffix) {
        if (diffCase.isFrozen()) {
            recordSilentOverrideRejection(diffCase, task, suffix);
            throw new IllegalStateException("业务键 " + task.getBusinessKey()
                    + " 的结果已回传冻结，禁止静默覆盖；请改用 ISSUE_CORRECTION 另发更正任务");
        }
        if (!diffCase.getTargetVersion().equals(task.getRuleVersion())) {
            binder.rebindToLatest(task, "裁决批准按新规则重算" + suffix);
            taskRepository.save(task);
        }
        diffCase.setStatus(DiffCaseStatus.APPROVED);
        diffCase.setResolutionNote("批准按规则 " + diffCase.getTargetVersion() + " 重算" + suffix);
        diffCase.setResolvedAt(Instant.now());
        eventLog.record(task, Decision.RULE_DIFF_APPROVED,
                "差异案件批准：未执行任务按规则 " + diffCase.getTargetVersion() + " 重算" + suffix);
        taskRepository.save(task);
        discardShadow(diffCase);
    }

    /** 撤回/驳回：未执行任务恢复入队时点绑定；冻结案件原本就未改动，直接关闭 */
    private void doWithdraw(RuleDiffCase diffCase, ClearingTask task, DiffCaseStatus closeStatus,
            Decision decision, String label, String suffix) {
        if (!diffCase.isFrozen()
                && diffCase.getTargetVersion().equals(task.getRuleVersion())
                && !task.getRuleVersion().equals(task.getBaselineRuleVersion())) {
            int seq = (int) bindingRepository.countByTaskId(task.getId());
            com.example.clearing.domain.TaskRuleBinding revert =
                    new com.example.clearing.domain.TaskRuleBinding(task.getId(), seq,
                            task.getBaselineRuleVersion(), TaskRuleBinder.KIND_REBIND, task.getStage(),
                            label + "：恢复入队时点规则版本 " + task.getBaselineRuleVersion() + suffix);
            bindingRepository.save(revert);
            task.setRuleVersion(task.getBaselineRuleVersion());
            eventLog.record(task, decision,
                    label + "：规则绑定恢复为入队时点版本 " + task.getBaselineRuleVersion()
                            + "（版本链保留全部轨迹）" + suffix);
            taskRepository.save(task);
        } else {
            eventLog.record(task, decision,
                    label + "：任务结果依据未改动，差异案件关闭" + suffix);
            taskRepository.save(task);
        }
        diffCase.setStatus(closeStatus);
        diffCase.setResolutionNote(label + suffix);
        diffCase.setResolvedAt(Instant.now());
        discardShadow(diffCase);
    }

    /** 策略 B：另发更正任务，原任务、原分录、原发布行全部保留 */
    private void doIssueCorrection(RuleDiffCase diffCase, ClearingTask origin, String suffix) {
        ClearingTask correction = buildCorrectionTask(diffCase, origin);
        correction = taskRepository.save(correction);
        binder.bindCorrection(correction, diffCase.getTargetVersion(),
                "差异案件 " + diffCase.getId() + " 裁决另发更正任务" + suffix);
        correction = taskRepository.save(correction);

        origin.setRevisedById(correction.getId());
        eventLog.record(origin, Decision.REVISED_TASK_ISSUED,
                "策略 B：另发更正任务 " + correction.getId() + "（清单版本 " + correction.getBillVersion()
                        + "，规则 " + diffCase.getTargetVersion() + "，补正日期 "
                        + correction.getCorrectionDate() + "）；原任务与已回传结果保留不动" + suffix);
        taskRepository.save(origin);

        diffCase.setStatus(DiffCaseStatus.CORRECTION_ISSUED);
        diffCase.setCorrectionTaskId(correction.getId());
        diffCase.setResolutionNote("另发更正任务 " + correction.getId() + suffix);
        diffCase.setResolvedAt(Instant.now());

        emitNotices(diffCase);
        eventLog.record(correction, Decision.IMPACT_NOTIFIED,
                "影响通知已随更正任务发出（基金侧/医疗机构侧），差额 " + diffCase.getDeltaAmount() + suffix);
    }

    private ClearingTask buildCorrectionTask(RuleDiffCase diffCase, ClearingTask origin) {
        int nextSeq = taskRepository.findAll().stream()
                .filter(t -> origin.getBusinessKey().equals(t.getBusinessKey()))
                .mapToInt(ClearingTask::getCorrectionSeq)
                .max().orElse(0) + 1;
        ClearingTask correction = new ClearingTask();
        correction.setBatchId("CORRECTION-CASE-" + diffCase.getId());
        correction.setClearingDate(origin.getClearingDate());
        correction.setCorrectionDate(clock.today());
        correction.setBusinessKey(origin.getBusinessKey());
        correction.setBillVersion(origin.getBillVersion() + "-r" + nextSeq);
        correction.setContentHash(origin.getContentHash());
        correction.setSourceType(origin.getSourceType());
        correction.setDependencyCode(TaskRegistrar.DEP_RULE);
        correction.setDependencyVersion("RULE=" + diffCase.getTargetVersion()
                + "（更正自 " + diffCase.getBaseVersion() + "）");
        correction.setAmount(origin.getAmount());
        correction.setPriority(origin.getPriority());
        correction.setStage(Stage.CALC);
        correction.setPayload(origin.getPayload());
        correction.setStatus(TaskStatus.READY);
        correction.setNextAttemptAt(Instant.now());
        correction.setCorrectionOfId(origin.getId());
        correction.setCorrectionSeq(nextSeq);
        return correction;
    }

    private void emitNotices(RuleDiffCase diffCase) {
        for (String recipient : List.of("FUND", "HOSPITAL")) {
            ImpactNotice notice = new ImpactNotice();
            notice.setDiffCaseId(diffCase.getId());
            notice.setBusinessKey(diffCase.getBusinessKey());
            notice.setRecipient(recipient);
            notice.setFromVersion(diffCase.getBaseVersion());
            notice.setToVersion(diffCase.getTargetVersion());
            notice.setOriginalAmount(diffCase.getOriginalAmount());
            notice.setRevisedAmount(diffCase.getShadowAmount());
            notice.setDeltaAmount(diffCase.getDeltaAmount());
            notice.setStatus(NoticeStatus.NOTIFIED);
            notice.setMessage("规则口径变更影响通知：原口径 " + diffCase.getBaseVersion()
                    + " 金额 " + diffCase.getOriginalAmount() + "，新口径 " + diffCase.getTargetVersion()
                    + " 金额 " + diffCase.getShadowAmount() + "，差额 " + diffCase.getDeltaAmount()
                    + "（" + (diffCase.getDeltaAmount().signum() > 0 ? "补付" : "追回") + "）");
            noticeRepository.save(notice);
        }
    }

    // ---------- 影子复算与立案 ----------

    private java.util.Optional<RuleDiffCase> openDiffForTask(ClearingTask task, String targetVersion,
            RecalcTrigger trigger, String remark) {
        boolean alreadyOpen = diffRepository.findByTaskIdOrderByIdDesc(task.getId()).stream()
                .anyMatch(c -> c.getStatus() == DiffCaseStatus.OPEN
                        && c.getTargetVersion().equals(targetVersion));
        if (alreadyOpen) {
            return java.util.Optional.empty();
        }
        ShadowRecalc recalc = recalculator.recalc(task, targetVersion, trigger, remark);
        if (recalc.getDeltaAmount().signum() == 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(openCase(task, recalc, recalculator.isFrozen(task),
                trigger == RecalcTrigger.NEW_RULE_PUBLISH
                        ? "规则 " + targetVersion + " 发布后影子复算发现差额"
                        : "影子复算发现差额"));
    }

    private RuleDiffCase openCase(ClearingTask task, ShadowRecalc recalc, boolean frozen, String reason) {
        var existing = diffRepository.findByTaskIdOrderByIdDesc(task.getId()).stream()
                .filter(c -> c.getStatus() == DiffCaseStatus.OPEN
                        && c.getTargetVersion().equals(recalc.getTargetVersion()))
                .findFirst();
        if (existing.isPresent()) {
            recalc.setDiffCaseId(existing.get().getId());
            shadowRepository.save(recalc);
            return existing.get();
        }
        RuleDiffCase diffCase = new RuleDiffCase();
        diffCase.setTaskId(task.getId());
        diffCase.setBusinessKey(task.getBusinessKey());
        diffCase.setBillVersion(task.getBillVersion());
        diffCase.setBaseVersion(recalc.getBaseVersion());
        diffCase.setTargetVersion(recalc.getTargetVersion());
        diffCase.setOriginalAmount(recalc.getOriginalAmount());
        diffCase.setShadowAmount(recalc.getShadowAmount());
        diffCase.setDeltaAmount(recalc.getDeltaAmount());
        diffCase.setFrozen(frozen);
        diffCase.setStatus(DiffCaseStatus.OPEN);
        diffCase.setOpenedReason(reason);
        RuleDiffCase saved = diffRepository.save(diffCase);
        recalc.setDiffCaseId(saved.getId());
        shadowRepository.save(recalc);
        eventLog.record(task, Decision.RULE_DIFF_OPEN,
                "差异裁决队列立案（案件 " + saved.getId() + "）：规则 " + recalc.getBaseVersion()
                        + "→" + recalc.getTargetVersion() + "，原金额 " + recalc.getOriginalAmount()
                        + "，影子金额 " + recalc.getShadowAmount() + "，差额 " + recalc.getDeltaAmount()
                        + (frozen ? "；任务已冻结，禁止静默覆盖，只能更正" : "；任务未执行，可改绑重算或撤回"));
        taskRepository.save(task);
        return saved;
    }

    private void discardShadow(RuleDiffCase diffCase) {
        shadowRepository.findAll().stream()
                .filter(s -> s.getDiffCaseId() != null && s.getDiffCaseId().equals(diffCase.getId()))
                .forEach(s -> {
                    s.setStatus(ShadowRecalcStatus.DISCARDED);
                    shadowRepository.save(s);
                });
    }

    /**
     * 静默覆盖拒绝留痕：记录 RESULT_FROZEN_REJECTED 事件但不改案件状态、不动发布册，
     * 事务可正常提交。供演示/批处理在不触发回滚的情况下核验“不能静默覆盖”。
     */
    @Transactional
    public RuleDiffCase recordSilentOverrideRejection(Long caseId, String note) {
        RuleDiffCase diffCase = requireCase(caseId);
        ClearingTask task = taskRepository.findById(diffCase.getTaskId()).orElseThrow();
        recordSilentOverrideRejection(diffCase, task,
                (note == null || note.isBlank()) ? "" : "；备注：" + note);
        taskRepository.save(task);
        return diffCase;
    }

    private void recordSilentOverrideRejection(RuleDiffCase diffCase, ClearingTask task, String suffix) {
        eventLog.record(task, Decision.RESULT_FROZEN_REJECTED,
                "试图把已回传结果按规则 " + diffCase.getTargetVersion()
                        + " 静默覆盖：结果发布册金额已冻结，拒绝改写，只能另发更正任务" + suffix);
    }

    /** 人工发起：对单个任务按目标版本影子复算，差额非零则立案 */
    @Transactional
    public java.util.Optional<RuleDiffCase> recalcSingle(Long taskId, String targetVersion) {
        ClearingTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        snapshotService.requireVersion(targetVersion);
        return openDiffForTask(task, targetVersion, RecalcTrigger.MANUAL,
                "人工发起影子复算（目标版本 " + targetVersion + "）");
    }

    // ---------- 查询 ----------

    public List<RuleDiffCase> openCases() {
        return diffRepository.findByStatus(DiffCaseStatus.OPEN);
    }

    public List<RuleDiffCase> allCases() {
        return diffRepository.findAll();
    }

    public RuleDiffCase requireCase(Long id) {
        return diffRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("差异案件不存在: " + id));
    }

    public List<ImpactNotice> allNotices() {
        return noticeRepository.findAll();
    }

    public List<ImpactNotice> noticesOfCase(Long caseId) {
        return noticeRepository.findByDiffCaseId(caseId);
    }

    @Transactional
    public ImpactNotice acknowledgeNotice(Long noticeId) {
        ImpactNotice notice = noticeRepository.findById(noticeId)
                .orElseThrow(() -> new IllegalArgumentException("通知不存在: " + noticeId));
        notice.setStatus(NoticeStatus.ACKNOWLEDGED);
        notice.setAcknowledgedAt(Instant.now());
        return noticeRepository.save(notice);
    }
}
