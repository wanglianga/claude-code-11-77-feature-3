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
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 差异裁决队列：影子复算发现金额差异后生成案件，等待人工裁决。
 * 案件区分任务是否已执行（frozen=true 表示已扣款/已回传，结果已冻结）：
 *  - 未执行：可 APPROVED 按新规则重算（策略 A），或 CORRECTION_ISSUED 另发更正任务（策略 B）；
 *  - 已冻结：禁止静默覆盖，只能另发更正任务并通知影响方。
 */
@Entity
@Table(name = "rule_diff_case", indexes = {
        @Index(name = "idx_diff_status", columnList = "status"),
        @Index(name = "idx_diff_task", columnList = "task_id"),
        @Index(name = "idx_diff_biz", columnList = "business_key")
})
public class RuleDiffCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "business_key", nullable = false, length = 128)
    private String businessKey;

    @Column(name = "bill_version", nullable = false, length = 32)
    private String billVersion;

    @Column(name = "base_version", nullable = false, length = 32)
    private String baseVersion;

    @Column(name = "target_version", nullable = false, length = 32)
    private String targetVersion;

    @Column(name = "original_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal originalAmount;

    @Column(name = "shadow_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal shadowAmount;

    @Column(name = "delta_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal deltaAmount;

    /** 任务在差异生成时是否已执行（已扣款/回传 → 结果冻结，不可静默覆盖） */
    @Column(name = "frozen", nullable = false)
    private boolean frozen;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private DiffCaseStatus status = DiffCaseStatus.OPEN;

    /** 裁决产生的更正任务 id（策略 B） */
    @Column(name = "correction_task_id")
    private Long correctionTaskId;

    @Column(name = "opened_reason", length = 512)
    private String openedReason;

    @Column(name = "resolution_note", length = 512)
    private String resolutionNote;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
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

    public String getBaseVersion() {
        return baseVersion;
    }

    public void setBaseVersion(String baseVersion) {
        this.baseVersion = baseVersion;
    }

    public String getTargetVersion() {
        return targetVersion;
    }

    public void setTargetVersion(String targetVersion) {
        this.targetVersion = targetVersion;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public void setOriginalAmount(BigDecimal originalAmount) {
        this.originalAmount = originalAmount;
    }

    public BigDecimal getShadowAmount() {
        return shadowAmount;
    }

    public void setShadowAmount(BigDecimal shadowAmount) {
        this.shadowAmount = shadowAmount;
    }

    public BigDecimal getDeltaAmount() {
        return deltaAmount;
    }

    public void setDeltaAmount(BigDecimal deltaAmount) {
        this.deltaAmount = deltaAmount;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    public DiffCaseStatus getStatus() {
        return status;
    }

    public void setStatus(DiffCaseStatus status) {
        this.status = status;
    }

    public Long getCorrectionTaskId() {
        return correctionTaskId;
    }

    public void setCorrectionTaskId(Long correctionTaskId) {
        this.correctionTaskId = correctionTaskId;
    }

    public String getOpenedReason() {
        return openedReason;
    }

    public void setOpenedReason(String openedReason) {
        this.openedReason = openedReason;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public void setResolutionNote(String resolutionNote) {
        this.resolutionNote = resolutionNote;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Instant openedAt) {
        this.openedAt = openedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
