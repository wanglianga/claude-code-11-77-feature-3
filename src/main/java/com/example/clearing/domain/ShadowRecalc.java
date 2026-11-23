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
 * 影子复算器产物：用新规则快照对同一报文重新计算，只算不写账。
 * 原口径金额来自任务首次待遇计算结果（或已入账扣款分录），
 * 影子金额按目标规则快照重算，两者差额完整留痕，供差异裁决队列使用。
 */
@Entity
@Table(name = "shadow_recalc", indexes = {
        @Index(name = "idx_shadow_task", columnList = "task_id"),
        @Index(name = "idx_shadow_target", columnList = "target_version"),
        @Index(name = "idx_shadow_status", columnList = "status")
})
public class ShadowRecalc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** 业务键与清单版本（冗余存储，便于脱离任务检索版本链） */
    @Column(name = "business_key", nullable = false, length = 128)
    private String businessKey;

    @Column(name = "bill_version", nullable = false, length = 32)
    private String billVersion;

    /** 复算所依据的报文内容哈希（与任务 content_hash 一致） */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** 原口径规则版本 */
    @Column(name = "base_version", nullable = false, length = 32)
    private String baseVersion;

    /** 影子复算目标规则版本 */
    @Column(name = "target_version", nullable = false, length = 32)
    private String targetVersion;

    @Column(name = "original_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal originalAmount;

    @Column(name = "shadow_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal shadowAmount;

    /** 差额 = 影子金额 - 原金额（正数需补付，负数需追回） */
    @Column(name = "delta_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal deltaAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 24)
    private RecalcTrigger triggerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ShadowRecalcStatus status = ShadowRecalcStatus.CALCULATED;

    /** 关联差异裁决案件（OPEN 后回填） */
    @Column(name = "diff_case_id")
    private Long diffCaseId;

    @Column(name = "remark", length = 512)
    private String remark;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

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

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
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

    public RecalcTrigger getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(RecalcTrigger triggerType) {
        this.triggerType = triggerType;
    }

    public ShadowRecalcStatus getStatus() {
        return status;
    }

    public void setStatus(ShadowRecalcStatus status) {
        this.status = status;
    }

    public Long getDiffCaseId() {
        return diffCaseId;
    }

    public void setDiffCaseId(Long diffCaseId) {
        this.diffCaseId = diffCaseId;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
