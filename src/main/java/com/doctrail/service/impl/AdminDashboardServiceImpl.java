package com.doctrail.service.impl;

import com.doctrail.dto.admin.response.AgentRunPageResponse;
import com.doctrail.dto.admin.response.AgentRunResponse;
import com.doctrail.dto.admin.response.DashboardStatsResponse;
import com.doctrail.mapper.DashboardStatsMapper;
import com.doctrail.service.admin.AdminDashboardService;
import com.doctrail.service.audit.AgentAuditQueryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Dashboard and audit reads use persistence when the corresponding providers are available. */
@Service
public class AdminDashboardServiceImpl implements AdminDashboardService {
    //Spring 提供的**延迟、可选 Bean 注入工具**
    private final ObjectProvider<AgentAuditQueryService> audit;
    private final ObjectProvider<DashboardStatsMapper> dashboard;

    public AdminDashboardServiceImpl(ObjectProvider<AgentAuditQueryService> audit,
                                     ObjectProvider<DashboardStatsMapper> dashboard) {
        this.audit = audit;
        this.dashboard = dashboard;
    }
    @Override
    public DashboardStatsResponse stats() {
        DashboardStatsMapper mapper = dashboard.getIfAvailable();
        if (mapper == null) return new DashboardStatsResponse(0, 0, 0, 0, 0, true);

        DashboardStatsMapper.Counts counts = mapper.countDashboardRecords();
        long finished = counts.finishedRunCount();
        double rate = finished == 0 ? 0 : Math.round(10_000.0 * counts.completedRunCount() / finished) / 100.0;
        return new DashboardStatsResponse(counts.documentCount(), counts.questionCount(), counts.runCount(),
                rate, finished, false);
    }

    @Override
    public AgentRunPageResponse runs(int page, int size) {
        //Spring 容器里存在这个 Bean，就返回实例；不存在直接返回 null，不会抛异常。
        AgentAuditQueryService query = audit.getIfAvailable();
        if (query != null) return query.list(page, size);
        return new AgentRunPageResponse(List.of(), page, size, 0, true);
    }

    @Override
    public AgentRunResponse run(UUID runId) {
        AgentAuditQueryService query = audit.getIfAvailable();
        if (query != null) return query.detail(runId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AgentRunResponse(
                runId, UUID.randomUUID(), UUID.randomUUID(), "knowledge_qa", "COMPLETED",
                now, now, List.of(), List.of(), null, null, true
        );
    }
}
