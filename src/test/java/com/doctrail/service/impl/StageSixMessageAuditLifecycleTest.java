package com.doctrail.service.impl;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.doctrail.dto.ai.ask.AiAskRequest;
import com.doctrail.dto.ai.ask.AiAskResponse;
import com.doctrail.dto.user.request.ChatAskRequest;
import com.doctrail.entity.ConversationEntity;
import com.doctrail.entity.MessageEntity;
import com.doctrail.entity.QaLogEntity;
import com.doctrail.exception.AiServiceClientException;
import com.doctrail.exception.RequestTerminalFailureException;
import com.doctrail.mapper.ConversationMapper;
import com.doctrail.mapper.MessageMapper;
import com.doctrail.mapper.QaLogMapper;
import com.doctrail.security.AuthenticatedIdentity;
import com.doctrail.service.ai.client.AiAskClient;
import com.doctrail.service.audit.AgentAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StageSixMessageAuditLifecycleTest {
    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final QaLogMapper qaLogs = mock(QaLogMapper.class);
    private final AiAskClient python = mock(AiAskClient.class);
    private final AgentAuditService audit = mock(AgentAuditService.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final StageTwoMessageServiceImpl service = new StageTwoMessageServiceImpl(
            conversations, messages, qaLogs, python, json, manager, audit, Duration.ofSeconds(900));
    private final UUID requestId = UUID.randomUUID();
    private final AuthenticatedIdentity identity = new AuthenticatedIdentity(7L, "demo", "USER");
    private final ChatAskRequest request = new ChatAskRequest("question", 42L);
    private final AtomicReference<MessageEntity> saved = new AtomicReference<>();

    @BeforeEach
    void fixture() {
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(42L);
        conversation.setUserId(7L);
        when(conversations.selectById(42L)).thenReturn(conversation);
        when(messages.selectOne(any())).thenAnswer(invocation -> saved.get());
        when(messages.selectList(any())).thenReturn(List.of());
        when(messages.selectCount(any())).thenReturn(0L);
        when(messages.insert(any(MessageEntity.class))).thenAnswer(invocation -> {
            MessageEntity row = invocation.getArgument(0);
            if ("user".equals(row.getRole())) {
                row.setId(100L);
                saved.set(row);
            }
            return 1;
        });
        when(messages.updateById(any(MessageEntity.class))).thenReturn(1);
        when(qaLogs.insert(any(QaLogEntity.class))).thenReturn(1);
        when(python.ask(any())).thenAnswer(invocation -> {
            AiAskRequest call = invocation.getArgument(0);
            return new AiAskResponse("answer", List.of(), "knowledge_qa", call.runId(), call.traceId(), null,
                    new AiAskResponse.ExecutionTrace("COMPLETED", List.of(), List.of()), false);
        });
    }

    @Test
    void auditStartsAfterGateCommitBeforeModelAndFinishesAfterSuccessCommit() {
        var result = service.send(identity, request, requestId.toString());
        assertThat(result.mock()).isFalse();
        var order = inOrder(manager, audit, python);
        order.verify(manager).commit(any());
        order.verify(audit).start(any(), any(), any());
        order.verify(python).ask(any());
        order.verify(manager).commit(any());
        order.verify(audit).reconcileSafely(requestId);
        assertThat(saved.get().getRequestState()).isEqualTo("SUCCEEDED");
    }

    @Test
    void runCreationFailurePreventsModelCallAndPersistsFailure() {
        doThrow(new IllegalStateException("audit storage unavailable"))
                .when(audit).start(any(), any(), any());

        assertThatThrownBy(() -> service.send(identity, request, requestId.toString()))
                .isInstanceOf(RequestTerminalFailureException.class);

        verifyNoInteractions(python, qaLogs);
        assertThat(saved.get().getRequestState()).isEqualTo("FAILED");
        assertThat(saved.get().getRequestErrorCode()).isEqualTo(5000);
        verify(audit).reconcileSafely(requestId);
    }

    @Test
    void successfulAnswerSurvivesActualAuditTransactionFailure() {
        AgentAuditService realBoundary = spy(new AgentAuditService(messages, mock(com.doctrail.mapper.AgentRunMapper.class),
                mock(com.doctrail.mapper.AgentStepMapper.class), mock(com.doctrail.mapper.ToolCallMapper.class), json, manager));
        doNothing().when(realBoundary).start(any(), any(), any());
        doThrow(new IllegalStateException("audit transaction unavailable")).when(realBoundary).reconcile(requestId);
        var isolatedService = new StageTwoMessageServiceImpl(conversations, messages, qaLogs, python, json,
                manager, realBoundary, Duration.ofSeconds(900));

        var actual = isolatedService.send(identity, request, requestId.toString());

        assertThat(actual.answer()).isEqualTo("answer");
        assertThat(saved.get().getRequestState()).isEqualTo("SUCCEEDED");
        assertThat(saved.get().getAnswerSnapshot()).contains("answer");
        verify(python, times(1)).ask(any());
        verify(realBoundary).reconcile(requestId);
    }

    @Test
    void pythonFailureIsCommittedBeforeAuditFinalization() {
        doThrow(AiServiceClientException.contract("invalid response", null)).when(python).ask(any());
        assertThatThrownBy(() -> service.send(identity, request, requestId.toString()))
                .isInstanceOf(AiServiceClientException.class);
        assertThat(saved.get().getRequestState()).isEqualTo("FAILED");
        assertThat(saved.get().getRequestErrorCode()).isEqualTo(3003);
        var order = inOrder(manager, audit, python);
        order.verify(manager).commit(any());
        order.verify(audit).start(any(), any(), any());
        order.verify(python).ask(any());
        order.verify(manager).commit(any());
        order.verify(audit).reconcileSafely(requestId);
    }

    @Test
    void timeoutRecoveryCommitsBeforeFailingTheCorrespondingAudit() {
        MessageEntity row = new MessageEntity();
        row.setId(100L);
        row.setRequestId(requestId.toString());
        row.setRole("user");
        row.setRequestState("RUNNING");
        when(messages.selectTimedOutRunningForUpdate(any())).thenReturn(List.of(row));

        service.recoverTimedOutRequests();

        assertThat(row.getRequestErrorCode()).isEqualTo(5000);
        var order = inOrder(manager, audit);
        order.verify(manager).commit(any());
        order.verify(audit).reconcileSafely(requestId);
        verifyNoInteractions(python, qaLogs);
    }
}
