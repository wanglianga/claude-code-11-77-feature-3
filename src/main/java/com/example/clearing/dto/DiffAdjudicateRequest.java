package com.example.clearing.dto;

import com.example.clearing.service.RuleGovernanceService.DiffAction;
import jakarta.validation.constraints.NotNull;

/** 差异裁决请求：APPROVE / WITHDRAW / REJECT / ISSUE_CORRECTION */
public record DiffAdjudicateRequest(
        @NotNull(message = "裁决动作不能为空") DiffAction action,
        String note) {
}
