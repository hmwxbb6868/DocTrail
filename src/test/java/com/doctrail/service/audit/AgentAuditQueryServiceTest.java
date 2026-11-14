package com.doctrail.service.audit;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.doctrail.entity.AgentRunEntity;
import com.doctrail.entity.AgentStepEntity;
import com.doctrail.entity.ToolCallEntity;
import com.doctrail.exception.ResourceNotFoundException;
import com.doctrail.mapper.AgentRunMapper;
import com.doctrail.mapper.AgentStepMapper;
import com.doctrail.mapper.ToolCallMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentAuditQueryServiceTest {
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final AgentStepMapper steps = mock(AgentStepMapper.class);
    private final ToolCallMapper tools = mock(ToolCallMapper.class);
    private final AgentAuditQueryService service = new AgentAuditQueryService(
            runs, steps, tools, JsonMapper.builder().findAndAddModules().build());

    @Test
    void mapsRealRunAndTypedChildrenAndEmptyToolCalls() {
        UUID runId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AgentRunEntity run = new AgentRunEntity();
        run.setRunId(runId.toString());
        run.setTraceId(traceId.toString());
        run.setRequestId(requestId.toString());
        run.setTaskType("knowledge_qa");
        run.setStatus("COMPLETED");
        run.setStartedAt(OffsetDateTime.parse("2026-10-16T10:00:00Z"));
        run.setFinishedAt(OffsetDateTime.parse("2026-10-16T10:00:01Z"));
        run.setErrorCode(null);
        run.setErrorMessage(null);
        AgentStepEntity step = new AgentStepEntity();
        step.setStepId(UUID.randomUUID().toString());
        step.setStepName("retrieve");
        step.setStatus("COMPLETED");
        step.setDurationMs(9L);
        when(runs.selectOne(any())).thenReturn(run);
        when(steps.selectList(any())).thenReturn(List.of(step));
        when(tools.selectList(any())).thenReturn(List.of());

        var actual = service.detail(runId);

        assertThat(actual.mock()).isFalse();
        assertThat(actual.steps()).hasSize(1);
        assertThat(actual.toolCalls()).isEmpty();
        assertThat(actual.startedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(actual.finishedAt()).isNotNull();
        assertThat(actual.errorCode()).isNull();
    }

    @Test
    void unknownRunIsNotFound() {
        when(runs.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.detail(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void pagesAreRealAndInvalidPageArgumentsNeverReachTheDatabase() {
        when(runs.selectCount(any())).thenReturn(0L);
        when(runs.selectList(any())).thenReturn(List.of());
        var page = service.list(2, 10);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.total()).isZero();
        assertThat(page.mock()).isFalse();
        assertThat(page.items()).isEmpty();
        clearInvocations(runs);
        assertThatThrownBy(() -> service.list(0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(1, 101)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(runs);
    }

    @Test
    void mapsToolJsonIntegerErrorsAndRunningNullFinishTime() {
        UUID runId = UUID.randomUUID();
        AgentRunEntity run = new AgentRunEntity();
        run.setRunId(runId.toString());
        run.setTraceId(UUID.randomUUID().toString());
        run.setRequestId(UUID.randomUUID().toString());
        run.setStatus("RUNNING");
        run.setStartedAt(OffsetDateTime.parse("2026-10-16T18:00:00+08:00"));
        ToolCallEntity tool = new ToolCallEntity();
        tool.setToolCallId(UUID.randomUUID().toString());
        tool.setToolName("search");
        tool.setStatus("FAILED");
        tool.setErrorCode(3002);
        tool.setInputParams("{\"query\":\"q\"}");
        tool.setOutput(null);
        when(runs.selectOne(any())).thenReturn(run);
        when(steps.selectList(any())).thenReturn(List.of());
        when(tools.selectList(any())).thenReturn(List.of(tool));
        var actual = service.detail(runId);
        assertThat(actual.finishedAt()).isNull();
        assertThat(actual.startedAt()).isEqualTo(OffsetDateTime.parse("2026-10-16T10:00:00Z"));
        assertThat(actual.toolCalls().getFirst().inputParams()).containsEntry("query", "q");
        assertThat(actual.toolCalls().getFirst().output()).isNull();
        assertThat(actual.toolCalls().getFirst().errorCode()).isEqualTo(3002);
    }
}
