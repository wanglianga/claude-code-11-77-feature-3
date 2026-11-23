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
import java.time.Instant;

/**
 * 任务绑定器产物：每个清算任务入队时绑定当时生效的规则版本。
 * 一个任务在版本链上的绑定轨迹由多条记录表达（初始绑定 + 每次改绑），
 * 因此 (task_id, bound_version, seq) 唯一；当前生效绑定以 seq 最大者为准。
 */
@Entity
@Table(name = "task_rule_binding",
        uniqueConstraints = @UniqueConstraint(name = "uk_binding_task_seq",
                columnNames = {"task_id", "seq"}),
        indexes = {
                @Index(name = "idx_binding_task", columnList = "task_id"),
                @Index(name = "idx_binding_rule", columnList = "bound_version")
        })
public class TaskRuleBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** 绑定序号：从 0 递增，最新绑定 seq 最大 */
    @Column(name = "seq", nullable = false)
    private int seq;

    /** 绑定的规则版本（指向 rule_snapshot.version） */
    @Column(name = "bound_version", nullable = false, length = 32)
    private String boundVersion;

    /** INITIAL 入队绑定 / REBIND 策略 A 改绑最新 / CORRECTION 更正任务绑定 */
    @Column(name = "bind_kind", nullable = false, length = 16)
    private String bindKind;

    /** 绑定时任务所处阶段（CALC 未执行 / RETURN 已出账 / DONE 已回传） */
    @Enumerated(EnumType.STRING)
    @Column(name = "stage_at_bind", nullable = false, length = 16)
    private Stage stageAtBind;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "bound_at", nullable = false)
    private Instant boundAt = Instant.now();

    public TaskRuleBinding() {
    }

    public TaskRuleBinding(Long taskId, int seq, String boundVersion, String bindKind,
            Stage stageAtBind, String reason) {
        this.taskId = taskId;
        this.seq = seq;
        this.boundVersion = boundVersion;
        this.bindKind = bindKind;
        this.stageAtBind = stageAtBind;
        this.reason = reason;
    }

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

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public String getBoundVersion() {
        return boundVersion;
    }

    public void setBoundVersion(String boundVersion) {
        this.boundVersion = boundVersion;
    }

    public String getBindKind() {
        return bindKind;
    }

    public void setBindKind(String bindKind) {
        this.bindKind = bindKind;
    }

    public Stage getStageAtBind() {
        return stageAtBind;
    }

    public void setStageAtBind(Stage stageAtBind) {
        this.stageAtBind = stageAtBind;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getBoundAt() {
        return boundAt;
    }

    public void setBoundAt(Instant boundAt) {
        this.boundAt = boundAt;
    }
}
