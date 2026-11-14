package com.doctrail.service.impl;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.doctrail.dto.ai.ask.AiAskRequest;
import com.doctrail.dto.ai.ask.AiAskResponse;
import com.doctrail.dto.user.request.ChatAskRequest;
import com.doctrail.dto.user.response.ChatAskResponse;
import com.doctrail.entity.ConversationEntity;
import com.doctrail.entity.MessageEntity;
import com.doctrail.entity.QaLogEntity;
import com.doctrail.exception.RequestInProgressException;
import com.doctrail.exception.AiServiceClientException;
import com.doctrail.exception.PersistedRequestFailureException;
import com.doctrail.exception.RequestIdConflictException;
import com.doctrail.mapper.ConversationMapper;
import com.doctrail.mapper.MessageMapper;
import com.doctrail.mapper.QaLogMapper;
import com.doctrail.security.AuthenticatedIdentity;
import com.doctrail.service.ai.client.AiAskClient;
import com.doctrail.service.audit.AgentAuditService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StageTwoMessageServiceImplTest {
    private final ConversationMapper conversationMapper = mock(ConversationMapper.class);
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final QaLogMapper qaLogMapper = mock(QaLogMapper.class);
    private final AiAskClient aiAskClient = mock(AiAskClient.class);
    private final AgentAuditService agentAudit = mock(AgentAuditService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final StageTwoMessageServiceImpl service = new StageTwoMessageServiceImpl(
            conversationMapper, messageMapper, qaLogMapper, aiAskClient,
            objectMapper, transactionManager, agentAudit, Duration.ofSeconds(900));

    {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
    }
    private final AuthenticatedIdentity identity = new AuthenticatedIdentity(7L, "demo", "USER");

    @Test
    void commitsGateBeforeCallingPythonAndReturnsTerminalResponse() {
        ConversationEntity conversation = ownedConversation();
        when(conversationMapper.selectById(42L)).thenReturn(conversation);
        UUID requestId = UUID.randomUUID();
        MessageEntity requestRow = requestRow(requestId, "RUNNING");
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(null, requestRow);
        MessageEntity priorUser = requestRow(UUID.randomUUID(), "SUCCEEDED");
        priorUser.setId(80L);
        MessageEntity priorAssistant = new MessageEntity();
        priorAssistant.setId(81L);
        priorAssistant.setRole("assistant");
        priorAssistant.setContent("前一轮回答");
        MessageEntity unfinishedUser = requestRow(UUID.randomUUID(), "FAILED");
        unfinishedUser.setId(82L);
        when(messageMapper.selectList(any())).thenReturn(List.of(priorUser, priorAssistant, unfinishedUser));
        when(messageMapper.selectCount(any())).thenReturn(0L);
        when(messageMapper.insert(any(MessageEntity.class))).thenAnswer(invocation -> {
            MessageEntity inserted = invocation.getArgument(0);
            if ("user".equals(inserted.getRole())) inserted.setId(100L);
            return 1;
        });
        when(messageMapper.updateById(any(MessageEntity.class))).thenReturn(1);
        when(qaLogMapper.insert(any(QaLogEntity.class))).thenReturn(1);
        AiAskResponse response = response(requestId);
        when(aiAskClient.ask(any())).thenReturn(response);

        ChatAskResponse result = service.send(identity, new ChatAskRequest("你好", 42L), requestId.toString());

        assertThat(result.mock()).isFalse();
        ArgumentCaptor<AiAskRequest> askRequest = ArgumentCaptor.forClass(AiAskRequest.class);
        verify(aiAskClient).ask(askRequest.capture());
        assertThat(askRequest.getValue().history()).extracting(AiAskRequest.HistoryItem::messageId)
                .containsExactly(80L, 81L);
        verify(messageMapper, times(2)).insert(any(MessageEntity.class));
        verify(qaLogMapper).insert(any(QaLogEntity.class));
        verify(messageMapper).updateById(requestRow);
        InOrder order = inOrder(transactionManager, aiAskClient);
        order.verify(transactionManager).commit(any(TransactionStatus.class));
        order.verify(aiAskClient).ask(any());
        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues())
                .allSatisfy(definition -> assertThat(definition.getPropagationBehavior())
                        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void runningDuplicateDoesNotCallPython() {
        ConversationEntity conversation = ownedConversation();
        when(conversationMapper.selectById(42L)).thenReturn(conversation);
        UUID requestId = UUID.randomUUID();
        MessageEntity existing = requestRow(requestId, "RUNNING");
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(existing, null);
        when(messageMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.send(identity, new ChatAskRequest("你好", 42L), requestId.toString()))
                .isInstanceOf(RequestInProgressException.class);
        verifyNoInteractions(aiAskClient, qaLogMapper);
    }

    @Test
    void aiFailureIsPersistedAndReturnedWithTheFrozenClassification() {
        ConversationEntity conversation = ownedConversation();
        when(conversationMapper.selectById(42L)).thenReturn(conversation);
        UUID requestId = UUID.randomUUID();
        MessageEntity requestRow = requestRow(requestId, "RUNNING");
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(null, requestRow);
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(messageMapper.insert(any(MessageEntity.class))).thenAnswer(invocation -> {
            MessageEntity inserted = invocation.getArgument(0);
            if ("user".equals(inserted.getRole())) inserted.setId(100L);
            return 1;
        });
        when(messageMapper.selectCount(any())).thenReturn(0L);
        when(messageMapper.updateById(any(MessageEntity.class))).thenReturn(1);
        when(aiAskClient.ask(any())).thenThrow(AiServiceClientException.timeout("timeout", null));

        assertThatThrownBy(() -> service.send(identity, new ChatAskRequest("你好", 42L), requestId.toString()))
                .isInstanceOf(AiServiceClientException.class)
                .extracting("businessCode").isEqualTo(3002);
        verify(messageMapper).updateById(argThat((MessageEntity failed) ->
                "FAILED".equals(failed.getRequestState())
                        && Integer.valueOf(504).equals(failed.getRequestErrorHttp())
                        && Integer.valueOf(3002).equals(failed.getRequestErrorCode())
                        && "AI request timed out".equals(failed.getRequestErrorMessage())));
        verify(aiAskClient).ask(any());
        verifyNoInteractions(qaLogMapper);
    }

    @Test
    void succeededDuplicateReplaysCompleteSnapshotWithoutCallingPython() throws Exception {
        when(conversationMapper.selectById(42L)).thenReturn(ownedConversation());
        UUID requestId = UUID.randomUUID();
        MessageEntity request = requestRow(requestId, "SUCCEEDED");
        ChatAskResponse saved = new ChatAskResponse("保存的答案", List.of(), "knowledge_qa",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), null,
                new ChatAskResponse.ExecutionTrace("COMPLETED", List.of(), List.of()), false);
        request.setAnswerSnapshot(objectMapper.writeValueAsString(saved));
        request.setAnswerSnapshotVersion(1);
        MessageEntity assistant = new MessageEntity();
        assistant.setId(101L);
        assistant.setRole("assistant");
        assistant.setContent("保存的答案");
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(request, assistant);

        ChatAskResponse replay = service.send(identity, new ChatAskRequest("你好", 42L), requestId.toString());

        assertThat(replay.answer()).isEqualTo("保存的答案");
        assertThat(replay.mock()).isFalse();
        verifyNoInteractions(aiAskClient, qaLogMapper);
    }

    @Test
    void historicalStageOneMockSnapshotReplaysWithoutCallingPython() throws Exception {
        when(conversationMapper.selectById(42L)).thenReturn(ownedConversation());
        UUID requestId = UUID.randomUUID();
        MessageEntity request = requestRow(requestId, "SUCCEEDED");
        ChatAskResponse saved = new ChatAskResponse("历史占位答案", List.of(), "knowledge_qa",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), null,
                new ChatAskResponse.ExecutionTrace("COMPLETED", List.of(), List.of()), true);
        request.setAnswerSnapshot(objectMapper.writeValueAsString(saved));
        request.setAnswerSnapshotVersion(1);
        MessageEntity assistant = new MessageEntity();
        assistant.setId(101L);
        assistant.setRole("assistant");
        assistant.setContent("历史占位答案");
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(request, assistant);

        ChatAskResponse replay = service.send(identity, new ChatAskRequest("你好", 42L), requestId.toString());

        assertThat(replay.answer()).isEqualTo("历史占位答案");
        assertThat(replay.mock()).isTrue();
        verifyNoInteractions(aiAskClient, qaLogMapper);
    }

    @Test
    void failedDuplicateReplaysPersistedErrorAndDoesNotCallPython() {
        when(conversationMapper.selectById(42L)).thenReturn(ownedConversation());
        UUID requestId = UUID.randomUUID();
        MessageEntity request = requestRow(requestId, "FAILED");
        request.setRequestErrorHttp(504);
        request.setRequestErrorCode(3002);
        request.setRequestErrorMessage("AI request timed out");
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(request, (MessageEntity) null);

        assertThatThrownBy(() -> service.send(identity, new ChatAskRequest("你好", 42L), requestId.toString()))
                .isInstanceOf(PersistedRequestFailureException.class)
                .satisfies(exception -> {
                    PersistedRequestFailureException failure = (PersistedRequestFailureException) exception;
                    assertThat(failure.httpStatus()).isEqualTo(504);
                    assertThat(failure.businessCode()).isEqualTo(3002);
                    assertThat(failure.safeMessage()).isEqualTo("AI request timed out");
                });
        verifyNoInteractions(aiAskClient, qaLogMapper);
    }

    @Test
    void malformedPersistedFailureIsAnInternalIntegrityErrorAndDoesNotCallPython() {
        when(conversationMapper.selectById(42L)).thenReturn(ownedConversation());
        UUID requestId = UUID.randomUUID();
        MessageEntity request = requestRow(requestId, "FAILED");
        request.setRequestErrorHttp(200);
        request.setRequestErrorCode(0);
        request.setRequestErrorMessage("invalid persisted error");
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(request, (MessageEntity) null);

        assertThatThrownBy(() -> service.send(identity, new ChatAskRequest("你好", 42L), requestId.toString()))
                .isInstanceOf(com.doctrail.exception.RequestTerminalFailureException.class);
        verifyNoInteractions(aiAskClient, qaLogMapper);
    }

    @Test
    void requestIdInsertRaceRereadsTheWinnerAndNeverCallsPythonTwice() {
        when(conversationMapper.selectById(42L)).thenReturn(ownedConversation());
        UUID requestId = UUID.randomUUID();
        MessageEntity winner = requestRow(requestId, "RUNNING");
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(null, winner, null);
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(messageMapper.insert(any(MessageEntity.class)))
                .thenThrow(new org.springframework.dao.DeadlockLoserDataAccessException(
                        "request id insert race", null));

        assertThatThrownBy(() -> service.send(identity, new ChatAskRequest("你好", 42L), requestId.toString()))
                .isInstanceOf(RequestInProgressException.class);
        verify(messageMapper).insert(any(MessageEntity.class));
        verifyNoInteractions(aiAskClient, qaLogMapper);
    }

    @Test
    void historyBudgetKeepsOnlyTheNewestWholeMessageWhenOlderHistoryWouldOverflow() {
        when(conversationMapper.selectById(42L)).thenReturn(ownedConversation());
        UUID requestId = UUID.randomUUID();
        MessageEntity requestRow = requestRow(requestId, "RUNNING");
        MessageEntity older = historyMessage(80L, "u".repeat(8000));
        MessageEntity newest = historyMessage(81L, "n".repeat(100));
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(null, requestRow);
        when(messageMapper.selectList(any())).thenReturn(List.of(older, newest));
        when(messageMapper.insert(any(MessageEntity.class))).thenAnswer(invocation -> {
            MessageEntity inserted = invocation.getArgument(0);
            if ("user".equals(inserted.getRole())) inserted.setId(100L);
            return 1;
        });
        when(messageMapper.selectCount(any())).thenReturn(0L);
        when(messageMapper.updateById(any(MessageEntity.class))).thenReturn(1);
        when(qaLogMapper.insert(any(QaLogEntity.class))).thenReturn(1);
        when(aiAskClient.ask(any())).thenReturn(response(requestId));

        service.send(identity, new ChatAskRequest("你好", 42L), requestId.toString());

        ArgumentCaptor<AiAskRequest> askRequest = ArgumentCaptor.forClass(AiAskRequest.class);
        verify(aiAskClient).ask(askRequest.capture());
        assertThat(askRequest.getValue().history()).extracting(AiAskRequest.HistoryItem::messageId)
                .containsExactly(81L);
        assertThat(askRequest.getValue().history().getFirst().content()).isEqualTo("n".repeat(100));
    }

    @Test
    void recoveryMarksExpiredRunningRequestFailedWith5000InAnIndependentTransaction() {
        MessageEntity expired = requestRow(UUID.randomUUID(), "RUNNING");
        when(messageMapper.selectTimedOutRunningForUpdate(any())).thenReturn(List.of(expired));
        when(messageMapper.updateById(expired)).thenReturn(1);

        service.recoverTimedOutRequests();

        assertThat(expired.getRequestState()).isEqualTo("FAILED");
        assertThat(expired.getRequestErrorHttp()).isEqualTo(500);
        assertThat(expired.getRequestErrorCode()).isEqualTo(5000);
        assertThat(expired.getRequestErrorMessage()).isNotBlank();
        verify(messageMapper).selectTimedOutRunningForUpdate(any());
        verify(messageMapper).updateById(expired);
        verifyNoInteractions(aiAskClient, qaLogMapper);
    }

    @Test
    void latePythonResponseCannotOverwriteRequestAlreadyRecoveredAsFailed() {
        when(conversationMapper.selectById(42L)).thenReturn(ownedConversation());
        UUID requestId = UUID.randomUUID();
        AtomicReference<MessageEntity> requestReference = new AtomicReference<>();
        AtomicInteger selectOneCalls = new AtomicInteger();
        when(messageMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            int call = selectOneCalls.incrementAndGet();
            return call == 1 || call == 5 ? null : requestReference.get();
        });
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(messageMapper.insert(any(MessageEntity.class))).thenAnswer(invocation -> {
            MessageEntity inserted = invocation.getArgument(0);
            if ("user".equals(inserted.getRole())) {
                inserted.setId(100L);
                requestReference.set(inserted);
            }
            return 1;
        });
        when(messageMapper.selectTimedOutRunningForUpdate(any()))
                .thenAnswer(invocation -> List.of(requestReference.get()));
        when(messageMapper.updateById(any(MessageEntity.class))).thenReturn(1);
        when(aiAskClient.ask(any())).thenAnswer(invocation -> {
            service.recoverTimedOutRequests();
            return response(requestId);
        });

        assertThatThrownBy(() -> service.send(
                identity, new ChatAskRequest("你好", 42L), requestId.toString()))
                .isInstanceOf(PersistedRequestFailureException.class)
                .satisfies(exception -> {
                    PersistedRequestFailureException failure = (PersistedRequestFailureException) exception;
                    assertThat(failure.httpStatus()).isEqualTo(500);
                    assertThat(failure.businessCode()).isEqualTo(5000);
                });
        assertThat(requestReference.get().getRequestState()).isEqualTo("FAILED");
        verify(messageMapper, times(1)).insert(any(MessageEntity.class));
        verifyNoInteractions(qaLogMapper);
    }

    @Test
    void reusingRequestIdForAnotherQuestionRemainsAUniqueBusinessConflict() {
        when(conversationMapper.selectById(42L)).thenReturn(ownedConversation());
        UUID requestId = UUID.randomUUID();
        MessageEntity existing = requestRow(requestId, "RUNNING");
        existing.setContent("different question");
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        assertThatThrownBy(() -> service.send(identity, new ChatAskRequest("你好", 42L), requestId.toString()))
                .isInstanceOf(RequestIdConflictException.class);
        verifyNoInteractions(aiAskClient, qaLogMapper);
    }

    private AiAskResponse response(UUID requestId) {
        UUID runId = UUID.nameUUIDFromBytes(("run:" + requestId).getBytes());
        UUID traceId = UUID.nameUUIDFromBytes(("trace:" + requestId).getBytes());
        return new AiAskResponse("来自正式索引的回答", List.of(), "knowledge_qa", runId, traceId, null,
                new AiAskResponse.ExecutionTrace("COMPLETED", List.of(), List.of()), false);
    }

    private ConversationEntity ownedConversation() {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(42L);
        conversation.setUserId(7L);
        conversation.setTitle("会话");
        return conversation;
    }

    private MessageEntity requestRow(UUID requestId, String state) {
        MessageEntity request = new MessageEntity();
        request.setId(100L);
        request.setConversationId(42L);
        request.setRole("user");
        request.setContent("你好");
        request.setRequestId(requestId.toString());
        request.setRequestState(state);
        return request;
    }

    private MessageEntity historyMessage(Long id, String content) {
        MessageEntity message = new MessageEntity();
        message.setId(id);
        message.setConversationId(42L);
        message.setRole("user");
        message.setContent(content);
        return message;
    }
}
