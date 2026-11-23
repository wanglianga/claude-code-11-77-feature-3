package com.example.clearing.domain;

/** 规则快照生命周期 */
public enum RuleSnapshotStatus {
    /** 草稿：已入快照库但未到生效时点（跨午夜生效场景） */
    DRAFT,
    /** 生效中：入队绑定与待遇计算的可用版本 */
    EFFECTIVE,
    /** 被新版本替代（版本链上前一版本） */
    SUPERSEDED,
    /** 人工撤回：不再允许新绑定，已绑定任务保留以便历史复现 */
    WITHDRAWN
}
