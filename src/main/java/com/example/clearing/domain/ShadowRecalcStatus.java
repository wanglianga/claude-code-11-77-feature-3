package com.example.clearing.domain;

/** 影子复算记录状态（影子复算只算不写账，不影响正式分录） */
public enum ShadowRecalcStatus {
    /** 复算完成，金额差异已留痕 */
    CALCULATED,
    /** 关联差异案件被撤回/驳回，复算结果作废 */
    DISCARDED
}
