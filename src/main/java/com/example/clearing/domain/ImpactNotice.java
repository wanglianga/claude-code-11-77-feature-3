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
 * 影响通知：规则口径变化导致已回传结果需要更正时，向相关方发出的通知留痕。
 * 通知与差异案件/更正发布行关联，相关方确认后可置 ACKNOWLEDGED。
 */
@Entity
@Table(name = "impact_notice", indexes = {
        @Index(name = "idx_notice_case", columnList = "diff_case_id"),
        @Index(name = "idx_notice_biz", columnList = "business_key")
})
public class ImpactNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "diff_case_id", nullable = false)
    private Long diffCaseId;

    @Column(name = "business_key", nullable = false, length = 128)
    private String businessKey;

    /** 接收方：FUND 基金侧 / HOSPITAL 医疗机构侧 */
    @Column(name = "recipient", nullable = false, length = 32)
    private String recipient;

    @Column(name = "from_version", nullable = false, length = 32)
    private String fromVersion;

    @Column(name = "to_version", nullable = false, length = 32)
    private String toVersion;

    @Column(name = "original_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal originalAmount;

    @Column(name = "revised_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal revisedAmount;

    @Column(name = "delta_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal deltaAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NoticeStatus status = NoticeStatus.NOTIFIED;

    @Column(name = "message", length = 512)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDiffCaseId() {
        return diffCaseId;
    }

    public void setDiffCaseId(Long diffCaseId) {
        this.diffCaseId = diffCaseId;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public void setBusinessKey(String businessKey) {
        this.businessKey = businessKey;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getFromVersion() {
        return fromVersion;
    }

    public void setFromVersion(String fromVersion) {
        this.fromVersion = fromVersion;
    }

    public String getToVersion() {
        return toVersion;
    }

    public void setToVersion(String toVersion) {
        this.toVersion = toVersion;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public void setOriginalAmount(BigDecimal originalAmount) {
        this.originalAmount = originalAmount;
    }

    public BigDecimal getRevisedAmount() {
        return revisedAmount;
    }

    public void setRevisedAmount(BigDecimal revisedAmount) {
        this.revisedAmount = revisedAmount;
    }

    public BigDecimal getDeltaAmount() {
        return deltaAmount;
    }

    public void setDeltaAmount(BigDecimal deltaAmount) {
        this.deltaAmount = deltaAmount;
    }

    public NoticeStatus getStatus() {
        return status;
    }

    public void setStatus(NoticeStatus status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public void setAcknowledgedAt(Instant acknowledgedAt) {
        this.acknowledgedAt = acknowledgedAt;
    }
}
