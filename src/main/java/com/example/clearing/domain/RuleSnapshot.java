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
 * 规则快照库：待遇规则每次发布都固化为不可变快照。
 *  - 同一版本号唯一（uk_rule_snapshot_version）；
 *  - previousVersion 串起版本链；effectiveFrom 支持跨午夜（未来日期）生效；
 *  - 状态 DRAFT → EFFECTIVE → SUPERSEDED，人工撤回置 WITHDRAWN；
 *  - 统筹支付比例 reimbursementRate 为本演示的规则口径，报文总额 × 比例 = 统筹金额。
 */
@Entity
@Table(name = "rule_snapshot",
        uniqueConstraints = @UniqueConstraint(name = "uk_rule_snapshot_version", columnNames = "version"),
        indexes = {
                @Index(name = "idx_rule_effective", columnList = "effective_from"),
                @Index(name = "idx_rule_status", columnList = "status")
        })
public class RuleSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规则版本号，如 2026.11 / 2026.12 */
    @Column(name = "version", nullable = false, length = 32)
    private String version;

    /** 版本链上的前一版本（首个版本为 null） */
    @Column(name = "previous_version", length = 32)
    private String previousVersion;

    /** 统筹支付比例（0~1），演示口径：报文总额 × 比例 */
    @Column(name = "reimbursement_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal reimbursementRate;

    /** 规则正文/参数快照（JSON 文本，发布后不可变） */
    @Column(name = "rule_body", columnDefinition = "TEXT", nullable = false)
    private String ruleBody;

    /** 生效起始日（含当日）。跨午夜生效：发布时给未来日期，到点自动 EFFECTIVE */
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RuleSnapshotStatus status = RuleSnapshotStatus.DRAFT;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt = Instant.now();

    /** 生效时点（由 DRAFT 转 EFFECTIVE 的时间） */
    @Column(name = "became_effective_at")
    private Instant becameEffectiveAt;

    /** 撤回时间与原因（WITHDRAWN 时填写） */
    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "withdraw_reason", length = 512)
    private String withdrawReason;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getPreviousVersion() {
        return previousVersion;
    }

    public void setPreviousVersion(String previousVersion) {
        this.previousVersion = previousVersion;
    }

    public BigDecimal getReimbursementRate() {
        return reimbursementRate;
    }

    public void setReimbursementRate(BigDecimal reimbursementRate) {
        this.reimbursementRate = reimbursementRate;
    }

    public String getRuleBody() {
        return ruleBody;
    }

    public void setRuleBody(String ruleBody) {
        this.ruleBody = ruleBody;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public RuleSnapshotStatus getStatus() {
        return status;
    }

    public void setStatus(RuleSnapshotStatus status) {
        this.status = status;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getBecameEffectiveAt() {
        return becameEffectiveAt;
    }

    public void setBecameEffectiveAt(Instant becameEffectiveAt) {
        this.becameEffectiveAt = becameEffectiveAt;
    }

    public Instant getWithdrawnAt() {
        return withdrawnAt;
    }

    public void setWithdrawnAt(Instant withdrawnAt) {
        this.withdrawnAt = withdrawnAt;
    }

    public String getWithdrawReason() {
        return withdrawReason;
    }

    public void setWithdrawReason(String withdrawReason) {
        this.withdrawReason = withdrawReason;
    }
}
