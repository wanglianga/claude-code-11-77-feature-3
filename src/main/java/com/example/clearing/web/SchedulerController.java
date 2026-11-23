package com.example.clearing.web;

import com.example.clearing.dto.TickResult;
import com.example.clearing.service.ClearingScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "调度器")
@RestController
@RequestMapping("/api/scheduler")
public class SchedulerController {

    private final ClearingScheduler scheduler;

    public SchedulerController(ClearingScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Operation(summary = "手动执行一个调度周期",
            description = "协调器配额 → 本地桶补充 → 等待者提升 → 投放（令牌+退避+依赖健康三者满足）→ 待确认回执查询 → 风暴观测")
    @PostMapping("/tick")
    public TickResult tick() {
        return scheduler.tick();
    }
}
