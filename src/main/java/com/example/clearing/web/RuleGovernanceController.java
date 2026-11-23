package com.example.clearing.web;

import com.example.clearing.domain.ImpactNotice;
import com.example.clearing.domain.ResultPublication;
import com.example.clearing.domain.RuleDiffCase;
import com.example.clearing.domain.RuleSnapshot;
import com.example.clearing.domain.ShadowRecalc;
import com.example.clearing.domain.TaskRuleBinding;
import com.example.clearing.dto.DiffAdjudicateRequest;
import com.example.clearing.dto.RulePublishRequest;
import com.example.clearing.repo.ResultPublicationRepository;
import com.example.clearing.service.LogicalClock;
import com.example.clearing.service.RuleGovernanceService;
import com.example.clearing.service.RuleSnapshotService;
import com.example.clearing.service.ShadowRecalculator;
import com.example.clearing.service.TaskRuleBinder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 规则治理 API：规则快照库、任务绑定器、影子复算器、差异裁决队列、
 * 结果发布册与影响通知。GET 只读（viewer 可用），写操作需 admin。
 */
@Tag(name = "规则快照与差异治理")
@RestController
@RequestMapping("/api/rule-governance")
public class RuleGovernanceController {

    private final RuleGovernanceService governance;
    private final RuleSnapshotService snapshotService;
    private final TaskRuleBinder binder;
    private final ShadowRecalculator recalculator;
    private final ResultPublicationRepository publicationRepository;
    private final LogicalClock clock;

    public RuleGovernanceController(RuleGovernanceService governance,
            RuleSnapshotService snapshotService,
            TaskRuleBinder binder,
            ShadowRecalculator recalculator,
            ResultPublicationRepository publicationRepository,
            LogicalClock clock) {
        this.governance = governance;
        this.snapshotService = snapshotService;
        this.binder = binder;
        this.recalculator = recalculator;
        this.publicationRepository = publicationRepository;
        this.clock = clock;
    }

    // ---------- 规则快照库 ----------

    @Operation(summary = "规则快照库（版本链）", description = "按生效顺序返回全部不可变快照，含 DRAFT/EFFECTIVE/SUPERSEDED/WITHDRAWN")
    @GetMapping("/snapshots")
    public List<RuleSnapshot> snapshots() {
        return snapshotService.list();
    }

    @Operation(summary = "当前生效规则版本")
    @GetMapping("/snapshots/current")
    public RuleSnapshot current() {
        return snapshotService.currentEffective()
                .orElseThrow(() -> new IllegalArgumentException("规则快照库尚无生效版本"));
    }

    @Operation(summary = "发布规则快照", description = "effectiveFrom 为未来日期时入 DRAFT，跨午夜由调度周期自动激活；到日发布立即生效并触发影子复算")
    @PostMapping("/snapshots")
    public RuleSnapshot publish(@Valid @RequestBody RulePublishRequest request) {
        return governance.publishRule(request.version(), request.reimbursementRate(),
                request.effectiveFrom(), request.description());
    }

    @Operation(summary = "人工撤回规则版本", description = "DRAFT/EFFECTIVE 可撤回；已 SUPERSEDED 的版本链不可改写，撤回不影响已绑定任务的历史复现")
    @PostMapping("/snapshots/{version}/withdraw")
    public RuleSnapshot withdraw(@PathVariable String version, @RequestParam(required = false) String reason) {
        return snapshotService.withdraw(version, reason);
    }

    @Operation(summary = "手动执行跨午夜激活", description = "调度周期已自动调用；手动接口便于验收时立即观察激活结果")
    @PostMapping("/activate")
    public Map<String, Object> activate() {
        List<RuleSnapshot> activated = governance.runDueActivations();
        return Map.of("activated", activated, "currentEffective", snapshotService.currentEffectiveVersion());
    }

    // ---------- 任务绑定器 ----------

    @Operation(summary = "任务规则绑定轨迹", description = "INITIAL 入队绑定 / REBIND 策略 A 改绑 / CORRECTION 更正绑定，按 seq 排列")
    @GetMapping("/tasks/{taskId}/bindings")
    public List<TaskRuleBinding> bindings(@PathVariable Long taskId) {
        return binder.trail(taskId);
    }

    @Operation(summary = "策略 A：未执行任务随最新规则统一重算",
            description = "仅改绑未执行（CALC 且无扣款分录）任务；已冻结任务不受影响，只能走差异裁决更正")
    @PostMapping("/policy/rebind-latest")
    public Map<String, Object> rebindLatest(@RequestParam(required = false) String reason) {
        var rebound = governance.applyLatestToUnexecuted(reason == null ? "人工触发策略 A 统一口径" : reason);
        return Map.of("reboundTaskIds", rebound.stream().map(t -> t.getId()).toList(),
                "count", rebound.size());
    }

    // ---------- 影子复算器 ----------

    @Operation(summary = "影子复算记录", description = "只算不写账的全部复算留痕，可按任务过滤")
    @GetMapping("/shadow")
    public List<ShadowRecalc> shadow(@RequestParam(required = false) Long taskId) {
        return taskId == null ? recalculator.all() : recalculator.byTask(taskId);
    }

    @Operation(summary = "人工发起单笔影子复算", description = "差额非零自动入差异裁决队列，不产生任何账本分录")
    @PostMapping("/tasks/{taskId}/recalc")
    public Map<String, Object> recalc(@PathVariable Long taskId, @RequestParam String targetVersion) {
        var diffCase = governance.recalcSingle(taskId, targetVersion);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("diffOpened", diffCase.isPresent());
        diffCase.ifPresent(c -> view.put("diffCase", c));
        return view;
    }

    // ---------- 差异裁决队列 ----------

    @Operation(summary = "差异裁决案件列表", description = "默认仅 OPEN；status=all 返回全部案件")
    @GetMapping("/diffs")
    public List<RuleDiffCase> diffs(@RequestParam(defaultValue = "OPEN") String status) {
        return "all".equalsIgnoreCase(status) ? governance.allCases() : governance.openCases();
    }

    @Operation(summary = "差异案件详情")
    @GetMapping("/diffs/{caseId}")
    public RuleDiffCase diff(@PathVariable Long caseId) {
        return governance.requireCase(caseId);
    }

    @Operation(summary = "人工裁决差异案件",
            description = "APPROVE 仅未冻结任务可用；冻结任务 APPROVE 会被拒绝（禁止静默覆盖）；"
                    + "WITHDRAW/REJECT 关闭案件并恢复入队绑定；ISSUE_CORRECTION 另发更正任务并通知影响方")
    @PostMapping("/diffs/{caseId}/adjudicate")
    public RuleDiffCase adjudicate(@PathVariable Long caseId,
            @Valid @RequestBody DiffAdjudicateRequest request) {
        return governance.adjudicate(caseId, request.action(), request.note());
    }

    // ---------- 结果发布册 ----------

    @Operation(summary = "结果发布册", description = "按业务键返回追加式发布版本链；不带业务键返回全部发布行")
    @GetMapping("/publications")
    public List<ResultPublication> publications(@RequestParam(required = false) String businessKey) {
        return businessKey == null ? publicationRepository.findAll()
                : publicationRepository.findByBusinessKeyOrderByRevisionSeqAsc(businessKey);
    }

    // ---------- 影响通知 ----------

    @Operation(summary = "影响通知列表")
    @GetMapping("/notices")
    public List<ImpactNotice> notices() {
        return governance.allNotices();
    }

    @Operation(summary = "相关方确认知悉通知")
    @PostMapping("/notices/{noticeId}/ack")
    public ImpactNotice acknowledge(@PathVariable Long noticeId) {
        return governance.acknowledgeNotice(noticeId);
    }

    // ---------- 逻辑时钟（跨午夜演练） ----------

    @Operation(summary = "逻辑时钟当前日期（跨午夜演练用，不影响真实系统时间）")
    @GetMapping("/clock")
    public Map<String, String> clock() {
        return Map.of("today", clock.today().toString());
    }

    @Operation(summary = "推进逻辑时钟天数", description = "推进后下一次调度周期会激活到期的 DRAFT 规则快照")
    @PostMapping("/clock/advance")
    public Map<String, String> advance(@RequestParam(defaultValue = "1") int days) {
        clock.advanceDays(days);
        return Map.of("today", clock.today().toString());
    }
}
