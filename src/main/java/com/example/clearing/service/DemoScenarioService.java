package com.example.clearing.service;

import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.DepHealth;
import com.example.clearing.domain.DiffCaseStatus;
import com.example.clearing.domain.EntryStatus;
import com.example.clearing.domain.EntryType;
import com.example.clearing.domain.FailureType;
import com.example.clearing.domain.LedgerEntry;
import com.example.clearing.domain.PublicationStatus;
import com.example.clearing.domain.RuleDiffCase;
import com.example.clearing.domain.RuleSnapshot;
import com.example.clearing.domain.RuleSnapshotStatus;
import com.example.clearing.domain.SourceType;
import com.example.clearing.domain.StormReport;
import com.example.clearing.domain.StormLevel;
import com.example.clearing.domain.TaskStatus;
import com.example.clearing.domain.TokenBucket;
import com.example.clearing.dto.ScenarioResult;
import com.example.clearing.repo.ClearingTaskRepository;
import com.example.clearing.repo.FailureRecordRepository;
import com.example.clearing.repo.ImpactNoticeRepository;
import com.example.clearing.repo.LedgerEntryRepository;
import com.example.clearing.repo.QuotaGrantRepository;
import com.example.clearing.repo.ResultPublicationRepository;
import com.example.clearing.repo.RuleDiffCaseRepository;
import com.example.clearing.repo.RuleSnapshotRepository;
import com.example.clearing.repo.ShadowRecalcRepository;
import com.example.clearing.repo.StormReportRepository;
import com.example.clearing.repo.TaskEventRepository;
import com.example.clearing.repo.TaskRuleBindingRepository;
import com.example.clearing.service.RuleGovernanceService.DiffAction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验收样本：医院批次、药店回传、目录冲突、接口降级与配额恢复、
 * 基金回执延迟、内容冲突、风暴观测。每个场景通过真实服务执行并输出断言。
 */
@Service
public class DemoScenarioService {

    private static final LocalDate CLEARING_DATE = LocalDate.of(2026, 11, 23);

    private final TaskRegistrar registrar;
    private final ClearingScheduler scheduler;
    private final TokenBucketService bucketService;
    private final DependencyGraphService graphService;
    private final DownstreamSimulator simulator;
    private final CatalogService catalogService;
    private final LedgerService ledgerService;
    private final ManualResolutionService manualResolution;
    private final StormObserver stormObserver;
    private final ClearingTaskRepository taskRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final TaskEventRepository eventRepository;
    private final FailureRecordRepository failureRepository;
    private final StormReportRepository stormRepository;
    private final QuotaGrantRepository quotaRepository;
    private final ImpactNoticeRepository noticeRepository;
    private final ResultPublicationRepository publicationRepository;
    private final RuleDiffCaseRepository diffCaseRepository;
    private final ShadowRecalcRepository shadowRepository;
    private final TaskRuleBindingRepository bindingRepository;
    private final RuleSnapshotRepository snapshotRepository;
    private final RuleSnapshotService snapshotService;
    private final RuleGovernanceService governance;
    private final LogicalClock clock;

    public DemoScenarioService(TaskRegistrar registrar,
            ClearingScheduler scheduler,
            TokenBucketService bucketService,
            DependencyGraphService graphService,
            DownstreamSimulator simulator,
            CatalogService catalogService,
            LedgerService ledgerService,
            ManualResolutionService manualResolution,
            StormObserver stormObserver,
            ClearingTaskRepository taskRepository,
            LedgerEntryRepository ledgerRepository,
            TaskEventRepository eventRepository,
            FailureRecordRepository failureRepository,
            StormReportRepository stormRepository,
            QuotaGrantRepository quotaRepository,
            ImpactNoticeRepository noticeRepository,
            ResultPublicationRepository publicationRepository,
            RuleDiffCaseRepository diffCaseRepository,
            ShadowRecalcRepository shadowRepository,
            TaskRuleBindingRepository bindingRepository,
            RuleSnapshotRepository snapshotRepository,
            RuleSnapshotService snapshotService,
            RuleGovernanceService governance,
            LogicalClock clock) {
        this.registrar = registrar;
        this.scheduler = scheduler;
        this.bucketService = bucketService;
        this.graphService = graphService;
        this.simulator = simulator;
        this.catalogService = catalogService;
        this.ledgerService = ledgerService;
        this.manualResolution = manualResolution;
        this.stormObserver = stormObserver;
        this.taskRepository = taskRepository;
        this.ledgerRepository = ledgerRepository;
        this.eventRepository = eventRepository;
        this.failureRepository = failureRepository;
        this.stormRepository = stormRepository;
        this.quotaRepository = quotaRepository;
        this.noticeRepository = noticeRepository;
        this.publicationRepository = publicationRepository;
        this.diffCaseRepository = diffCaseRepository;
        this.shadowRepository = shadowRepository;
        this.bindingRepository = bindingRepository;
        this.snapshotRepository = snapshotRepository;
        this.snapshotService = snapshotService;
        this.governance = governance;
        this.clock = clock;
    }

    /** 演练复位：清空业务数据，恢复依赖健康、令牌桶、模拟器与规则快照基线 */
    @Transactional
    public void reset() {
        eventRepository.deleteAll();
        failureRepository.deleteAll();
        stormRepository.deleteAll();
        quotaRepository.deleteAll();
        noticeRepository.deleteAll();
        publicationRepository.deleteAll();
        diffCaseRepository.deleteAll();
        shadowRepository.deleteAll();
        ledgerRepository.deleteAll();
        bindingRepository.deleteAll();
        taskRepository.deleteAll();
        snapshotRepository.deleteAll();
        bucketService.resetAll();
        graphService.resetHealth();
        simulator.reset();
        stormObserver.resetWindow();
        clock.reset();
        snapshotService.publish("2026.11", new java.math.BigDecimal("0.7000"), clock.today(),
                "基线待遇规则：统筹支付比例 0.70（演练复位重建）");
    }

    @Transactional
    public ScenarioResult run(String name) {
        return switch (name) {
            case "hospital-batch" -> hospitalBatch();
            case "pharmacy-return" -> pharmacyReturn();
            case "catalog-conflict" -> catalogConflict();
            case "rule-degradation" -> ruleDegradation();
            case "fund-pending-confirm" -> fundPendingConfirm();
            case "content-conflict" -> contentConflict();
            case "storm-watch" -> stormWatch();
            case "rule-midnight-effective" -> ruleMidnightEffective();
            case "rule-diff-withdraw" -> ruleDiffWithdraw();
            case "two-batch-correction" -> twoBatchCorrection();
            default -> throw new IllegalArgumentException("未知场景: " + name
                    + "，可选：hospital-batch, pharmacy-return, catalog-conflict, rule-degradation, "
                    + "fund-pending-confirm, content-conflict, storm-watch, "
                    + "rule-midnight-effective, rule-diff-withdraw, two-batch-correction");
        };
    }

    /** 场景 1：医院结算批次正常清算 */
    private ScenarioResult hospitalBatch() {
        ScenarioResult s = new ScenarioResult("hospital-batch");
        reset();
        s.step("登记 3 笔医院结算清单（金额 12000 / 45000 / 8000）");
        ClearingTask t1 = reg("HOSP-20261123-001", "v1", SourceType.HOSPITAL, "12000.00",
                payload("A-001", 2, "100.00"));
        ClearingTask t2 = reg("HOSP-20261123-002", "v1", SourceType.HOSPITAL, "45000.00",
                payload("B-002", 1, "300.00"));
        ClearingTask t3 = reg("HOSP-20261123-003", "v1", SourceType.HOSPITAL, "8000.00",
                payload("D-004", 5, "20.00"));
        s.step("执行 2 个调度周期");
        scheduler.tick();
        scheduler.tick();
        s.check(status(t1) == TaskStatus.CONFIRMED, "任务1 清算完成 CONFIRMED，实际=" + status(t1));
        s.check(status(t2) == TaskStatus.CONFIRMED, "任务2 清算完成 CONFIRMED，实际=" + status(t2));
        s.check(status(t3) == TaskStatus.CONFIRMED, "任务3 清算完成 CONFIRMED，实际=" + status(t3));
        s.check(taskRepository.findById(t2.getId()).orElseThrow().getPriority() == 1,
                "金额 45000 的任务被赋予 MEDIUM 金额优先级");
        long debits = ledgerRepository.findAll().stream()
                .filter(e -> e.getEntryType() == EntryType.CLEAR_DEBIT && e.getStatus() == EntryStatus.POSTED)
                .count();
        long returns = ledgerRepository.findAll().stream()
                .filter(e -> e.getEntryType() == EntryType.FUND_RETURN && e.getStatus() == EntryStatus.CONFIRMED)
                .count();
        s.check(debits == 3 && returns == 3, "幂等账本：3 笔扣款 POSTED + 3 笔回传 CONFIRMED，实际="
                + debits + "/" + returns);
        return s;
    }

    /** 场景 2：药店购药回传清算 */
    private ScenarioResult pharmacyReturn() {
        ScenarioResult s = new ScenarioResult("pharmacy-return");
        reset();
        s.step("登记 2 笔药店购药回传记录");
        ClearingTask t1 = reg("PHARM-0001", "v1", SourceType.PHARMACY, "1500.00", payload("A-001", 1, "50.00"));
        ClearingTask t2 = reg("PHARM-0002", "v1", SourceType.PHARMACY, "2300.00", payload("B-002", 3, "30.00"));
        s.step("执行 2 个调度周期");
        scheduler.tick();
        scheduler.tick();
        s.check(status(t1) == TaskStatus.CONFIRMED && status(t2) == TaskStatus.CONFIRMED,
                "药店回传任务全部 CONFIRMED，实际=" + status(t1) + "/" + status(t2));
        return s;
    }

    /** 场景 3：目录缺失 → 目录等待 → 目录同步后恢复 */
    private ScenarioResult catalogConflict() {
        ScenarioResult s = new ScenarioResult("catalog-conflict");
        reset();
        s.step("登记 1 笔含未知目录编码 ZZZ-999 的医院清单");
        ClearingTask task = reg("HOSP-CAT-001", "v1", SourceType.HOSPITAL, "9000.00",
                payload("ZZZ-999", 1, "100.00"));
        s.step("执行 1 个调度周期（目录匹配失败）");
        scheduler.tick();
        s.check(status(task) == TaskStatus.WAITING_DEPENDENCY, "任务进入目录等待 WAITING_DEPENDENCY，实际="
                + status(task));
        s.check(bucketService.get(TaskRegistrar.DEP_RULE).getTotalConsumed() == 0,
                "目录等待期间未消耗任何尝试令牌");
        s.check(failureRepository.findAll().stream()
                .anyMatch(f -> f.getFailureType() == FailureType.CATALOG_WAIT),
                "失败判定器记录 CATALOG_WAIT");
        s.step("目录同步：补齐目录项 ZZZ-999");
        catalogService.add("ZZZ-999", "演练新药", "2026.11");
        s.step("再执行 2 个调度周期");
        scheduler.tick();
        scheduler.tick();
        s.check(status(task) == TaskStatus.CONFIRMED, "目录恢复后任务自动提升并清算完成 CONFIRMED，实际="
                + status(task));
        return s;
    }

    /** 场景 4：待遇规则接口降级 → 桶冻结 → 恢复后按剩余预算渐进投放（不补发） */
    private ScenarioResult ruleDegradation() {
        ScenarioResult s = new ScenarioResult("rule-degradation");
        reset();
        s.step("登记批次 B1 共 5 笔医院清单并执行 1 个调度周期（RULE 桶容量 6，消耗 5）");
        for (int i = 1; i <= 5; i++) {
            reg("HOSP-B1-" + i, "v1", SourceType.HOSPITAL, "1000.00", payload("A-001", 1, "10.00"));
        }
        scheduler.tick();
        TokenBucket ruleBucket = bucketService.get(TaskRegistrar.DEP_RULE);
        s.check(ruleBucket.getTokens() == 1, "B1 投放后 RULE 桶余额=1，实际=" + ruleBucket.getTokens());

        s.step("待遇规则服务 DOWN（接口降级），登记批次 B2 共 5 笔");
        graphService.updateHealth(TaskRegistrar.DEP_RULE, DepHealth.DOWN, "演练：规则服务不可用");
        for (int i = 1; i <= 5; i++) {
            reg("HOSP-B2-" + i, "v1", SourceType.HOSPITAL, "1000.00", payload("A-001", 1, "10.00"));
        }
        long refilledAtDown = bucketService.get(TaskRegistrar.DEP_RULE).getTotalRefilled();
        s.step("故障期间执行 2 个调度周期");
        scheduler.tick();
        scheduler.tick();
        TokenBucket frozen = bucketService.get(TaskRegistrar.DEP_RULE);
        s.check(frozen.getTokens() == 1 && frozen.getTotalRefilled() == refilledAtDown,
                "故障期间桶冻结：余额保持 1、零补充（不放行、不补充），实际余额=" + frozen.getTokens()
                        + " 累计补充=" + frozen.getTotalRefilled());
        boolean b2Waiting = taskRepository.findAll().stream()
                .filter(t -> t.getBusinessKey().startsWith("HOSP-B2-"))
                .allMatch(t -> t.getStatus() == TaskStatus.READY
                        && t.getLastDecision() == com.example.clearing.domain.Decision.WAIT_DEPENDENCY_DOWN);
        s.check(b2Waiting, "B2 任务保持就绪等待，决策记录为 WAIT_DEPENDENCY_DOWN");
        s.check(quotaRepository.findAll().stream()
                .anyMatch(q -> q.getDependencyCode().equals(TaskRegistrar.DEP_RULE) && q.getGranted() == 0),
                "协调器配额证据：RULE_ENGINE 故障期间配额置零（故障隔离，仅收紧本依赖队列）");

        s.step("待遇规则服务恢复健康，执行 1 个调度周期");
        graphService.updateHealth(TaskRegistrar.DEP_RULE, DepHealth.HEALTHY, "演练：规则服务恢复");
        scheduler.tick();
        TokenBucket afterHeal = bucketService.get(TaskRegistrar.DEP_RULE);
        s.check(afterHeal.getTotalRefilled() == refilledAtDown + 2,
                "恢复后仅按剩余预算渐进补充（+2/周期），不补发故障期间尝试：累计补充="
                        + afterHeal.getTotalRefilled() + "（故障前 " + refilledAtDown + "）");
        long b2CalcDone = taskRepository.findAll().stream()
                .filter(t -> t.getBusinessKey().startsWith("HOSP-B2-"))
                .filter(t -> t.getStage() != com.example.clearing.domain.Stage.CALC)
                .count();
        s.check(b2CalcDone == 3, "恢复后首个周期仅投放 3 笔（冻结余额 1 + 补充 2），实际=" + b2CalcDone);

        s.step("继续调度直至全部完成");
        tickUntil(15, () -> taskRepository.findAll().stream()
                .allMatch(t -> t.getStatus() == TaskStatus.CONFIRMED));
        s.check(taskRepository.findAll().stream().allMatch(t -> t.getStatus() == TaskStatus.CONFIRMED),
                "B1+B2 共 10 笔最终全部 CONFIRMED");
        return s;
    }

    /** 场景 5：基金回传已受理但回执延迟 → 账本待确认，按操作号查询，不重复扣款 */
    private ScenarioResult fundPendingConfirm() {
        ScenarioResult s = new ScenarioResult("fund-pending-confirm");
        reset();
        s.step("基金网关设为 DELAYED（受理成功，回执延迟 300s）");
        simulator.setFundMode(DownstreamSimulator.FundMode.DELAYED);
        simulator.setFundDelaySeconds(300);
        ClearingTask task = reg("HOSP-FUND-001", "v1", SourceType.HOSPITAL, "20000.00",
                payload("C-003", 1, "500.00"));
        s.step("执行 1 个调度周期（待遇计算 + 基金回传受理）");
        scheduler.tick();
        s.check(status(task) == TaskStatus.PENDING_CONFIRM, "任务进入待确认 PENDING_CONFIRM，实际=" + status(task));
        String opNo = LedgerService.fundReturnOpNo(taskRepository.findById(task.getId()).orElseThrow());
        LedgerEntry entry = ledgerService.byOperationNo(opNo).orElseThrow();
        s.check(entry.getStatus() == EntryStatus.PENDING_CONFIRM && entry.isGatewayAccepted(),
                "回传分录 " + opNo + " 保持待确认且网关已受理");
        long consumedAfterAccept = bucketService.get(TaskRegistrar.DEP_FUND).getTotalConsumed();

        s.step("回执延迟期间再执行 2 个调度周期");
        scheduler.tick();
        scheduler.tick();
        s.check(bucketService.get(TaskRegistrar.DEP_FUND).getTotalConsumed() == consumedAfterAccept,
                "待确认期间仅按操作号查询回执，未取得新的扣款令牌（FUND 桶消耗保持 "
                        + consumedAfterAccept + "）");
        long entries = ledgerRepository.findAll().stream()
                .filter(e -> e.getTaskId().equals(task.getId())).count();
        s.check(entries == 2, "幂等账本对该清单仅 2 条分录（1 扣款 + 1 回传），无重复记账，实际=" + entries);
        s.check(status(task) == TaskStatus.PENDING_CONFIRM, "回执未到，任务保持待确认");

        s.step("回执到达（人工补确认），再执行 1 个调度周期");
        simulator.confirmManually(opNo);
        scheduler.tick();
        s.check(status(task) == TaskStatus.CONFIRMED, "回执确认后任务完成 CONFIRMED，实际=" + status(task));
        s.check(ledgerService.byOperationNo(opNo).orElseThrow().getStatus() == EntryStatus.CONFIRMED,
                "回传分录按操作号确认 CONFIRMED");
        return s;
    }

    /** 场景 6：同业务键不同内容重复到达 → 内容冲突，不发放令牌 */
    private ScenarioResult contentConflict() {
        ScenarioResult s = new ScenarioResult("content-conflict");
        reset();
        s.step("登记清单 KEY-CC-001 v1（内容 P1）");
        TaskRegistrar.RegisterResult first = registrar.register("BATCH-CC", CLEARING_DATE, "KEY-CC-001",
                "v1", SourceType.HOSPITAL, new BigDecimal("5000.00"), payload("A-001", 1, "100.00"));
        s.check(first.outcome() == TaskRegistrar.Outcome.CREATED, "首次登记成功 CREATED");
        s.step("同业务键同版本但内容不同（P2）重复到达");
        TaskRegistrar.RegisterResult second = registrar.register("BATCH-CC2", CLEARING_DATE, "KEY-CC-001",
                "v1", SourceType.HOSPITAL, new BigDecimal("5000.00"), payload("A-001", 2, "100.00"));
        s.check(second.outcome() == TaskRegistrar.Outcome.CONTENT_CONFLICT,
                "登记器生成内容冲突 CONTENT_CONFLICT，实际=" + second.outcome());
        s.check(bucketService.get(TaskRegistrar.DEP_RULE).getTotalConsumed() == 0,
                "内容冲突未发放任何尝试令牌");
        s.check(failureRepository.findAll().stream()
                .anyMatch(f -> f.getFailureType() == FailureType.CONTENT_MISMATCH),
                "失败判定器记录 CONTENT_MISMATCH");
        s.step("人工裁定：保留原清单，冲突登记作废");
        manualResolution.resolve(second.task().getId(), ManualResolutionService.Action.KEEP_ORIGINAL, "验收裁定");
        s.check(status(second.task()) == TaskStatus.CANCELLED, "冲突登记已作废 CANCELLED");
        s.step("执行调度，原清单正常清算");
        scheduler.tick();
        scheduler.tick();
        s.check(status(first.task()) == TaskStatus.CONFIRMED, "原清单清算完成 CONFIRMED，实际=" + status(first.task()));
        s.check(ledgerRepository.findByBusinessKey("KEY-CC-001").size() == 2,
                "账本仅含原清单的 2 条分录（冲突内容未入账）");
        return s;
    }

    /** 场景 7：规则接口连续超时 → 失败聚集触发风暴观测 */
    private ScenarioResult stormWatch() {
        ScenarioResult s = new ScenarioResult("storm-watch");
        reset();
        s.step("待遇规则服务设为 TIMEOUT（调用即超时），登记 6 笔任务");
        simulator.setRuleMode(DownstreamSimulator.RuleMode.TIMEOUT);
        for (int i = 1; i <= 6; i++) {
            reg("HOSP-S-" + i, "v1", SourceType.HOSPITAL, "2000.00", payload("A-001", 1, "20.00"));
        }
        s.step("执行 1 个调度周期（6 次尝试全部瞬时失败）");
        scheduler.tick();
        StormReport latest = stormObserver.latest().stream()
                .filter(r -> r.getDependencyCode().equals(TaskRegistrar.DEP_RULE))
                .findFirst().orElseThrow();
        s.check(latest.getStormLevel() == StormLevel.STORM,
                "风暴观测器判定 RULE_ENGINE 失败聚集为 STORM，实际=" + latest.getStormLevel()
                        + "（窗口失败 " + latest.getFailuresWindow() + " 次）");
        boolean allRetry = taskRepository.findAll().stream()
                .allMatch(t -> t.getStatus() == TaskStatus.RETRY_SCHEDULED);
        s.check(allRetry, "全部任务进入退避重试 RETRY_SCHEDULED");
        s.step("接口恢复，等待退避窗口（1.2s）后继续调度直至完成");
        simulator.setRuleMode(DownstreamSimulator.RuleMode.NORMAL);
        sleep(1200);
        tickUntil(15, () -> taskRepository.findAll().stream()
                .allMatch(t -> t.getStatus() == TaskStatus.CONFIRMED));
        s.check(taskRepository.findAll().stream().allMatch(t -> t.getStatus() == TaskStatus.CONFIRMED),
                "风暴平息后全部任务最终 CONFIRMED");
        s.check(stormRepository.count() > 0, "风暴报告已留存供复盘");
        return s;
    }

    // ---------- 规则治理三场景 ----------

    /** 场景 8：规则跨午夜生效——队列中旧清单与新清单适用不同待遇规则，金额差异留痕，冻结结果不可覆盖 */
    private ScenarioResult ruleMidnightEffective() {
        ScenarioResult s = new ScenarioResult("rule-midnight-effective");
        reset();
        s.step("11-23：快照库仅生效 2026.11（统筹比例 0.70），登记旧清单 RULE-MID-OLD 并完成清算");
        ClearingTask oldTask = reg("RULE-MID-OLD", "v1", SourceType.HOSPITAL, "1000.00",
                payload("A-001", 10, "100.00"));
        scheduler.tick();
        ClearingTask oldDone = taskRepository.findById(oldTask.getId()).orElseThrow();
        BigDecimal oldAmount = ledgerRepository.findByOperationNo(LedgerService.debitOpNo(oldDone))
                .orElseThrow().getAmount();
        s.check(oldDone.getStatus() == TaskStatus.CONFIRMED && oldAmount.compareTo(new BigDecimal("700.00")) == 0,
                "旧清单按 2026.11 清算完成，统筹金额=700.00（1000×0.70），实际=" + oldAmount);
        s.check("2026.11".equals(oldDone.getRuleVersion()),
                "旧清单入队绑定规则版本 2026.11，实际=" + oldDone.getRuleVersion());

        s.step("发布新规则 2026.12（比例 0.75），生效日为次日 2026-11-24，入库为 DRAFT 草稿");
        RuleSnapshot draft = governance.publishRule("2026.12", new BigDecimal("0.7500"),
                LocalDate.of(2026, 11, 24), "跨午夜生效演练：统筹比例上调至 0.75");
        s.check(draft.getStatus() == RuleSnapshotStatus.DRAFT,
                "未到生效日的新规则状态 DRAFT，实际=" + draft.getStatus());
        s.check("2026.11".equals(snapshotService.currentEffectiveVersion()),
                "午夜前当前生效版本仍为 2026.11，实际=" + snapshotService.currentEffectiveVersion());

        s.step("午夜前再登记新清单 RULE-MID-NEW（与旧清单同在队列），入队时点绑定 2026.11");
        ClearingTask queued = reg("RULE-MID-NEW", "v1", SourceType.HOSPITAL, "1000.00",
                payload("A-001", 10, "100.00"));
        s.check("2026.11".equals(taskRepository.findById(queued.getId()).orElseThrow().getRuleVersion()),
                "午夜前入队任务绑定 2026.11");

        s.step("逻辑时钟跨午夜推进到 2026-11-24，调度周期激活到期草稿");
        clock.advanceDays(1);
        List<RuleSnapshot> activated = governance.runDueActivations();
        s.check(activated.size() == 1 && "2026.12".equals(activated.get(0).getVersion()),
                "跨午夜后 2026.12 自动激活 EFFECTIVE，实际="
                        + activated.stream().map(RuleSnapshot::getVersion).toList());
        s.check("2026.12".equals(snapshotService.currentEffectiveVersion()),
                "当前生效版本切换为 2026.12，实际=" + snapshotService.currentEffectiveVersion());
        RuleSnapshot oldSnap = snapshotService.find("2026.11").orElseThrow();
        s.check(oldSnap.getStatus() == RuleSnapshotStatus.SUPERSEDED
                        && "2026.11".equals(draft.getPreviousVersion()),
                "版本链：2026.11 置 SUPERSEDED 但快照保留，2026.12.previousVersion=2026.11，实际链="
                        + oldSnap.getStatus() + "->" + draft.getPreviousVersion());

        s.step("影子复算器对存量任务只算不写账：旧清单差额 50.00 进入差异裁决队列");
        RuleDiffCase oldCase = governance.openCases().stream()
                .filter(c -> "RULE-MID-OLD".equals(c.getBusinessKey())).findFirst().orElseThrow();
        s.check(oldCase.isFrozen() && oldCase.getDeltaAmount().compareTo(new BigDecimal("50.00")) == 0,
                "旧清单案件 frozen=true、差额=50.00（750-700），实际 frozen=" + oldCase.isFrozen()
                        + " delta=" + oldCase.getDeltaAmount());

        s.step("策略 A：未执行的新清单随最新规则统一重算（改绑留痕，初始绑定不删除）");
        List<ClearingTask> rebound = governance.applyLatestToUnexecuted("跨午夜新规则生效，统一口径");
        ClearingTask reboundTask = taskRepository.findById(queued.getId()).orElseThrow();
        s.check(rebound.size() == 1 && "2026.12".equals(reboundTask.getRuleVersion())
                        && "2026.11".equals(reboundTask.getBaselineRuleVersion()),
                "仅 1 个未执行任务改绑 2026.12，初始绑定保留 2026.11，实际="
                        + reboundTask.getRuleVersion() + "/" + reboundTask.getBaselineRuleVersion());

        s.step("执行调度，新清单按 2026.12 清算，与旧清单适用规则不同");
        scheduler.tick();
        BigDecimal newAmount = ledgerRepository.findByOperationNo(
                LedgerService.debitOpNo(taskRepository.findById(queued.getId()).orElseThrow()))
                .orElseThrow().getAmount();
        s.check(newAmount.compareTo(new BigDecimal("750.00")) == 0,
                "新清单统筹金额=750.00（1000×0.75），实际=" + newAmount);

        s.step("尝试批准已冻结旧清单的覆盖重算，必须被拒绝且案件保持 OPEN");
        RuleDiffCase blocked = governance.recordSilentOverrideRejection(oldCase.getId(), "试图静默覆盖已回传结果");
        s.check(blocked.getStatus() == DiffCaseStatus.OPEN
                        && governance.requireCase(oldCase.getId()).getStatus() == DiffCaseStatus.OPEN,
                "静默覆盖被拒绝（RESULT_FROZEN_REJECTED 已留痕），案件仍 OPEN，只能另发更正任务");

        s.step("结果发布册：旧清单原始行金额 700.00、规则 2026.11 原样冻结");
        var oldPub = publicationRepository.findByBusinessKeyOrderByRevisionSeqAsc("RULE-MID-OLD");
        s.check(oldPub.size() == 1 && oldPub.get(0).getAmount().compareTo(new BigDecimal("700.00")) == 0
                        && "2026.11".equals(oldPub.get(0).getRuleVersion()),
                "发布册原始行未被改写，实际行数=" + oldPub.size() + " 金额=" + oldPub.get(0).getAmount());
        return s;
    }

    /** 场景 9：人工裁决撤回——改绑后的未执行任务恢复入队时点规则，绑定版本链完整可复现 */
    private ScenarioResult ruleDiffWithdraw() {
        ScenarioResult s = new ScenarioResult("rule-diff-withdraw");
        reset();
        s.step("登记未执行清单 RULE-WD-1（报文总额 2000，入队绑定 2026.11），保持 READY 不调度");
        ClearingTask task = reg("RULE-WD-1", "v1", SourceType.HOSPITAL, "2000.00",
                payload("B-002", 10, "200.00"));

        s.step("立即发布生效新规则 2026.12（比例 0.80），影子复算自动立案");
        governance.publishRule("2026.12", new BigDecimal("0.8000"), clock.today(),
                "人工裁决撤回演练：比例上调至 0.80");
        RuleDiffCase diffCase = governance.openCases().stream()
                .filter(c -> "RULE-WD-1".equals(c.getBusinessKey())).findFirst().orElseThrow();
        s.check(!diffCase.isFrozen() && diffCase.getDeltaAmount().compareTo(new BigDecimal("200.00")) == 0,
                "未执行案件 frozen=false，影子差额=200.00（1600-1400），实际=" + diffCase.getDeltaAmount());

        s.step("先按策略 A 改绑最新规则（口径统一路径），绑定轨迹追加 REBIND");
        governance.applyLatestToUnexecuted("演练：先改绑后撤回");
        ClearingTask rebound = taskRepository.findById(task.getId()).orElseThrow();
        s.check("2026.12".equals(rebound.getRuleVersion())
                        && bindingRepository.findByTaskIdOrderBySeqAsc(task.getId()).size() == 2,
                "改绑后当前版本 2026.12，绑定轨迹 2 条（INITIAL+REBIND）");

        s.step("人工裁决撤回 WITHDRAW：案件关闭，任务恢复入队时点绑定 2026.11");
        governance.adjudicate(diffCase.getId(), DiffAction.WITHDRAW, "验收撤回：维持 2026.11 口径");
        RuleDiffCase after = governance.requireCase(diffCase.getId());
        ClearingTask reverted = taskRepository.findById(task.getId()).orElseThrow();
        s.check(after.getStatus() == DiffCaseStatus.WITHDRAWN
                        && "2026.11".equals(reverted.getRuleVersion())
                        && "2026.11".equals(reverted.getBaselineRuleVersion()),
                "案件 WITHDRAWN，任务绑定恢复 2026.11，实际案件=" + after.getStatus()
                        + " 绑定=" + reverted.getRuleVersion());
        var trail = bindingRepository.findByTaskIdOrderBySeqAsc(task.getId());
        s.check(trail.size() == 3 && trail.get(0).getBoundVersion().equals("2026.11")
                        && trail.get(1).getBoundVersion().equals("2026.12")
                        && trail.get(2).getBoundVersion().equals("2026.11"),
                "版本链可复现：2026.11(INITIAL) → 2026.12(REBIND) → 2026.11(撤回恢复)，实际="
                        + trail.stream().map(t -> t.getBoundVersion() + "/" + t.getBindKind()).toList());

        s.step("执行调度，按撤回后的原口径 2026.11 清算");
        scheduler.tick();
        BigDecimal amount = ledgerRepository.findByOperationNo(
                LedgerService.debitOpNo(taskRepository.findById(task.getId()).orElseThrow()))
                .orElseThrow().getAmount();
        s.check(amount.compareTo(new BigDecimal("1400.00")) == 0,
                "撤回后按 0.70 口径计算=1400.00（2000×0.70），实际=" + amount);
        boolean shadowDiscarded = shadowRepository.findByTaskIdOrderByIdDesc(task.getId()).stream()
                .filter(r -> r.getDiffCaseId() != null && r.getDiffCaseId().equals(diffCase.getId()))
                .allMatch(r -> r.getStatus() == com.example.clearing.domain.ShadowRecalcStatus.DISCARDED);
        s.check(shadowDiscarded, "关联影子复算记录随撤回标记 DISCARDED");
        return s;
    }

    /** 场景 10：两批同项清单补正日期不同——策略 B 另发更正任务，双版本账本、发布册与影响通知 */
    private ScenarioResult twoBatchCorrection() {
        ScenarioResult s = new ScenarioResult("two-batch-correction");
        reset();
        s.step("批次 B1（清算日 11-23）登记同项清单 RULE-TB-1 并完成清算");
        ClearingTask t1 = registrar.register("B1", LocalDate.of(2026, 11, 23), "RULE-TB-1", "v1",
                SourceType.HOSPITAL, new BigDecimal("1000.00"), payload("C-003", 5, "200.00")).task();
        scheduler.tick();
        s.step("跨午夜到 11-24，批次 B2 登记同项清单 RULE-TB-2 并完成清算");
        clock.advanceDays(1);
        ClearingTask t2 = registrar.register("B2", LocalDate.of(2026, 11, 24), "RULE-TB-2", "v1",
                SourceType.HOSPITAL, new BigDecimal("1000.00"), payload("C-003", 5, "200.00")).task();
        scheduler.tick();
        s.check(status(t1) == TaskStatus.CONFIRMED && status(t2) == TaskStatus.CONFIRMED,
                "两批清单均按 2026.11 清算 CONFIRMED（金额各 700.00）");

        s.step("11-24 发布生效新规则 2026.12（比例 0.75），两笔已回传结果影子复算各差 50.00 立案");
        governance.publishRule("2026.12", new BigDecimal("0.7500"), clock.today(),
                "两批补正演练：比例上调至 0.75");
        List<RuleDiffCase> cases = governance.openCases();
        s.check(cases.size() == 2 && cases.stream().allMatch(RuleDiffCase::isFrozen)
                        && cases.stream().allMatch(c -> c.getDeltaAmount().compareTo(new BigDecimal("50.00")) == 0),
                "2 个 frozen 差异案件，差额各 50.00，实际=" + cases.size());

        s.step("11-24 对 B1 案件裁决 ISSUE_CORRECTION：另发更正任务并调度完成");
        RuleDiffCase case1 = cases.stream().filter(c -> "RULE-TB-1".equals(c.getBusinessKey())).findFirst().orElseThrow();
        governance.adjudicate(case1.getId(), DiffAction.ISSUE_CORRECTION, "B1 补正");
        ClearingTask correction1 = taskRepository.findById(
                governance.requireCase(case1.getId()).getCorrectionTaskId()).orElseThrow();
        s.check("v1-r1".equals(correction1.getBillVersion())
                        && correction1.getCorrectionDate().equals(LocalDate.of(2026, 11, 24))
                        && "2026.12".equals(correction1.getRuleVersion()),
                "更正任务版本 v1-r1、补正日期 2026-11-24、绑定 2026.12，实际补正日="
                        + correction1.getCorrectionDate());
        scheduler.tick();

        s.step("跨午夜到 11-25，对 B2 案件裁决 ISSUE_CORRECTION（补正日期与 B1 不同）");
        clock.advanceDays(1);
        RuleDiffCase case2 = cases.stream().filter(c -> "RULE-TB-2".equals(c.getBusinessKey())).findFirst().orElseThrow();
        governance.adjudicate(case2.getId(), DiffAction.ISSUE_CORRECTION, "B2 补正");
        ClearingTask correction2 = taskRepository.findById(
                governance.requireCase(case2.getId()).getCorrectionTaskId()).orElseThrow();
        scheduler.tick();
        s.check(correction2.getCorrectionDate().equals(LocalDate.of(2026, 11, 25))
                        && !correction2.getCorrectionDate().equals(correction1.getCorrectionDate()),
                "两批补正日期不同：B1=2026-11-24，B2=2026-11-25，实际="
                        + correction1.getCorrectionDate() + "/" + correction2.getCorrectionDate());

        s.step("双版本账本：原分录 700.00 保留不动，更正分录 750.00 独立入账");
        for (String key : List.of("RULE-TB-1", "RULE-TB-2")) {
            BigDecimal originDebit = ledgerRepository.findByOperationNo("CD-" + key + "-v1")
                    .orElseThrow().getAmount();
            BigDecimal correctionDebit = ledgerRepository.findByOperationNo("CD-" + key + "-v1-r1")
                    .orElseThrow().getAmount();
            s.check(originDebit.compareTo(new BigDecimal("700.00")) == 0
                            && correctionDebit.compareTo(new BigDecimal("750.00")) == 0,
                    key + " 原扣款 700.00 未改写、更正扣款 750.00 独立存在，实际="
                            + originDebit + "/" + correctionDebit);
        }

        s.step("结果发布册：每业务键两行版本链，原行 SUPERSEDED 金额保留，更正行 PUBLISHED");
        for (String key : List.of("RULE-TB-1", "RULE-TB-2")) {
            var trail = publicationRepository.findByBusinessKeyOrderByRevisionSeqAsc(key);
            s.check(trail.size() == 2
                            && trail.get(0).getStatus() == PublicationStatus.SUPERSEDED
                            && trail.get(0).getAmount().compareTo(new BigDecimal("700.00")) == 0
                            && "2026.11".equals(trail.get(0).getRuleVersion())
                            && trail.get(1).getStatus() == PublicationStatus.PUBLISHED
                            && trail.get(1).getAmount().compareTo(new BigDecimal("750.00")) == 0
                            && "2026.12".equals(trail.get(1).getRuleVersion()),
                    key + " 发布册版本链：原行保留 700/2026.11，更正行 750/2026.12");
        }
        s.check(noticeRepository.count() == 4,
                "影响通知：每案件向基金侧+医疗机构侧各 1 条，共 4 条，实际=" + noticeRepository.count());
        s.check(noticeRepository.findAll().stream()
                        .allMatch(n -> n.getDeltaAmount().compareTo(new BigDecimal("50.00")) == 0),
                "通知均载明差额 50.00 与新旧版本/金额");
        s.check(status(t1) == TaskStatus.CONFIRMED && status(t2) == TaskStatus.CONFIRMED
                        && t1.getId() != correction1.getId(),
                "原任务保持 CONFIRMED 不被静默覆盖，更正任务为独立任务");
        return s;
    }

    // ---------- 工具 ----------

    private ClearingTask reg(String key, String version, SourceType source, String amount, String payload) {
        return registrar.register("BATCH-" + key, CLEARING_DATE, key, version, source,
                new BigDecimal(amount), payload).task();
    }

    private static String payload(String code, int qty, String price) {
        return "{\"items\":[{\"catalogCode\":\"" + code + "\",\"qty\":" + qty + ",\"price\":" + price + "}]}";
    }

    private TaskStatus status(ClearingTask task) {
        return taskRepository.findById(task.getId()).orElseThrow().getStatus();
    }

    private void tickUntil(int maxTicks, BooleanSupplier done) {
        for (int i = 0; i < maxTicks && !done.getAsBoolean(); i++) {
            scheduler.tick();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
