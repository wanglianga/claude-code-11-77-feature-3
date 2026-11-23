package com.example.clearing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.DiffCaseStatus;
import com.example.clearing.domain.PublicationStatus;
import com.example.clearing.domain.ResultPublication;
import com.example.clearing.domain.RuleDiffCase;
import com.example.clearing.domain.RuleSnapshot;
import com.example.clearing.domain.RuleSnapshotStatus;
import com.example.clearing.domain.SourceType;
import com.example.clearing.domain.TaskStatus;
import com.example.clearing.repo.ImpactNoticeRepository;
import com.example.clearing.repo.LedgerEntryRepository;
import com.example.clearing.repo.ResultPublicationRepository;
import com.example.clearing.repo.RuleDiffCaseRepository;
import com.example.clearing.repo.TaskRuleBindingRepository;
import com.example.clearing.service.DemoScenarioService;
import com.example.clearing.service.LedgerService;
import com.example.clearing.service.LogicalClock;
import com.example.clearing.service.RuleGovernanceService;
import com.example.clearing.service.RuleGovernanceService.DiffAction;
import com.example.clearing.service.RuleSnapshotService;
import com.example.clearing.service.TaskRegistrar;
import com.example.clearing.service.ClearingScheduler;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 规则快照治理不变量：
 *  - 规则跨午夜生效，队列新旧清单适用不同规则且金额差异可核验；
 *  - 人工裁决撤回恢复入队时点绑定，版本链完整可复现；
 *  - 两批同项清单补正日期不同，更正任务双版本入账；
 *  - 已回传结果禁止静默覆盖，只能追加更正发布行并通知影响方。
 */
@SpringBootTest
class RuleGovernanceTest {

    @Autowired private DemoScenarioService demo;
    @Autowired private TaskRegistrar registrar;
    @Autowired private ClearingScheduler scheduler;
    @Autowired private RuleGovernanceService governance;
    @Autowired private RuleSnapshotService snapshotService;
    @Autowired private LogicalClock clock;
    @Autowired private LedgerEntryRepository ledgerRepository;
    @Autowired private RuleDiffCaseRepository diffRepository;
    @Autowired private ResultPublicationRepository publicationRepository;
    @Autowired private ImpactNoticeRepository noticeRepository;
    @Autowired private TaskRuleBindingRepository bindingRepository;
    @Autowired private com.example.clearing.repo.ClearingTaskRepository taskRepository;

    @BeforeEach
    void reset() {
        demo.reset();
    }

    private ClearingTask register(String key, String amount, String payload) {
        return registrar.register("B-" + key, LocalDate.of(2026, 11, 23), key, "v1",
                SourceType.HOSPITAL, new BigDecimal(amount), payload).task();
    }

    private static String payload(String code, int qty, String price) {
        return "{\"items\":[{\"catalogCode\":\"" + code + "\",\"qty\":" + qty + ",\"price\":" + price + "}]}";
    }

    private BigDecimal debitAmount(ClearingTask task) {
        return ledgerRepository.findByOperationNo(LedgerService.debitOpNo(task)).orElseThrow().getAmount();
    }

    @Test
    void ruleAcrossMidnightChangesRateAndFreezesPublishedResult() {
        // 旧清单在 2026.11（0.70）下清算：1000 → 700
        ClearingTask oldTask = register("G-MID-OLD", "1000.00", payload("A-001", 10, "100.00"));
        scheduler.tick();
        assertEquals(TaskStatus.CONFIRMED, taskRepository.findById(oldTask.getId()).orElseThrow().getStatus());
        assertEquals(new BigDecimal("700.00"), debitAmount(taskRepository.findById(oldTask.getId()).orElseThrow()));

        // 新规则次日生效，入库为草稿，午夜前不改变当前版本
        RuleSnapshot draft = governance.publishRule("2026.12", new BigDecimal("0.7500"),
                LocalDate.of(2026, 11, 24), "午夜生效");
        assertEquals(RuleSnapshotStatus.DRAFT, draft.getStatus());
        assertEquals("2026.11", snapshotService.currentEffectiveVersion());

        // 午夜前入队的新清单先绑定旧版本
        ClearingTask queued = register("G-MID-NEW", "1000.00", payload("A-001", 10, "100.00"));
        assertEquals("2026.11", taskRepository.findById(queued.getId()).orElseThrow().getBaselineRuleVersion());

        // 跨午夜激活，版本链切换
        clock.advanceDays(1);
        List<RuleSnapshot> activated = governance.runDueActivations();
        assertEquals(1, activated.size());
        assertEquals("2026.12", snapshotService.currentEffectiveVersion());
        assertEquals(RuleSnapshotStatus.SUPERSEDED, snapshotService.find("2026.11").orElseThrow().getStatus());
        assertEquals("2026.11", snapshotService.find("2026.12").orElseThrow().getPreviousVersion());

        // 旧清单影子差额 50 立案且冻结
        RuleDiffCase oldCase = governance.openCases().stream()
                .filter(c -> c.getBusinessKey().equals("G-MID-OLD")).findFirst().orElseThrow();
        assertTrue(oldCase.isFrozen());
        assertEquals(new BigDecimal("50.00"), oldCase.getDeltaAmount());

        // 策略 A：未执行任务改绑新规则，初始绑定保留
        governance.applyLatestToUnexecuted("统一口径");
        ClearingTask rebound = taskRepository.findById(queued.getId()).orElseThrow();
        assertEquals("2026.12", rebound.getRuleVersion());
        assertEquals("2026.11", rebound.getBaselineRuleVersion());

        // 新清单按 0.75 清算：1000 → 750
        scheduler.tick();
        assertEquals(new BigDecimal("750.00"), debitAmount(taskRepository.findById(queued.getId()).orElseThrow()));

        // 冻结任务禁止静默覆盖
        assertThrows(IllegalStateException.class,
                () -> governance.adjudicate(oldCase.getId(), DiffAction.APPROVE, "尝试覆盖"));
        assertEquals(DiffCaseStatus.OPEN, governance.requireCase(oldCase.getId()).getStatus());

        // 发布册原始行未被改写
        ResultPublication original = publicationRepository
                .findByBusinessKeyOrderByRevisionSeqAsc("G-MID-OLD").get(0);
        assertEquals(new BigDecimal("700.00"), original.getAmount());
        assertEquals("2026.11", original.getRuleVersion());
    }

    @Test
    void manualWithdrawRestoresEnqueueBindingAndKeepsVersionTrail() {
        ClearingTask task = register("G-WD-1", "2000.00", payload("B-002", 10, "200.00"));
        governance.publishRule("2026.12", new BigDecimal("0.8000"), clock.today(), "立即生效");

        RuleDiffCase diffCase = governance.openCases().stream()
                .filter(c -> c.getBusinessKey().equals("G-WD-1")).findFirst().orElseThrow();
        assertFalse(diffCase.isFrozen());
        assertEquals(new BigDecimal("200.00"), diffCase.getDeltaAmount());

        governance.applyLatestToUnexecuted("先改绑");
        assertEquals("2026.12", taskRepository.findById(task.getId()).orElseThrow().getRuleVersion());

        governance.adjudicate(diffCase.getId(), DiffAction.WITHDRAW, "撤回");
        ClearingTask reverted = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals("2026.11", reverted.getRuleVersion());
        assertEquals(DiffCaseStatus.WITHDRAWN, governance.requireCase(diffCase.getId()).getStatus());

        var trail = bindingRepository.findByTaskIdOrderBySeqAsc(task.getId());
        assertEquals(List.of("2026.11", "2026.12", "2026.11"),
                trail.stream().map(t -> t.getBoundVersion()).toList());

        scheduler.tick();
        assertEquals(new BigDecimal("1400.00"), debitAmount(taskRepository.findById(task.getId()).orElseThrow()));
    }

    @Test
    void twoBatchesSameItemGetCorrectionsOnDifferentDatesWithoutSilentOverride() {
        ClearingTask t1 = registrar.register("B1", LocalDate.of(2026, 11, 23), "G-TB-1", "v1",
                SourceType.HOSPITAL, new BigDecimal("1000.00"), payload("C-003", 5, "200.00")).task();
        scheduler.tick();
        clock.advanceDays(1);
        ClearingTask t2 = registrar.register("B2", LocalDate.of(2026, 11, 24), "G-TB-2", "v1",
                SourceType.HOSPITAL, new BigDecimal("1000.00"), payload("C-003", 5, "200.00")).task();
        scheduler.tick();

        governance.publishRule("2026.12", new BigDecimal("0.7500"), clock.today(), "补正规则");
        List<RuleDiffCase> cases = governance.openCases();
        assertEquals(2, cases.size());
        assertTrue(cases.stream().allMatch(RuleDiffCase::isFrozen));

        RuleDiffCase case1 = cases.stream().filter(c -> c.getBusinessKey().equals("G-TB-1")).findFirst().orElseThrow();
        governance.adjudicate(case1.getId(), DiffAction.ISSUE_CORRECTION, "B1 补正");
        ClearingTask correction1 = taskRepository.findById(
                governance.requireCase(case1.getId()).getCorrectionTaskId()).orElseThrow();
        assertEquals(LocalDate.of(2026, 11, 24), correction1.getCorrectionDate());
        scheduler.tick();

        clock.advanceDays(1);
        RuleDiffCase case2 = cases.stream().filter(c -> c.getBusinessKey().equals("G-TB-2")).findFirst().orElseThrow();
        governance.adjudicate(case2.getId(), DiffAction.ISSUE_CORRECTION, "B2 补正");
        ClearingTask correction2 = taskRepository.findById(
                governance.requireCase(case2.getId()).getCorrectionTaskId()).orElseThrow();
        scheduler.tick();
        assertEquals(LocalDate.of(2026, 11, 25), correction2.getCorrectionDate());
        assertNotEquals(correction1.getCorrectionDate(), correction2.getCorrectionDate());

        // 双版本账本：原 700 保留，更正 750 独立
        for (String key : List.of("G-TB-1", "G-TB-2")) {
            assertEquals(new BigDecimal("700.00"),
                    ledgerRepository.findByOperationNo("CD-" + key + "-v1").orElseThrow().getAmount());
            assertEquals(new BigDecimal("750.00"),
                    ledgerRepository.findByOperationNo("CD-" + key + "-v1-r1").orElseThrow().getAmount());
        }

        // 发布册版本链
        for (String key : List.of("G-TB-1", "G-TB-2")) {
            var trail = publicationRepository.findByBusinessKeyOrderByRevisionSeqAsc(key);
            assertEquals(2, trail.size());
            assertEquals(PublicationStatus.SUPERSEDED, trail.get(0).getStatus());
            assertEquals(new BigDecimal("700.00"), trail.get(0).getAmount());
            assertEquals(PublicationStatus.PUBLISHED, trail.get(1).getStatus());
            assertEquals(new BigDecimal("750.00"), trail.get(1).getAmount());
            assertEquals("2026.12", trail.get(1).getRuleVersion());
        }

        // 影响通知：2 案件 × 2 接收方
        assertEquals(4, noticeRepository.count());
        // 原任务仍为 CONFIRMED，未被覆盖
        assertEquals(TaskStatus.CONFIRMED, taskRepository.findById(t1.getId()).orElseThrow().getStatus());
        assertEquals(TaskStatus.CONFIRMED, taskRepository.findById(t2.getId()).orElseThrow().getStatus());
        assertTrue(taskRepository.findById(t1.getId()).orElseThrow().getRevisedById() != null);
    }

    @Test
    void ruleSnapshotIsImmutableAndWithdrawalKeepsHistoryReproducible() {
        ClearingTask task = register("G-IMM-1", "1000.00", payload("A-001", 10, "100.00"));
        scheduler.tick();
        // 同版本重复发布被拒绝（快照不可变）
        assertThrows(IllegalArgumentException.class,
                () -> governance.publishRule("2026.11", new BigDecimal("0.9000"), clock.today(), "篡改"));
        // 撤回当前生效版本后，已完成任务的绑定版本快照仍可查、金额仍可复现
        RuleSnapshot withdrawn = snapshotService.withdraw("2026.11", "演练撤回");
        assertEquals(RuleSnapshotStatus.WITHDRAWN, withdrawn.getStatus());
        assertEquals("2026.11", taskRepository.findById(task.getId()).orElseThrow().getRuleVersion());
        assertEquals(new BigDecimal("700.00"), debitAmount(taskRepository.findById(task.getId()).orElseThrow()));
        // SUPERSEDED 版本不能撤回（版本链不可改写）
        governance.publishRule("2026.12", new BigDecimal("0.7500"), clock.today(), "新版本");
        assertThrows(IllegalStateException.class,
                () -> snapshotService.withdraw("2026.11", "再次撤回旧版本"));
    }
}
