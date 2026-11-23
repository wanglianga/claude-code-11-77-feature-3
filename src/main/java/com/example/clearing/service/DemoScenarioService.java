package com.example.clearing.service;

import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.DepHealth;
import com.example.clearing.domain.EntryStatus;
import com.example.clearing.domain.EntryType;
import com.example.clearing.domain.FailureType;
import com.example.clearing.domain.LedgerEntry;
import com.example.clearing.domain.SourceType;
import com.example.clearing.domain.StormReport;
import com.example.clearing.domain.StormLevel;
import com.example.clearing.domain.TaskStatus;
import com.example.clearing.domain.TokenBucket;
import com.example.clearing.dto.ScenarioResult;
import com.example.clearing.repo.ClearingTaskRepository;
import com.example.clearing.repo.FailureRecordRepository;
import com.example.clearing.repo.LedgerEntryRepository;
import com.example.clearing.repo.QuotaGrantRepository;
import com.example.clearing.repo.StormReportRepository;
import com.example.clearing.repo.TaskEventRepository;
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
            QuotaGrantRepository quotaRepository) {
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
    }

    /** 演练复位：清空业务数据，恢复依赖健康、令牌桶与模拟器 */
    @Transactional
    public void reset() {
        eventRepository.deleteAll();
        failureRepository.deleteAll();
        stormRepository.deleteAll();
        quotaRepository.deleteAll();
        ledgerRepository.deleteAll();
        taskRepository.deleteAll();
        bucketService.resetAll();
        graphService.resetHealth();
        simulator.reset();
        stormObserver.resetWindow();
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
            default -> throw new IllegalArgumentException("未知场景: " + name
                    + "，可选：hospital-batch, pharmacy-return, catalog-conflict, rule-degradation, "
                    + "fund-pending-confirm, content-conflict, storm-watch");
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
