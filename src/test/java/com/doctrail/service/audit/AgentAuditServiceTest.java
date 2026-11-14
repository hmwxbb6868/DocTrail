package com.doctrail.service.audit;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.doctrail.dto.user.response.ChatAskResponse;
import com.doctrail.entity.*;
import com.doctrail.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentAuditServiceTest {
    private final MessageMapper messages = mock(MessageMapper.class);
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final AgentStepMapper steps = mock(AgentStepMapper.class);
    private final ToolCallMapper tools = mock(ToolCallMapper.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final AgentAuditService service = new AgentAuditService(messages, runs, steps, tools, json, manager);
    private final UUID requestId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID traceId = UUID.randomUUID();

    @BeforeEach
    void transactionStub() {
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
    }

    @Test
    void startsOneRunAfterCommittedMessageGateAndReusesExistingRunningRun() {
        when(messages.selectOne(any())).thenReturn(message("RUNNING"));
        AgentRunEntity existing = runningRun();
        when(runs.selectOne(any())).thenReturn(null, existing);
        when(runs.insert(any(AgentRunEntity.class))).thenReturn(1);

        service.start(requestId, runId, traceId);
        service.start(requestId, runId, traceId);

        verify(runs, times(1)).insert(any(AgentRunEntity.class));
        assertThat(existing.getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void completesFromPersistedSnapshotWithTypedStepAndToolAndTerminalReplayIsIdempotent() throws Exception {
        ChatAskResponse answer = answer();
        MessageEntity request = message("SUCCEEDED");
        request.setAnswerSnapshot(json.writeValueAsString(answer));
        request.setAnswerSnapshotVersion(1);
        AgentRunEntity run = runningRun();
        when(messages.selectOne(any())).thenReturn(request);
        when(messages.selectCount(any())).thenReturn(1L);
        when(runs.selectOne(any())).thenReturn(run);
        when(steps.selectOne(any())).thenReturn(null);
        when(tools.selectOne(any())).thenReturn(null);
        when(steps.insert(any(AgentStepEntity.class))).thenReturn(1);
        when(tools.insert(any(ToolCallEntity.class))).thenReturn(1);
        when(runs.updateById(any(AgentRunEntity.class))).thenReturn(1);

        service.reconcile(requestId);
        service.reconcile(requestId);

        assertThat(run.getStatus()).isEqualTo("COMPLETED");
        assertThat(run.getTaskType()).isEqualTo("knowledge_qa");
        assertThat(run.getFinishedAt()).isNotNull();
        verify(steps, times(1)).insert(any(AgentStepEntity.class));
        verify(tools, times(1)).insert(any(ToolCallEntity.class));
        verify(runs, times(1)).updateById(any(AgentRunEntity.class));
    }

    @Test
    void failedMessageMakesRunQueryableFailedWithoutChildren() {
        MessageEntity request = message("FAILED");
        request.setRequestErrorHttp(503);
        request.setRequestErrorCode(3002);
        request.setRequestErrorMessage("AI service unavailable");
        AgentRunEntity run = runningRun();
        when(messages.selectOne(any())).thenReturn(request);
        when(messages.selectCount(any())).thenReturn(0L);
        when(runs.selectOne(any())).thenReturn(run);
        when(runs.updateById(any(AgentRunEntity.class))).thenReturn(1);

        service.reconcile(requestId);

        assertThat(run.getStatus()).isEqualTo("FAILED");
        assertThat(run.getErrorCode()).isEqualTo(3002);
        assertThat(run.getFinishedAt()).isNotNull();
        verifyNoInteractions(steps, tools);
    }

    @Test
    void recordsTraceFieldsWithoutLosingNullableErrorsOrEmptyToolArray() {
        ChatAskResponse emptyTools = new ChatAskResponse("answer", List.of(), "knowledge_qa", runId.toString(),
                traceId.toString(), null,
                new ChatAskResponse.ExecutionTrace("COMPLETED", List.of(
                        new ChatAskResponse.TraceStep(UUID.randomUUID(), "retrieve", "COMPLETED", 8L, null, null)),
                        List.of()), false);
        MessageEntity request = message("SUCCEEDED");
        request.setAnswerSnapshotVersion(1);
        request.setAnswerSnapshot(write(emptyTools));
        when(messages.selectOne(any())).thenReturn(request);
        when(messages.selectCount(any())).thenReturn(1L);
        when(runs.selectOne(any())).thenReturn(runningRun());
        when(steps.selectOne(any())).thenReturn(null);
        when(steps.insert(any(AgentStepEntity.class))).thenAnswer(invocation -> {
            AgentStepEntity saved = invocation.getArgument(0);
            assertThat(saved.getErrorCode()).isNull();
            assertThat(saved.getErrorMessage()).isNull();
            return 1;
        });
        when(runs.updateById(any(AgentRunEntity.class))).thenReturn(1);

        service.reconcile(requestId);

        verify(steps).insert(any(AgentStepEntity.class));
        verifyNoInteractions(tools);
    }

    private ChatAskResponse answer() {
        return new ChatAskResponse("answer", List.of(), "knowledge_qa", runId.toString(), traceId.toString(), null,
                new ChatAskResponse.ExecutionTrace("COMPLETED", List.of(
                        new ChatAskResponse.TraceStep(UUID.randomUUID(), "retrieve", "COMPLETED", 8L, null, null)),
                        List.of(new ChatAskResponse.TraceToolCall(UUID.randomUUID(), "search", "COMPLETED",
                                Map.of("query", "q"), Map.of("count", 1), 4L, null, null))), false);
    }

    @Test
    void duplicateChildEventsUpdateWithinTheRunWithoutInsertingAgain() {
        ChatAskResponse answer = answer();
        MessageEntity request = message("SUCCEEDED");
        request.setAnswerSnapshotVersion(1);
        request.setAnswerSnapshot(write(answer));
        when(messages.selectOne(any())).thenReturn(request);
        when(messages.selectCount(any())).thenReturn(1L);
        when(runs.selectOne(any())).thenReturn(runningRun());
        AgentStepEntity step = new AgentStepEntity();
        step.setId(20L);
        step.setRunId(runId.toString());
        ToolCallEntity tool = new ToolCallEntity();
        tool.setId(30L);
        tool.setRunId(runId.toString());
        when(steps.selectOne(any())).thenReturn(step);
        when(tools.selectOne(any())).thenReturn(tool);
        when(steps.updateById(step)).thenReturn(1);
        when(tools.updateById(tool)).thenReturn(1);
        when(runs.updateById(any(AgentRunEntity.class))).thenReturn(1);

        service.reconcile(requestId);

        verify(steps).updateById(step);
        verify(tools).updateById(tool);
        verify(steps, never()).insert(any(AgentStepEntity.class));
        verify(tools, never()).insert(any(ToolCallEntity.class));
        assertThat(tool.getInputParams()).isEqualTo("{\"query\":\"q\"}");
        assertThat(tool.getOutput()).isEqualTo("{\"count\":1}");
    }

    @Test
    void rejectsChildUuidOwnedByAnotherRunAndRollsBackTheAuditTransaction() {
        MessageEntity request = message("SUCCEEDED");
        request.setAnswerSnapshotVersion(1);
        request.setAnswerSnapshot(write(answer()));
        when(messages.selectOne(any())).thenReturn(request);
        when(messages.selectCount(any())).thenReturn(1L);
        AgentRunEntity run = runningRun();
        when(runs.selectOne(any())).thenReturn(run);
        AgentStepEntity otherRunStep = new AgentStepEntity();
        otherRunStep.setRunId(UUID.randomUUID().toString());
        when(steps.selectOne(any())).thenReturn(otherRunStep);

        assertThatThrownBy(() -> service.reconcile(requestId)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another run");

        verify(manager).rollback(any());
        verify(runs, never()).updateById(any(AgentRunEntity.class));
        verifyNoInteractions(tools);
        assertThat(run.getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void scheduledCompensationRetriesFailedAuditsWithoutCallingAnyModel() {
        MessageEntity request = message("FAILED");
        request.setRequestErrorHttp(500);
        request.setRequestErrorCode(5000);
        request.setRequestErrorMessage("recovered timeout");
        when(runs.selectPendingTerminalRequestIds()).thenReturn(List.of(requestId.toString()));
        when(messages.selectOne(any())).thenReturn(request);
        when(messages.selectCount(any())).thenReturn(0L);
        when(runs.selectOne(any())).thenAnswer(invocation -> runningRun());
        when(runs.updateById(any(AgentRunEntity.class))).thenThrow(new IllegalStateException("offline"))
                .thenReturn(1);

        service.compensatePendingAudits();
        service.compensatePendingAudits();

        verify(manager, times(1)).rollback(any());
        verify(manager, times(1)).commit(any());
        verify(runs, times(2)).updateById(any(AgentRunEntity.class));
        assertThat(request.getRequestState()).isEqualTo("FAILED");
        verify(messages, never()).updateById(any(MessageEntity.class));
        verifyNoInteractions(steps, tools);
    }

    @Test
    void historicalSuccessfulMessageWithoutRunIsNotBackfilled() {
        when(messages.selectOne(any())).thenReturn(message("SUCCEEDED"));
        when(runs.selectOne(any())).thenReturn(null);
        service.reconcile(requestId);
        verify(runs, never()).insert(any(AgentRunEntity.class));
        verifyNoInteractions(steps, tools);
    }

    @Test
    void runningMessageCannotBeFinalizedByCompensation() {
        when(messages.selectOne(any())).thenReturn(message("RUNNING"));
        service.reconcile(requestId);
        verifyNoInteractions(runs, steps, tools);
    }

    @Test
    void mismatchedSnapshotIdsAreRejectedBeforeChildrenAreSaved() {
        MessageEntity request = message("SUCCEEDED");
        request.setAnswerSnapshotVersion(1);
        request.setAnswerSnapshot(write(answer()));
        AgentRunEntity run = runningRun();
        run.setTraceId(UUID.randomUUID().toString());
        when(messages.selectOne(any())).thenReturn(request);
        when(runs.selectOne(any())).thenReturn(run);
        assertThatThrownBy(() -> service.reconcile(requestId)).isInstanceOf(IllegalStateException.class);
        verify(manager).rollback(any());
        verifyNoInteractions(steps, tools);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private MessageEntity message(String state) {
        MessageEntity row = new MessageEntity();
        row.setId(10L);
        row.setRole("user");
        row.setConversationId(4L);
        row.setRequestId(requestId.toString());
        row.setRequestState(state);
        return row;
    }

    private AgentRunEntity runningRun() {
        AgentRunEntity row = new AgentRunEntity();
        row.setRunId(runId.toString());
        row.setTraceId(traceId.toString());
        row.setRequestId(requestId.toString());
        row.setConversationId(4L);
        row.setStatus("RUNNING");
        return row;
    }
}
