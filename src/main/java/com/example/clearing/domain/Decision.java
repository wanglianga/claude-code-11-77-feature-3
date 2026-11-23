package com.example.clearing.domain;

/** 恢复解释器使用的决策类型（任务时间线上的每一次状态变化原因） */
public enum Decision {
    REGISTERED,
    DUPLICATE,
    CONTENT_CONFLICT,
    PROMOTED_READY,
    WAIT_CATALOG,
    WAIT_DEPENDENCY_DOWN,
    THROTTLED,
    BACKOFF,
    DISPATCHED,
    STAGE_ADVANCED,
    PENDING_CONFIRM,
    RECEIPT_QUERY_FAILED,
    CONFIRMED,
    RETRY_SCHEDULED,
    ESCALATED_MANUAL,
    MANUAL_RESOLVED,
    CANCELLED,
    /** 任务入队时绑定规则版本（任务绑定器） */
    RULE_BOUND,
    /** 未执行任务改绑最新规则（策略 A：口径统一重算） */
    RULE_REBOUND,
    /** 差异进入裁决队列 */
    RULE_DIFF_OPEN,
    /** 人工裁决：撤回差异案件，维持原规则口径 */
    RULE_DIFF_WITHDRAWN,
    /** 人工裁决：批准未执行任务按新规则重算 */
    RULE_DIFF_APPROVED,
    /** 人工裁决：驳回差异案件 */
    RULE_DIFF_REJECTED,
    /** 另发更正任务（策略 B：历史可复现） */
    REVISED_TASK_ISSUED,
    /** 结果发布册：原始结果发布（不可变） */
    RESULT_PUBLISHED,
    /** 结果发布册：更正结果发布（新版本行，不覆盖旧行） */
    RESULT_REVISION_PUBLISHED,
    /** 试图静默覆盖已回传结果被拒绝 */
    RESULT_FROZEN_REJECTED,
    /** 影响通知已发出 */
    IMPACT_NOTIFIED
}
