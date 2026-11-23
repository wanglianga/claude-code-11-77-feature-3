package com.example.clearing.domain;

/** 结果发布册行状态 */
public enum PublicationStatus {
    /** 已发布：金额不可变，任何更正只能新增修订行 */
    PUBLISHED,
    /** 被后续修订行替代（原行保留，金额不改动，历史可复现） */
    SUPERSEDED
}
