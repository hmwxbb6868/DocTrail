package com.doctrail.controller.admin;

import com.doctrail.common.Result;
import com.doctrail.dto.admin.response.AgentRunPageResponse;
import com.doctrail.dto.admin.response.AgentRunResponse;
import com.doctrail.dto.admin.response.DashboardStatsResponse;
import com.doctrail.service.admin.AdminDashboardService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/admin")
public class AdminDashboardController {
    private final AdminDashboardService adminDashboardService;

    public AdminDashboardController(AdminDashboardService adminDashboardService) {
        this.adminDashboardService = adminDashboardService;
    }

    //大盘统计接口
    @GetMapping("/dashboard")
    public Result<DashboardStatsResponse> stats() {
        return Result.success(adminDashboardService.stats());
    }

    //Agent 审计任务分页列表接口
    @GetMapping("/agent-runs")
    public Result<AgentRunPageResponse> runs(
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int size
    ) {
        return Result.success(adminDashboardService.runs(page, size));
    }

    //单条 Agent 任务详情接口
    @GetMapping("/agent-runs/{runId}")
    public Result<AgentRunResponse> run(@PathVariable @jakarta.validation.constraints.NotNull UUID runId) {
        return Result.success(adminDashboardService.run(runId));
    }
}
