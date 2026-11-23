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
 * 结果发布册：每个清算任务的对外结果以追加方式登记，已发布行不可变。
 *  - 首次清算结果以 ORIGINAL 行发布（PUBLISHED，金额冻结）；
 *  - 更正任务完成后以 CORRECTION 行发布，原行转 SUPERSEDED 但金额与规则依据原样保留；
 *  - 任何对已回传（CONFIRMED）结果的直接改写都会被拒绝（RESULT_FROZEN_REJECTED），
 *    即“不能静默覆盖已回传结果”。
 * 同一修订序号在同一业务键下唯一，形成可复现的发布版本链。
 */
@Entity
@Table(name = "result_publication",
        uniqueConstraints = @UniqueConstraint(name = "uk_publish_biz_rev",
                columnNames = {"business_key", "revision_seq"}),
        indexes = {
                @Index(name = "idx_publish_biz", columnList = "business_key"),
                @Index(name = "idx_publish_status", columnList = "status"),
                @Index(name = "idx_publish_task", columnList = "task_id")
        })
public class ResultPublication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_key", nullable = false, length = 128)
    private String businessKey;

    /** 修订序号：0 = 原始结果，1..n = 第 n 次更正 */
    @Column(name = "revision_seq", nullable = false)
    private int revisionSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "publish_type", nullable = false, length = 16)
    private PublishType publishType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PublicationStatus status = PublicationStatus.PUBLISHED;

    /** 产生该结果的任务（更正任务 id 与原任务不同） */
    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "bill_version", nullable = false, length = 32)
    private String billVersion;

    /** 该结果适用的规则版本（发布时任务绑定版本） */
    @Column(name = "rule_version", nullable = false, length = 32)
    private String ruleVersion;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** 清算日（原始结果）或补正日期（更正结果） */
    @Column(name = "clearing_date", nullable = false)
    private LocalDate clearingDate;

    /** 更正行指向被替代的上一行 id；原始行为 null */
    @Column(name = "supersedes_id")
    private Long supersedesId;

    /** 关联差异裁决案件（更正行） */
    @Column(name = "diff_case_id")
    private Long diffCaseId;

    @Column(name = "note", length = 512)
    private String note;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public void setBusinessKey(String businessKey) {
        this.businessKey = businessKey;
    }

    public int getRevisionSeq() {
        return revisionSeq;
    }

    public void setRevisionSeq(int revisionSeq) {
        this.revisionSeq = revisionSeq;
    }

    public PublishType getPublishType() {
        return publishType;
    }

    public void setPublishType(PublishType publishType) {
        this.publishType = publishType;
    }

    public PublicationStatus getStatus() {
        return status;
    }

    public void setStatus(PublicationStatus status) {
        this.status = status;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getBillVersion() {
        return billVersion;
    }

    public void setBillVersion(String billVersion) {
        this.billVersion = billVersion;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(String ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public LocalDate getClearingDate() {
        return clearingDate;
    }

    public void setClearingDate(LocalDate clearingDate) {
        this.clearingDate = clearingDate;
    }

    public Long getSupersedesId() {
        return supersedesId;
    }

    public void setSupersedesId(Long supersedesId) {
        this.supersedesId = supersedesId;
    }

    public Long getDiffCaseId() {
        return diffCaseId;
    }

    public void setDiffCaseId(Long diffCaseId) {
        this.diffCaseId = diffCaseId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }
}
