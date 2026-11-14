package com.doctrail.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.doctrail.dto.user.response.ChatAskResponse;
import com.doctrail.dto.user.request.CreateConversationRequest;
import com.doctrail.dto.user.request.UpdateConversationRequest;
import com.doctrail.dto.user.response.ConversationResponse;
import com.doctrail.dto.user.response.MessageResponse;
import com.doctrail.dto.user.request.MessageFeedbackRequest;
import com.doctrail.entity.ConversationEntity;
import com.doctrail.entity.MessageEntity;
import com.doctrail.entity.MessageFeedbackEntity;
import com.doctrail.exception.ConversationAccessDeniedException;
import com.doctrail.exception.ResourceNotFoundException;
import com.doctrail.mapper.ConversationMapper;
import com.doctrail.mapper.MessageMapper;
import com.doctrail.mapper.MessageFeedbackMapper;
import com.doctrail.exception.MessageFeedbackStateException;
import com.doctrail.security.AuthenticatedIdentity;
import com.doctrail.service.user.ConversationService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Profile("stage2-auth")
public class StageTwoConversationServiceImpl implements ConversationService {
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final MessageFeedbackMapper messageFeedbackMapper;
    private final ObjectMapper objectMapper;

    public StageTwoConversationServiceImpl(ConversationMapper conversationMapper, MessageMapper messageMapper,
                                           MessageFeedbackMapper messageFeedbackMapper, ObjectMapper objectMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.messageFeedbackMapper = messageFeedbackMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ConversationResponse> list(AuthenticatedIdentity identity) {
//        MyBatis-Plus 的 Mapper，`selectList`：查询多条记录，返回`List<ConversationEntity>`
        return conversationMapper.selectList(
//                MyBatis-Plus 的**Lambda 条件构造器**，用来拼接 SQL 查询条件，不用手写 SQL 字符串。
                        Wrappers.<ConversationEntity>lambdaQuery()
//                                数据库里`user_id`等于当前登录用户 id 的会话
                                .eq(ConversationEntity::getUserId, identity.userId())
//                                `orderByDesc` = 降序（从大到小，最新的放前面）
                                .orderByDesc(ConversationEntity::getUpdateTime)
//                                如果更新时间一样，**再按会话 id 倒序**作为次级排序。
                                .orderByDesc(ConversationEntity::getId)
//                把查询出来的`List<ConversationEntity>`转为 Java 流，用来做数据转换。
                ).stream()
//                `.map` 的作用：**遍历流里每一个元素，做转换，输出新对象**。
//                `this::toResponse` 就是告诉流：**拿当前这个类里面的 `toResponse` 方法，用来做转换**。
                .map(this::toResponse)
                .toList();
    }

/*
* 插入数据库之后，**再根据刚刚插入的 id 重新查询一次数据库**，
* 拿到完整入库后的实体对象，再转成`ConversationResponse`返回；如果查不到就抛异常
*/
    @Override
    public ConversationResponse create(
            AuthenticatedIdentity identity,
            CreateConversationRequest request
    ) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        ConversationEntity entity = new ConversationEntity();
        entity.setUserId(identity.userId());
        entity.setTitle(request.title());
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        conversationMapper.insert(entity);
//   **再根据 id 去数据库读取这条刚创建的记录**，确认记录真实存在，并且拿到数据库中真实存储的数据
        ConversationEntity persisted = conversationMapper.selectById(entity.getId());
        if (persisted == null) {
            throw new IllegalStateException("created conversation could not be reloaded");
        }
        return toResponse(persisted);
    }

    @Override
    public ConversationResponse update(
            AuthenticatedIdentity identity,
            long conversationId,
            UpdateConversationRequest request
    ) {
        ConversationEntity entity = requireOwned(identity, conversationId);
        entity.setTitle(request.title());
        entity.setUpdateTime(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        conversationMapper.updateById(entity);
//        重新查一次数据库拿到最新的持久化数据
        ConversationEntity persisted = conversationMapper.selectById(conversationId);
        if (persisted == null) {
            throw new ResourceNotFoundException("conversation not found");
        }
        return toResponse(persisted);
    }

    @Override
    @Transactional
    public void delete(AuthenticatedIdentity identity, long conversationId) {
        requireOwned(identity, conversationId);
        List<Long> messageIds = messageMapper.selectList(
                        Wrappers.<MessageEntity>lambdaQuery()
                                .eq(MessageEntity::getConversationId, conversationId)
                ).stream()
//                遍历流中每一个 `MessageEntity` 对象，调用它的 `getId()` 方法，取出 id，形成一个只包含 id 的新流。
                .map(MessageEntity::getId)
                .toList();
        if (!messageIds.isEmpty()) {
            messageFeedbackMapper.delete(
                    Wrappers.<MessageFeedbackEntity>lambdaQuery()
                            .in(MessageFeedbackEntity::getMessageId, messageIds)
            );
            messageMapper.update(
                    null,
                    Wrappers.<MessageEntity>lambdaUpdate()
                            .eq(MessageEntity::getConversationId, conversationId)
                            .set(MessageEntity::getReplyToMessageId, null)
            );
        }
        messageMapper.delete(
                Wrappers.<MessageEntity>lambdaQuery()
                        .eq(MessageEntity::getConversationId, conversationId)
        );
        conversationMapper.deleteById(conversationId);
    }

//    展示会话消息列表
    @Override
    public List<MessageResponse> listMessages(AuthenticatedIdentity identity, long conversationId) {
        requireOwned(identity, conversationId);
        List<MessageEntity> messages = messageMapper.selectList(
                        Wrappers.<com.doctrail.entity.MessageEntity>lambdaQuery()
                                .eq(com.doctrail.entity.MessageEntity::getConversationId, conversationId)
                                .orderByAsc(com.doctrail.entity.MessageEntity::getId)
                );
        Map<Long, MessageFeedbackEntity> feedbackByMessage = feedbackByMessage(identity.userId(), messages);
        Map<Long, MessageEntity> requestsById = messages.stream()
                .filter(message -> "user".equals(message.getRole()))
                .collect(Collectors.toMap(MessageEntity::getId, Function.identity()));
        return messages.stream().map(message -> {
            List<ChatAskResponse.Source> sources = "assistant".equals(message.getRole())
                    ? sourcesOf(requestsById.get(message.getReplyToMessageId()))
                    : List.of();
            return toMessageResponse(message, feedbackByMessage.get(message.getId()), sources);
        }).toList();
    }

//    提交反馈
    @Override
    @Transactional
    public MessageResponse submitFeedback(AuthenticatedIdentity identity, long messageId,
                                           MessageFeedbackRequest request) {
        if (!"up".equals(request.value()) && !"down".equals(request.value())) {
            throw new IllegalArgumentException("value must be up or down");
        }
        MessageEntity message = messageMapper.selectById(messageId);
        if (message == null) {
            throw new ResourceNotFoundException("message not found");
        }
        requireOwned(identity, message.getConversationId());
        if (!"assistant".equals(message.getRole())) {
            throw new MessageFeedbackStateException("only assistant messages can receive feedback");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
//        查询【当前登录用户】，对这条【messageId 消息】有没有提交过反馈记录
        MessageFeedbackEntity feedback = messageFeedbackMapper.selectOne(
                Wrappers.<MessageFeedbackEntity>lambdaQuery()
                        .eq(MessageFeedbackEntity::getUserId, identity.userId())
                        .eq(MessageFeedbackEntity::getMessageId, messageId)
                        .last("LIMIT 1 FOR UPDATE")
        );
        if (feedback == null) {
            feedback = new MessageFeedbackEntity();
            feedback.setUserId(identity.userId());
            feedback.setMessageId(messageId);
            feedback.setValue(request.value());
            feedback.setCreateTime(now);
            feedback.setUpdateTime(now);
            messageFeedbackMapper.insert(feedback);
        } else {
            feedback.setValue(request.value());
            feedback.setUpdateTime(now);
            messageFeedbackMapper.updateById(feedback);
        }
        MessageEntity requestMessage = message.getReplyToMessageId() == null
                ? null
                : messageMapper.selectById(message.getReplyToMessageId());
        return toMessageResponse(message, feedback, sourcesOf(requestMessage));

    }

    private Map<Long, MessageFeedbackEntity> feedbackByMessage(Long userId, List<MessageEntity> messages) {
        if (messages.isEmpty()) return Map.of();
//        根据当前用户 ID，一批消息 ID 列表，一次性从数据库查询该用户对这些消息的所有反馈记录
        return messageFeedbackMapper.selectList(
                Wrappers.<MessageFeedbackEntity>lambdaQuery()
                        .eq(MessageFeedbackEntity::getUserId, userId)
                        .in(MessageFeedbackEntity::getMessageId,
                                messages.stream().map(MessageEntity::getId).toList())
//                转为 Map：`MessageFeedbackEntity::getMessageId`：**Map 的 key**，取反馈记录里的 messageId
//                `Function.identity()`：**Map 的 value**，就是反馈实体对象本身，等价 `feedback -> feedback`,即MessageFeedbackEntity
        ).stream().collect(Collectors.toMap(MessageFeedbackEntity::getMessageId, Function.identity()));
    }

//  MessageEntity-》MessageResponse
    private MessageResponse toMessageResponse(MessageEntity message, MessageFeedbackEntity feedback,
                                               List<ChatAskResponse.Source> sources) {
        return new MessageResponse(message.getId(), message.getConversationId(), message.getRole(),
                message.getContent(), message.getCreateTime(), feedback == null ? null : feedback.getValue(),
                sources, false);
    }

    private List<ChatAskResponse.Source> sourcesOf(MessageEntity request) {
        if (request == null || request.getAnswerSnapshot() == null || request.getAnswerSnapshot().isBlank()) {
            return List.of();
        }
        try {
            ChatAskResponse snapshot = objectMapper.readerFor(ChatAskResponse.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(request.getAnswerSnapshot());
            return snapshot.sources() == null ? List.of() : snapshot.sources();
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

//    校验两件事：会话是否存在；这个会话是不是当前登录用户自己的
    private ConversationEntity requireOwned(AuthenticatedIdentity identity, long conversationId) {
        ConversationEntity entity = conversationMapper.selectById(conversationId);
        if (entity == null) {
            throw new ResourceNotFoundException("conversation not found");
        }
        if (!Objects.equals(entity.getUserId(), identity.userId())) {
            throw new ConversationAccessDeniedException("conversation does not belong to current user");
        }
        return entity;
    }

    private ConversationResponse toResponse(ConversationEntity entity) {
        return new ConversationResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getCreateTime(),
                entity.getUpdateTime(),
                false
        );
    }
}
