package com.example.clearing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 任务登记器登记的清算任务。
 * 唯一约束 (business_key, bill_version, content_hash)：
 *  - 同键同版本同内容 → 幂等吸收（DUPLICATE）
 *  - 同键同版本不同内容 → 内容冲突（CONTENT_CONFLICT），不发放令牌
 */
@Entity
@Table(name = "clearing_task",
        uniqueConstraints = @UniqueConstraint(name = "uk_task_key_ver_hash",
                columnNames = {"business_key", "bill_version", "content_hash"}),
        indexes = {
                @Index(name = "idx_task_status", columnList = "status"),
                @Index(name = "idx_task_dep", columnList = "dependency_code"),
                @Index(name = "idx_task_biz", columnList = "business_key")
        })
public class ClearingTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 输入批次号 */
    @Column(name = "batch_id", nullable = false, length = 64)
    private String batchId;

    /** 清算日 */
    @Column(name = "clearing_date", nullable = false)
    private LocalDate clearingDate;

    /** 业务键（医院清单号 / 药店流水号 / 异地结算号） */
    @Column(name = "business_key", nullable = false, length = 128)
    private String businessKey;

    /** 清单版本 */
    @Column(name = "bill_version", nullable = false, length = 32)
    private String billVersion;

    /** 内容哈希（登记时按报文计算） */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16)
    private SourceType sourceType;

    /** 当前阶段对应的调度依赖（CALC→RULE_ENGINE，RETURN→FUND_GW） */
    @Column(name = "dependency_code", nullable = false, length = 64)
    private String dependencyCode;

    /** 登记时的依赖版本快照（目录版本/规则版本） */
    @Column(name = "dependency_version", length = 128)
    private String dependencyVersion;

    /** 任务绑定器：当前绑定的规则版本（待遇计算按此版本取快照） */
    @Column(name = "rule_version", length = 32)
    private String ruleVersion;

    /** 入队时点绑定的初始规则版本（永不改变，版本链复现依据） */
    @Column(name = "baseline_rule_version", length = 32)
    private String baselineRuleVersion;

    /** 更正任务指向的原任务 id（非更正任务为 null） */
    @Column(name = "correction_of_id")
    private Long correctionOfId;

    /** 更正序号：0=正常任务，1..n=第 n 次更正 */
    @Column(name = "correction_seq", nullable = false)
    private int correctionSeq = 0;

    /** 本任务被哪条更正任务替代（未更正为 null） */
    @Column(name = "revised_by_id")
    private Long revisedById;

    /** 补正日期（更正任务专用，正常任务为清算日） */
    @Column(name = "correction_date")
    private LocalDate correctionDate;

    /** 受影响金额 */
    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** 金额优先级：2=高 1=中 0=低 */
    @Column(name = "priority", nullable = false)
    private int priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private TaskStatus status = TaskStatus.REGISTERED;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 16)
    private Stage stage = Stage.CALC;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    /** 原始报文（JSON，TEXT 存储，避免 PG 大对象事务限制） */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_decision", length = 32)
    private Decision lastDecision;

    @Column(name = "last_reason", length = 512)
    private String lastReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public LocalDate getClearingDate() {
        return clearingDate;
    }

    public void setClearingDate(LocalDate clearingDate) {
        this.clearingDate = clearingDate;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public void setBusinessKey(String businessKey) {
        this.businessKey = businessKey;
    }

    public String getBillVersion() {
        return billVersion;
    }

    public void setBillVersion(String billVersion) {
        this.billVersion = billVersion;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(SourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getDependencyCode() {
        return dependencyCode;
    }

    public void setDependencyCode(String dependencyCode) {
        this.dependencyCode = dependencyCode;
    }

    public String getDependencyVersion() {
        return dependencyVersion;
    }

    public void setDependencyVersion(String dependencyVersion) {
        this.dependencyVersion = dependencyVersion;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(String ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public String getBaselineRuleVersion() {
        return baselineRuleVersion;
    }

    public void setBaselineRuleVersion(String baselineRuleVersion) {
        this.baselineRuleVersion = baselineRuleVersion;
    }

    public Long getCorrectionOfId() {
        return correctionOfId;
    }

    public void setCorrectionOfId(Long correctionOfId) {
        this.correctionOfId = correctionOfId;
    }

    public int getCorrectionSeq() {
        return correctionSeq;
    }

    public void setCorrectionSeq(int correctionSeq) {
        this.correctionSeq = correctionSeq;
    }

    public Long getRevisedById() {
        return revisedById;
    }

    public void setRevisedById(Long revisedById) {
        this.revisedById = revisedById;
    }

    public LocalDate getCorrectionDate() {
        return correctionDate;
    }

    public void setCorrectionDate(LocalDate correctionDate) {
        this.correctionDate = correctionDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public Stage getStage() {
        return stage;
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Decision getLastDecision() {
        return lastDecision;
    }

    public void setLastDecision(Decision lastDecision) {
        this.lastDecision = lastDecision;
    }

    public String getLastReason() {
        return lastReason;
    }

    public void setLastReason(String lastReason) {
        this.lastReason = lastReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
