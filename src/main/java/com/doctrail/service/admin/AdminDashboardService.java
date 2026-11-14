package com.doctrail.service.admin;

import com.doctrail.dto.admin.response.AgentRunPageResponse;
import com.doctrail.dto.admin.response.AgentRunResponse;
import com.doctrail.dto.admin.response.DashboardStatsResponse;

import java.util.UUID;

public interface AdminDashboardService {
    DashboardStatsResponse stats();

    AgentRunPageResponse runs(int page, int size);

    AgentRunResponse run(UUID runId);
}
