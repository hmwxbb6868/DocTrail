package com.doctrail.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.doctrail.dto.user.request.CreateConversationRequest;
import com.doctrail.dto.user.request.UpdateConversationRequest;
import com.doctrail.entity.ConversationEntity;
import com.doctrail.entity.MessageEntity;
import com.doctrail.entity.MessageFeedbackEntity;
import com.doctrail.dto.user.request.MessageFeedbackRequest;
import com.doctrail.mapper.ConversationMapper;
import com.doctrail.mapper.MessageMapper;
import com.doctrail.mapper.MessageFeedbackMapper;
import com.doctrail.security.AuthenticatedIdentity;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.InOrder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StageTwoConversationServiceImplTest {
    @BeforeAll
    static void initializeMybatisPlusTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ConversationEntity.class);
        TableInfoHelper.initTableInfo(assistant, MessageEntity.class);
        TableInfoHelper.initTableInfo(assistant, MessageFeedbackEntity.class);
    }

    private final ConversationMapper mapper = mock(ConversationMapper.class);
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final MessageFeedbackMapper feedbackMapper = mock(MessageFeedbackMapper.class);
    private final StageTwoConversationServiceImpl service = new StageTwoConversationServiceImpl(
            mapper, messageMapper, feedbackMapper, new ObjectMapper());
    private final AuthenticatedIdentity identity = new AuthenticatedIdentity(7L, "demo", "USER");

    @Test
    void createUsesJwtUserIdWritesUtcTimeAndReloadsPersistedRow() {
        doAnswer(invocation -> {
            ConversationEntity inserted = invocation.getArgument(0);
            inserted.setId(42L);
            return 1;
        }).when(mapper).insert(any(ConversationEntity.class));

        OffsetDateTime persistedCreateTime = OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 123_000_000, ZoneOffset.UTC);
        OffsetDateTime persistedUpdateTime = persistedCreateTime;
        ConversationEntity persisted = entity(42L, 7L, "真实会话", persistedCreateTime, persistedUpdateTime);
        when(mapper.selectById(42L)).thenReturn(persisted);

        var result = service.create(identity, new CreateConversationRequest("真实会话"));

        var captor = org.mockito.ArgumentCaptor.forClass(ConversationEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getCreateTime()).isNotNull();
        assertThat(captor.getValue().getUpdateTime()).isNotNull();
        assertThat(captor.getValue().getCreateTime().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(result.id()).isEqualTo(42L);
        assertThat(result.createdAt()).isEqualTo(persistedCreateTime);
        assertThat(result.updatedAt()).isEqualTo(persistedUpdateTime);
        assertThat(result.mock()).isFalse();
    }

    @Test
    void listMapsOnlyMapperRowsToNonMockResponses() {
        OffsetDateTime time = OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC);
        when(mapper.selectList(any())).thenReturn(List.of(entity(42L, 7L, "我的会话", time, time)));

        var result = service.list(identity);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo(42L);
        assertThat(result.getFirst().title()).isEqualTo("我的会话");
        assertThat(result.getFirst().mock()).isFalse();
    }

    @Test
    void updateRequiresOwnershipAndReturnsPersistedTitle() {
        OffsetDateTime oldTime = OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime newTime = oldTime.plusMinutes(1);
        ConversationEntity current = entity(42L, 7L, "旧标题", oldTime, oldTime);
        ConversationEntity persisted = entity(42L, 7L, "新标题", oldTime, newTime);
        when(mapper.selectById(42L)).thenReturn(current, persisted);

        var result = service.update(identity, 42L, new UpdateConversationRequest("新标题"));

        verify(mapper).updateById(current);
        assertThat(result.title()).isEqualTo("新标题");
        assertThat(result.updatedAt()).isEqualTo(newTime);
        assertThat(result.mock()).isFalse();
    }

    @Test
    void updateRejectsAnotherUsersConversation() {
        OffsetDateTime time = OffsetDateTime.now(ZoneOffset.UTC);
        ConversationEntity otherUsersConversation = entity(42L, 99L, "他人的会话", time, time);
        when(mapper.selectById(42L)).thenReturn(otherUsersConversation);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.update(identity, 42L, new UpdateConversationRequest("不应成功")))
                .isInstanceOf(com.doctrail.exception.ConversationAccessDeniedException.class);
        verify(mapper, org.mockito.Mockito.never()).updateById(any(ConversationEntity.class));
        verify(mapper, org.mockito.Mockito.never()).deleteById(any(Long.class));
    }

    @Test
    void deleteRequiresOwnershipAndDeletesOwnedConversation() {
        OffsetDateTime time = OffsetDateTime.now(ZoneOffset.UTC);
        when(mapper.selectById(42L)).thenReturn(entity(42L, 7L, "待删除", time, time));
        MessageEntity userMessage = message(900L, 42L, "user", "问题", time);
        MessageEntity assistantMessage = message(901L, 42L, "assistant", "回答", time);
        assistantMessage.setReplyToMessageId(userMessage.getId());
        when(messageMapper.selectList(any())).thenReturn(List.of(userMessage, assistantMessage));

        service.delete(identity, 42L);

        InOrder inOrder = inOrder(feedbackMapper, messageMapper, mapper);
        inOrder.verify(feedbackMapper).delete(any());
        inOrder.verify(messageMapper).update(isNull(MessageEntity.class), any());
        inOrder.verify(messageMapper).delete(any());
        inOrder.verify(mapper).deleteById(42L);
    }

    @Test
    void deleteOwnedConversationWithoutMessagesSkipsMessageCleanup() {
        OffsetDateTime time = OffsetDateTime.now(ZoneOffset.UTC);
        when(mapper.selectById(42L)).thenReturn(entity(42L, 7L, "空会话", time, time));
        when(messageMapper.selectList(any())).thenReturn(List.of());

        service.delete(identity, 42L);

        verify(feedbackMapper, org.mockito.Mockito.never()).delete(any());
        verify(messageMapper, org.mockito.Mockito.never()).update(any(), any());
        verify(messageMapper).delete(any());
        verify(mapper).deleteById(42L);
    }

    @Test
    void listMessagesIncludesCurrentUsersFeedbackAndNullWhenMissing() {
        OffsetDateTime time = OffsetDateTime.of(2026, 10, 2, 12, 0, 0, 0, ZoneOffset.UTC);
        MessageEntity assistant = message(901L, 42L, "assistant", "回答", time);
        MessageEntity user = message(900L, 42L, "user", "问题", time);
        user.setAnswerSnapshot("{\"answer\":\"回答\",\"sources\":[{\"docId\":1,\"docName\":\"e2e-knowledge.md\",\"page\":null,\"chunkIndex\":0,\"score\":0.8}],\"taskType\":\"knowledge_qa\",\"runId\":\"11111111-1111-1111-1111-111111111111\",\"traceId\":\"22222222-2222-2222-2222-222222222222\",\"usage\":null,\"executionTrace\":{\"status\":\"COMPLETED\",\"steps\":[],\"toolCalls\":[]},\"mock\":false}");
        assistant.setReplyToMessageId(user.getId());
        when(mapper.selectById(42L)).thenReturn(entity(42L, 7L, "会话", time, time));
        when(messageMapper.selectList(any())).thenReturn(List.of(user, assistant));
        MessageFeedbackEntity feedback = feedback(7L, 901L, "down", time);
        when(feedbackMapper.selectList(any())).thenReturn(List.of(feedback));

        var result = service.listMessages(identity, 42L);

        assertThat(result.get(0).feedback()).isNull();
        assertThat(result.get(1).feedback()).isEqualTo("down");
        assertThat(result.get(1).sources()).hasSize(1);
        assertThat(result.get(1).sources().getFirst().docName()).isEqualTo("e2e-knowledge.md");
    }

    @Test
    void submitFeedbackInsertsForAssistantMessage() {
        OffsetDateTime time = OffsetDateTime.of(2026, 10, 2, 12, 0, 0, 0, ZoneOffset.UTC);
        when(messageMapper.selectById(901L)).thenReturn(message(901L, 42L, "assistant", "回答", time));
        when(mapper.selectById(42L)).thenReturn(entity(42L, 7L, "会话", time, time));
        when(feedbackMapper.selectOne(any())).thenReturn(null);

        var result = service.submitFeedback(identity, 901L, new MessageFeedbackRequest("up"));

        verify(feedbackMapper).insert(any(MessageFeedbackEntity.class));
        assertThat(result.feedback()).isEqualTo("up");
    }

    @Test
    void submitFeedbackUpdatesExistingValue() {
        OffsetDateTime time = OffsetDateTime.of(2026, 10, 2, 12, 0, 0, 0, ZoneOffset.UTC);
        when(messageMapper.selectById(901L)).thenReturn(message(901L, 42L, "assistant", "回答", time));
        when(mapper.selectById(42L)).thenReturn(entity(42L, 7L, "会话", time, time));
        MessageFeedbackEntity existing = feedback(7L, 901L, "up", time);
        when(feedbackMapper.selectOne(any())).thenReturn(existing);

        var result = service.submitFeedback(identity, 901L, new MessageFeedbackRequest("down"));

        verify(feedbackMapper).updateById(existing);
        assertThat(existing.getValue()).isEqualTo("down");
        assertThat(result.feedback()).isEqualTo("down");
    }

    @Test
    void submitFeedbackRejectsUserMessage() {
        OffsetDateTime time = OffsetDateTime.of(2026, 10, 2, 12, 0, 0, 0, ZoneOffset.UTC);
        when(messageMapper.selectById(900L)).thenReturn(message(900L, 42L, "user", "问题", time));
        when(mapper.selectById(42L)).thenReturn(entity(42L, 7L, "会话", time, time));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.submitFeedback(identity, 900L, new MessageFeedbackRequest("up")))
                .isInstanceOf(com.doctrail.exception.MessageFeedbackStateException.class);
        verify(feedbackMapper, org.mockito.Mockito.never()).insert(any(MessageFeedbackEntity.class));
    }

    @Test
    void submitFeedbackRejectsUnsupportedValue() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.submitFeedback(identity, 901L, new MessageFeedbackRequest("star")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submitFeedbackRejectsMissingMessage() {
        when(messageMapper.selectById(999L)).thenReturn(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.submitFeedback(identity, 999L, new MessageFeedbackRequest("up")))
                .isInstanceOf(com.doctrail.exception.ResourceNotFoundException.class);
        verify(feedbackMapper, org.mockito.Mockito.never()).insert(any(MessageFeedbackEntity.class));
    }

    @Test
    void submitFeedbackRejectsAnotherUsersConversation() {
        OffsetDateTime time = OffsetDateTime.of(2026, 10, 2, 12, 0, 0, 0, ZoneOffset.UTC);
        when(messageMapper.selectById(901L)).thenReturn(message(901L, 42L, "assistant", "回答", time));
        when(mapper.selectById(42L)).thenReturn(entity(42L, 99L, "他人的会话", time, time));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.submitFeedback(identity, 901L, new MessageFeedbackRequest("up")))
                .isInstanceOf(com.doctrail.exception.ConversationAccessDeniedException.class);
        verify(feedbackMapper, org.mockito.Mockito.never()).insert(any(MessageFeedbackEntity.class));
    }

    private ConversationEntity entity(
            Long id,
            Long userId,
            String title,
            OffsetDateTime createTime,
            OffsetDateTime updateTime
    ) {
        ConversationEntity entity = new ConversationEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setTitle(title);
        entity.setCreateTime(createTime);
        entity.setUpdateTime(updateTime);
        return entity;
    }

    private MessageEntity message(Long id, Long conversationId, String role, String content, OffsetDateTime time) {
        MessageEntity message = new MessageEntity();
        message.setId(id);
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setCreateTime(time);
        return message;
    }

    private MessageFeedbackEntity feedback(Long userId, Long messageId, String value, OffsetDateTime time) {
        MessageFeedbackEntity feedback = new MessageFeedbackEntity();
        feedback.setUserId(userId);
        feedback.setMessageId(messageId);
        feedback.setValue(value);
        feedback.setCreateTime(time);
        feedback.setUpdateTime(time);
        return feedback;
    }
}
