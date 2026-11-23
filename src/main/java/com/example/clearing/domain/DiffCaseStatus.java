package com.example.clearing.domain;

/** 差异裁决队列案件状态 */
public enum DiffCaseStatus {
    /** 待人工裁决 */
    OPEN,
    /** 裁决：批准按新规则重算（仅允许未执行任务） */
    APPROVED,
    /** 裁决：撤回，维持原规则依据 */
    WITHDRAWN,
    /** 裁决：驳回，不做任何处理 */
    REJECTED,
    /** 裁决：另发更正任务（策略 B） */
    CORRECTION_ISSUED
}
