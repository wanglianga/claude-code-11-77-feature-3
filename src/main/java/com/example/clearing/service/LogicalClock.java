package com.example.clearing.service;

import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * 逻辑时钟：演示“规则跨午夜生效”的确定性时钟。
 * 默认固定在演练基准日，可通过接口/演练推进天数，不依赖真实当前日期，
 * 保证验收结果可复现。
 */
@Component
public class LogicalClock {

    private volatile LocalDate today = LocalDate.of(2026, 11, 23);

    public LocalDate today() {
        return today;
    }

    public void advanceDays(int days) {
        this.today = today.plusDays(days);
    }

    public void reset() {
        this.today = LocalDate.of(2026, 11, 23);
    }
}
