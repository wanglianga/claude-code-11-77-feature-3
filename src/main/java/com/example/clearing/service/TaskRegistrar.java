package com.example.clearing.service;

import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.Decision;
import com.example.clearing.domain.SourceType;
import com.example.clearing.domain.Stage;
import com.example.clearing.domain.TaskStatus;
import com.example.clearing.repo.ClearingTaskRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务登记器：记录输入批次、清算日、清单版本、业务键与依赖版本。
 * 幂等规则：
 *  - 同业务键 + 同清单版本 + 同内容哈希 → 幂等吸收（DUPLICATE）；
 *  - 同业务键 + 同清单版本 + 不同内容 → 内容冲突（CONTENT_CONFLICT），不发放令牌。
 */
@Service
public class TaskRegistrar {

    /** 调度依赖：待遇计算阶段 */
    public static final String DEP_RULE = "RULE_ENGINE";
    /** 调度依赖：基金回传阶段 */
    public static final String DEP_FUND = "FUND_GW";

    private final ClearingTaskRepository taskRepository;
    private final CatalogService catalogService;
    private final FailureClassifier failureClassifier;
    private final EventLog eventLog;
    private final RuleSnapshotService snapshotService;
    private final TaskRuleBinder ruleBinder;

    public TaskRegistrar(ClearingTaskRepository taskRepository,
            CatalogService catalogService,
            FailureClassifier failureClassifier,
            EventLog eventLog,
            RuleSnapshotService snapshotService,
            TaskRuleBinder ruleBinder) {
        this.taskRepository = taskRepository;
        this.catalogService = catalogService;
        this.failureClassifier = failureClassifier;
        this.eventLog = eventLog;
        this.snapshotService = snapshotService;
        this.ruleBinder = ruleBinder;
    }

    public enum Outcome {
        CREATED,
        DUPLICATE,
        CONTENT_CONFLICT
    }

    public record RegisterResult(Outcome outcome, ClearingTask task) {
    }

    @Transactional
    public RegisterResult register(String batchId, LocalDate clearingDate, String businessKey,
            String billVersion, SourceType sourceType, BigDecimal amount, String payload) {
        String contentHash = HashUtil.sha256(payload.trim());
        List<ClearingTask> siblings = taskRepository.findByBusinessKeyAndBillVersion(businessKey, billVersion);

        for (ClearingTask sibling : siblings) {
            if (sibling.getContentHash().equals(contentHash)
                    && sibling.getStatus() != TaskStatus.CANCELLED) {
                eventLog.record(sibling, Decision.DUPLICATE,
                        "重复登记（业务键 " + businessKey + " 版本 " + billVersion + " 内容一致），幂等吸收，不新建任务");
                taskRepository.save(sibling);
                return new RegisterResult(Outcome.DUPLICATE, sibling);
            }
        }

        ClearingTask task = build(batchId, clearingDate, businessKey, billVersion, sourceType, amount,
                payload, contentHash);

        if (!siblings.isEmpty()) {
            // 同业务键同版本但内容不同 → 内容冲突，不发放令牌
            ClearingTask saved = saveConflictSafe(task);
            failureClassifier.onContentMismatch(saved,
                    "业务键 " + businessKey + " 版本 " + billVersion
                            + " 已存在不同内容的清单（原哈希 " + siblings.get(0).getContentHash().substring(0, 12)
                            + "…，新哈希 " + contentHash.substring(0, 12)
                            + "…），生成内容冲突记录，不发放尝试令牌，等待人工裁定");
            return new RegisterResult(Outcome.CONTENT_CONFLICT, saved);
        }

        task.setStatus(TaskStatus.READY);
        task.setNextAttemptAt(Instant.now());
        ClearingTask saved = taskRepository.save(task);
        // 任务绑定器：入队时点固化规则版本（初始绑定，后续改绑只追加轨迹不删除）
        String boundRule = ruleBinder.bindAtEnqueue(saved);
        saved.setDependencyVersion("CAT=" + catalogService.currentVersion() + ";RULE=" + boundRule);
        saved = taskRepository.save(saved);
        eventLog.record(saved, Decision.REGISTERED,
                "登记成功：批次 " + batchId + "，清算日 " + clearingDate + "，来源 " + sourceType
                        + "，依赖版本 " + saved.getDependencyVersion() + "，金额优先级 " + bandOf(saved.getPriority()));
        taskRepository.save(saved);
        return new RegisterResult(Outcome.CREATED, saved);
    }

    private ClearingTask saveConflictSafe(ClearingTask task) {
        try {
            return taskRepository.saveAndFlush(task);
        } catch (DataIntegrityViolationException e) {
            // 并发下同键同版本同哈希已落库 → 按幂等吸收处理
            return taskRepository
                    .findByBusinessKeyAndBillVersionAndContentHash(task.getBusinessKey(), task.getBillVersion(),
                            task.getContentHash())
                    .orElseThrow(() -> e);
        }
    }

    private ClearingTask build(String batchId, LocalDate clearingDate, String businessKey,
            String billVersion, SourceType sourceType, BigDecimal amount, String payload, String contentHash) {
        ClearingTask task = new ClearingTask();
        task.setBatchId(batchId);
        task.setClearingDate(clearingDate);
        task.setBusinessKey(businessKey);
        task.setBillVersion(billVersion);
        task.setContentHash(contentHash);
        task.setSourceType(sourceType);
        task.setDependencyCode(DEP_RULE);
        task.setDependencyVersion("CAT=" + catalogService.currentVersion()
                + ";RULE=" + snapshotService.currentEffectiveVersion());
        task.setAmount(amount);
        task.setPriority(priorityOf(amount));
        task.setStage(Stage.CALC);
        task.setPayload(payload);
        return task;
    }

    /** 金额优先级：>=50000 高(2)，>=10000 中(1)，其余 低(0) */
    public static int priorityOf(BigDecimal amount) {
        if (amount.compareTo(new BigDecimal("50000")) >= 0) {
            return 2;
        }
        if (amount.compareTo(new BigDecimal("10000")) >= 0) {
            return 1;
        }
        return 0;
    }

    public static String bandOf(int priority) {
        return switch (priority) {
            case 2 -> "HIGH";
            case 1 -> "MEDIUM";
            default -> "LOW";
        };
    }
}
