package com.example.clearing.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 规则快照发布请求；effectiveFrom 为未来日期时入草稿，跨午夜自动激活 */
public record RulePublishRequest(
        @NotBlank(message = "规则版本号不能为空") String version,
        @NotNull(message = "统筹支付比例不能为空")
        @DecimalMin(value = "0.0001", message = "统筹支付比例必须大于 0")
        @DecimalMax(value = "1.0000", message = "统筹支付比例不能大于 1")
        BigDecimal reimbursementRate,
        LocalDate effectiveFrom,
        String description) {
}
