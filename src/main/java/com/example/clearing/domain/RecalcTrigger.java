package com.example.clearing.domain;

/** 影子复算触发来源 */
public enum RecalcTrigger {
    /** 新规则发布后自动扫描 */
    NEW_RULE_PUBLISH,
    /** 策略 A：未执行任务随最新规则统一重算 */
    POLICY_LATEST_REBIND,
    /** 人工发起的单次复算 */
    MANUAL
}
