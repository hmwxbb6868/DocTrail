package com.doctrail.controller.admin;

import com.doctrail.dto.admin.response.AgentRunPageResponse;
import com.doctrail.dto.admin.response.AgentRunResponse;
import com.doctrail.dto.user.response.ChatAskResponse;
import com.doctrail.exception.ApiExceptionHandler;
import com.doctrail.exception.ResourceNotFoundException;
import com.doctrail.mapper.DashboardStatsMapper;
import com.doctrail.service.audit.AgentAuditQueryService;
import com.doctrail.service.impl.AdminDashboardServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** In-memory controller/service/DTO checks; real JWT authorization is a user integration check. */
class AdminAgentAuditControllerTest {
    private final AgentAuditQueryService query = mock(AgentAuditQueryService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AgentAuditQueryService> provider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DashboardStatsMapper> dashboardProvider = mock(ObjectProvider.class);

    private MockMvc mvc() {
        when(provider.getIfAvailable()).thenReturn(query);
        return MockMvcBuilders.standaloneSetup(new AdminDashboardController(new AdminDashboardServiceImpl(provider, dashboardProvider)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void dashboardReturnsRealCountersAndExcludesRunningRecordsFromSuccessRate() throws Exception {
        var mapper = mock(DashboardStatsMapper.class);
        when(dashboardProvider.getIfAvailable()).thenReturn(mapper);
        when(mapper.countDashboardRecords()).thenReturn(new DashboardStatsMapper.Counts(2, 5, 4, 2, 3));

        mvc().perform(get("/api/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentCount").value(2))
                .andExpect(jsonPath("$.data.questionCount").value(5))
                .andExpect(jsonPath("$.data.runCount").value(4))
                .andExpect(jsonPath("$.data.finishedRunCount").value(3))
                .andExpect(jsonPath("$.data.successRate").value(66.67))
                .andExpect(jsonPath("$.data.mock").value(false));
    }

    @Test
    void dashboardDistinguishesNoFinishedRunsFromAllFailedRuns() throws Exception {
        var mapper = mock(DashboardStatsMapper.class);
        when(dashboardProvider.getIfAvailable()).thenReturn(mapper);
        when(mapper.countDashboardRecords())
                .thenReturn(new DashboardStatsMapper.Counts(0, 0, 1, 0, 0))
                .thenReturn(new DashboardStatsMapper.Counts(0, 1, 1, 0, 1));
        MockMvc mvc = mvc();

        mvc.perform(get("/api/admin/dashboard"))
                .andExpect(jsonPath("$.data.finishedRunCount").value(0))
                .andExpect(jsonPath("$.data.successRate").value(0.0));
        mvc.perform(get("/api/admin/dashboard"))
                .andExpect(jsonPath("$.data.finishedRunCount").value(1))
                .andExpect(jsonPath("$.data.successRate").value(0.0));
    }

    @Test
    void listAndDetailUseRealQueryAndPublicTypedFieldsIncludingNullErrors() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        var run = new AgentRunResponse(runId, UUID.randomUUID(), UUID.randomUUID(), "knowledge_qa", "COMPLETED",
                OffsetDateTime.now(), OffsetDateTime.now(), List.of(new ChatAskResponse.TraceStep(
                        stepId, "retrieve", "COMPLETED", 4L, null, null)), List.of(), null, null, false);
        when(query.list(1, 20)).thenReturn(new AgentRunPageResponse(List.of(run), 1, 20, 1, false));
        when(query.detail(runId)).thenReturn(run);
        MockMvc mvc = mvc();
        mvc.perform(get("/api/admin/agent-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.mock").value(false));
        mvc.perform(get("/api/admin/agent-runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value(runId.toString()))
                .andExpect(jsonPath("$.data.steps[0].stepId").value(stepId.toString()))
                .andExpect(jsonPath("$.data.steps[0].durationMs").value(4))
                .andExpect(jsonPath("$.data.steps[0].errorCode").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.toolCalls").isEmpty())
                .andExpect(jsonPath("$.data.errorCode").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.errorMessage").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.mock").value(false));
    }

    @Test
    void missingRunReturns404And2001() throws Exception {
        UUID runId = UUID.randomUUID();
        when(query.detail(runId)).thenThrow(new ResourceNotFoundException("agent run not found"));
        mvc().perform(get("/api/admin/agent-runs/" + runId))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(2001));
    }
}
