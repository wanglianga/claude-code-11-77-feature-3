package com.example.clearing.service;

import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.Decision;
import com.example.clearing.domain.Stage;
import com.example.clearing.domain.TaskRuleBinding;
import com.example.clearing.repo.TaskRuleBindingRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务绑定器：任务入队时把当时生效的规则版本固化绑定到任务上。
 *  - 初始绑定（INITIAL）永不删除，baselineRuleVersion 永不改变，保证历史可复现；
 *  - 策略 A 的统一重算通过追加 REBIND 绑定实现，版本轨迹完整可查；
 *  - 更正任务以 CORRECTION 绑定新规则版本，原任务绑定保持不动。
 */
@Service
public class TaskRuleBinder {

    public static final String KIND_INITIAL = "INITIAL";
    public static final String KIND_REBIND = "REBIND";
    public static final String KIND_CORRECTION = "CORRECTION";

    private final TaskRuleBindingRepository bindingRepository;
    private final RuleSnapshotService snapshotService;
    private final EventLog eventLog;

    public TaskRuleBinder(TaskRuleBindingRepository bindingRepository,
            RuleSnapshotService snapshotService,
            EventLog eventLog) {
        this.bindingRepository = bindingRepository;
        this.snapshotService = snapshotService;
        this.eventLog = eventLog;
    }

    /** 入队时点绑定当前生效规则版本 */
    @Transactional
    public String bindAtEnqueue(ClearingTask task) {
        String version = snapshotService.currentEffectiveVersion();
        if ("NONE".equals(version)) {
            throw new IllegalStateException("规则快照库尚无生效版本，无法登记任务");
        }
        snapshotService.requireVersion(version);
        task.setRuleVersion(version);
        task.setBaselineRuleVersion(version);
        TaskRuleBinding binding = new TaskRuleBinding(task.getId(), 0, version, KIND_INITIAL,
                Stage.CALC, "入队时点绑定生效规则版本 " + version);
        bindingRepository.save(binding);
        eventLog.record(task, Decision.RULE_BOUND,
                "任务绑定器：入队时点绑定规则版本 " + version + "（初始绑定，口径依据已固化）");
        return version;
    }

    /** 策略 A：未执行任务改绑最新规则，追加一条绑定轨迹，不改初始绑定 */
    @Transactional
    public String rebindToLatest(ClearingTask task, String reason) {
        String latest = snapshotService.currentEffectiveVersion();
        if ("NONE".equals(latest)) {
            throw new IllegalStateException("规则快照库尚无生效版本，无法改绑");
        }
        if (latest.equals(task.getRuleVersion())) {
            return latest;
        }
        int seq = (int) bindingRepository.countByTaskId(task.getId());
        bindingRepository.save(new TaskRuleBinding(task.getId(), seq, latest, KIND_REBIND,
                task.getStage(), reason));
        task.setRuleVersion(latest);
        eventLog.record(task, Decision.RULE_REBOUND,
                "策略 A 统一口径：规则版本由 " + task.getBaselineRuleVersion()
                        + " 改绑为最新生效版本 " + latest + "（初始绑定保留，版本链可复现）。" + reason);
        return latest;
    }

    /** 更正任务绑定目标规则版本 */
    @Transactional
    public String bindCorrection(ClearingTask correction, String targetVersion, String reason) {
        snapshotService.requireVersion(targetVersion);
        int seq = (int) bindingRepository.countByTaskId(correction.getId());
        correction.setRuleVersion(targetVersion);
        correction.setBaselineRuleVersion(targetVersion);
        bindingRepository.save(new TaskRuleBinding(correction.getId(), seq, targetVersion,
                KIND_CORRECTION, Stage.CALC, reason));
        eventLog.record(correction, Decision.RULE_BOUND,
                "更正任务绑定规则版本 " + targetVersion + "（" + reason + "）");
        return targetVersion;
    }

    public List<TaskRuleBinding> trail(Long taskId) {
        return bindingRepository.findByTaskIdOrderBySeqAsc(taskId);
    }
}
