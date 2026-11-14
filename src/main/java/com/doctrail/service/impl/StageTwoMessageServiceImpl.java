package com.doctrail.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.doctrail.dto.ai.ask.AiAskRequest;
import com.doctrail.dto.ai.ask.AiAskResponse;
import com.doctrail.dto.user.request.ChatAskRequest;
import com.doctrail.dto.user.response.ChatAskResponse;
import com.doctrail.entity.ConversationEntity;
import com.doctrail.entity.MessageEntity;
import com.doctrail.entity.QaLogEntity;
import com.doctrail.exception.AiServiceClientException;
import com.doctrail.exception.ConversationAccessDeniedException;
import com.doctrail.exception.PersistedRequestFailureException;
import com.doctrail.exception.RequestIdConflictException;
import com.doctrail.exception.RequestInProgressException;
import com.doctrail.exception.RequestTerminalFailureException;
import com.doctrail.exception.ResourceNotFoundException;
import com.doctrail.mapper.ConversationMapper;
import com.doctrail.mapper.MessageMapper;
import com.doctrail.mapper.QaLogMapper;
import com.doctrail.security.AuthenticatedIdentity;
import com.doctrail.service.ai.client.AiAskClient;
import com.doctrail.service.audit.AgentAuditService;
import com.doctrail.service.user.StageTwoMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Coordinates the complete synchronous message-request lifecycle.协调完整的同步消息请求生命周期
 * Database transactions are short REQUIRES_NEW blocks; the Python call runs
 * between them and never while a request-row lock is held.
 */
@Service
@Profile("stage2-auth")
public class StageTwoMessageServiceImpl implements StageTwoMessageService {
    private static final Logger logger = LoggerFactory.getLogger(StageTwoMessageServiceImpl.class);
    private static final int ANSWER_SNAPSHOT_VERSION = 1;
    private static final int MAX_HISTORY_MESSAGES = 20;//会话历史消息最大条数上限
    private static final int MAX_HISTORY_UTF8_BYTES = 8192;//会话历史消息最大字节上限
    private static final int HISTORY_MESSAGE_OVERHEAD_BYTES = 64;//单条历史消息固定开销字节
    private static final String RECOVERY_ERROR_MESSAGE = "AI request did not complete before the recovery timeout";

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final QaLogMapper qaLogMapper;
    private final AiAskClient aiAskClient;
    private final AgentAuditService agentAudit;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate independentTransaction;
    private final Duration requestRunningTimeout;

    public StageTwoMessageServiceImpl(
            ConversationMapper conversationMapper,
            MessageMapper messageMapper,
            QaLogMapper qaLogMapper,
            AiAskClient aiAskClient,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            AgentAuditService agentAudit,
            @Value("${doctrail.message.request-running-timeout:900s}") Duration requestRunningTimeout
    ) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.qaLogMapper = qaLogMapper;
        this.aiAskClient = aiAskClient;
        this.agentAudit = agentAudit;
        this.objectMapper = objectMapper;
        this.independentTransaction = new TransactionTemplate(transactionManager);
        this.independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        if (requestRunningTimeout == null || requestRunningTimeout.isZero() || requestRunningTimeout.isNegative()) {
            throw new IllegalArgumentException("message request running timeout must be positive");
        }
        this.requestRunningTimeout = requestRunningTimeout;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    //ChatAskRequest：conversationId，question，question；  requestIdHeader：一次 AI 请求的唯一标识
    public ChatAskResponse send(AuthenticatedIdentity identity, ChatAskRequest request, String requestIdHeader) {
        //=========================解析幂等requestId，校验会话归属权=========================
        UUID requestId = parseRequestId(requestIdHeader);
        ConversationEntity conversation = requireOwned(identity, request.conversationId());

        //=========================打开幂等请求Gate，查询幂等记录；无记录则插入RUNNING用户消息记录=========================
        //聚合从数据库查询得到的消息实体、请求状态、历史消息等信息
        RequestGate gate = openRequestGate(conversation.getId(), request.question(), requestId);

        //=========================幂等记录已存在，直接走幂等回放分支，不再调用Python AI服务=========================
        if (!gate.createdNow()) {
            return replayOrReject(gate);
        }

        //=========================基于幂等requestId，生成runId、traceId，用于跨Java与Python服务全链路追踪=========================
        UUID runId = stableUuid(requestId, "run");//本次 AI 执行任务编号 runId
        UUID traceId = stableUuid(requestId, "trace");//本次请求跟踪编号 traceId
        try {
            agentAudit.start(requestId, runId, traceId);

            //=========================组装请求参数，调用Python AI服务发起问答请求=========================
            AiAskResponse aiResponse = aiAskClient.ask(new AiAskRequest(
                    request.question(), conversation.getId(), identity.userId(),
                    gate.history().stream().map(message -> new AiAskRequest.HistoryItem(
                            message.getId(), message.getRole(), message.getContent())).toList(),
                    gate.history().isEmpty() ? null : gate.history().getLast().getId(),
                    identity.username(), false, requestId, runId, traceId, true));

            //=========================校验Python返回的runId、traceId，必须和下发的链路ID一致=========================
            if (!runId.equals(aiResponse.runId()) || !traceId.equals(aiResponse.traceId())) {
                throw AiServiceClientException.contract(
                        "Python ask response identifiers do not match the request", null);
            }

            //=========================校验必须为真实业务结果，不允许mock模拟数据=========================
            if (!Boolean.FALSE.equals(aiResponse.mock())) {
                throw AiServiceClientException.contract("real stage-5 ask must return mock=false", null);
            }

            //=========================校验AI执行状态必须为COMPLETED正常完成=========================
            if (!"COMPLETED".equals(aiResponse.executionTrace().status())) {
                throw AiServiceClientException.contract(
                        "real stage-5 ask must return a COMPLETED execution trace", null);
            }
            //AI 服务调用成功后，在独立事务内完成结果持久化与状态更新，返回ChatAskResponse。
            return persistSuccess(identity.userId(), conversation.getId(), request.question(), requestId, aiResponse);
        } catch (AiServiceClientException exception) {
            //persistFailure：在独立事务内持久化 AI 调用失败的请求状态与错误信息，返回布尔值表示本次失败状态更新是否成功
            //可读取`FAILED`记录，直接走`existingGate`进入幂等分支，不会再次调用 Python AI 服务
            if (persistFailure(conversation.getId(), requestId,
                    exception.httpStatus(), exception.businessCode(), exception.safeMessage())) {
                throw exception;
            }
            //返回ChatAskResponse对象
            return replayOrReject(readExistingRequest(conversation.getId(), request.question(), requestId));

            //RequestTerminalFailureException（业务不可恢复异常，例如数据脏数据、快照缺失、状态不一致）
        } catch (RequestTerminalFailureException exception) {
            if (persistFailure(conversation.getId(), requestId, 500, 5000, "internal server error")) {
                throw exception;
            }
            return replayOrReject(readExistingRequest(conversation.getId(), request.question(), requestId));

//            //通用运行异常时，persistFailure**失败** → 幂等回放；成功 → 包装抛出新终端异常。
        } catch (RuntimeException exception) {
            if (!persistFailure(conversation.getId(), requestId, 500, 5000, "internal server error")) {
                return replayOrReject(readExistingRequest(conversation.getId(), request.question(), requestId));
            }
            throw new RequestTerminalFailureException("message terminal transaction failed", exception);
        } finally {
            agentAudit.reconcileSafely(requestId);
        }
    }

    /**
     定时任务，用来捞取长时间卡在 RUNNING 状态、超时未完成的 AI 请求，自动把它们标记为 FAILED
     */
    @Scheduled(fixedDelayString = "${doctrail.message.recovery-scan-interval:60s}")
    public void recoverTimedOutRequests() {
        OffsetDateTime cutoff = utcNow().minus(requestRunningTimeout);
        try {
            List<UUID> recoveredRequestIds = new java.util.ArrayList<>();
            //开启独立事务执行本次扫描 + 更新操作；`recovered` 最后返回本次一共修复了多少条超时记录。
            int recovered = inIndependentTransaction(() -> {
                //查询`requestState = RUNNING` 的请求记录；创建时间 < cutoff（已经超时）
                List<MessageEntity> requests = messageMapper.selectTimedOutRunningForUpdate(cutoff);
                int updated = 0;
                for (MessageEntity request : requests) {
                    try {
                        validatePersistedState(request, null, MessageRequestState.RUNNING);
                    } catch (RequestTerminalFailureException invalid) {
                        logger.error("Skipping inconsistent RUNNING message request id={}", request.getId());
                        continue;
                    }
                    request.setRequestState(MessageRequestState.FAILED.name());
                    request.setRequestErrorHttp(500);
                    request.setRequestErrorCode(5000);
                    request.setRequestErrorMessage(RECOVERY_ERROR_MESSAGE);
                    request.setAnswerSnapshot(null);
                    request.setAnswerSnapshotVersion(null);
                    if (messageMapper.updateById(request) != 1) {
                        throw new RequestTerminalFailureException("failed to recover timed-out request");
                    }
                    recoveredRequestIds.add(UUID.fromString(request.getRequestId()));
                    updated++;
                }
                return updated;
            });
            // **对每一个 requestId 调用 `agentAudit.reconcileSafely(requestId)`**。
            recoveredRequestIds.forEach(agentAudit::reconcileSafely);
            if (recovered > 0) {
                logger.warn("Recovered {} timed-out RUNNING message requests", recovered);
            }
        } catch (RuntimeException failure) {
            logger.error("Message request recovery transaction failed; a later scan will retry", failure);
        }
    }

    //`openRequestGate()` 方法在Java 本地数据库事务内构造生成，
    // 聚合从数据库查询得到的消息实体、请求状态、历史消息等信息返回RequestGate，用于承载幂等校验结果
    private RequestGate openRequestGate(Long conversationId, String question, UUID requestId) {
        try {
            //inIndependentTransaction：开启一个独立数据库事务，在事务内部执行查询操作
            return inIndependentTransaction(() -> {
                MessageEntity existing = selectRequestForUpdate(requestId);
                if (existing != null) {
                    //基于数据库中已存在的请求记录，构建幂等分支所需的`RequestGate`对象，标记为非新建请求
                    return existingGate(existing, conversationId, question);
                }
                // 查询当前会话可用的历史对话消息，作为传给 Python AI 服务的上下文
                List<MessageEntity> history = selectUsableHistory(conversationId);
                //新建MessageEntity对象
                MessageEntity running = newRunningMessage(conversationId, question, requestId);
                if (messageMapper.insert(running) != 1) {
                    throw new RequestTerminalFailureException("failed to create running request row");
                }
                //构建`RequestGate`对象，标记为新建请求
                return new RequestGate(running, null, MessageRequestState.RUNNING, true, history);
            });
            //`DuplicateKeyException`：唯一索引冲突，多个请求同时插入同一个`requestId`记录
            //`PessimisticLockingFailureException`：悲观锁获取失败，多个并发请求争抢同一条记录的行锁
        } catch (DuplicateKeyException | PessimisticLockingFailureException race) {
            RequestGate existing = inIndependentTransaction(() -> {
                //在独立数据库事务内查询请求记录，执行带悲观行锁的 SQL 查询
                MessageEntity row = selectRequestForUpdate(requestId);
                //如果查到记录，通过`existingGate`构造`RequestGate`对象返回，进入幂等分支
                if (row == null) return null;
                return existingGate(row, conversationId, question);
            });
            if (existing != null) return existing;
            throw race;
        }
    }

    //读取并校验现有请求记录，进入幂等分支
    private RequestGate readExistingRequest(Long conversationId, String question, UUID requestId) {
        return inIndependentTransaction(() -> {
            MessageEntity existing = selectRequestForUpdate(requestId);
            if (existing == null) {
                throw new RequestTerminalFailureException("request row disappeared before replay");
            }
            return existingGate(existing, conversationId, question);
        });
    }

    //基于数据库中已存在的请求记录，构建幂等分支所需的`RequestGate`对象
    private RequestGate existingGate(MessageEntity existing, Long conversationId, String question) {
        validateRequestIdentity(existing, conversationId, question);
        MessageRequestState state = stateOf(existing);
        MessageEntity assistant = selectAssistant(existing.getId());
//        校验数据库持久化存储的请求记录状态与附属数据之间的一致性
        validatePersistedState(existing, assistant, state);
//        RequestGate标记为非新建请求
        return new RequestGate(existing, assistant, state, false, List.of());
    }

    //如果请求已存在，则进入这个方法
    private ChatAskResponse replayOrReject(RequestGate gate) {
        MessageEntity request = gate.request();
        MessageRequestState state = gate.state();
        if (state != MessageRequestState.RUNNING) {
            agentAudit.reconcileSafely(UUID.fromString(request.getRequestId()));
        }
        return switch (state) {
            case RUNNING -> throw new RequestInProgressException("request is still running");
            case FAILED -> throw new PersistedRequestFailureException(
                    request.getRequestErrorHttp(), request.getRequestErrorCode(), request.getRequestErrorMessage());
            case SUCCEEDED -> replaySnapshot(request, gate.assistant());
        };
    }

    // 校验成功后返回应答对象
    private ChatAskResponse replaySnapshot(MessageEntity request, MessageEntity assistant) {
        //request.getAnswerSnapshot()：AI 请求成功后，将对外响应对象`ChatAskResponse`
        if (request.getAnswerSnapshot() == null
                || !Objects.equals(request.getAnswerSnapshotVersion(), ANSWER_SNAPSHOT_VERSION)) {
            throw new RequestTerminalFailureException("successful request has no supported answer snapshot");
        }
        try {
            //从数据库中读取的 JSON 字符串，转换为 Java 对象 `ChatAskResponse`
            ChatAskResponse response = objectMapper.readerFor(ChatAskResponse.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)//配置反序列化特性
                    .readValue(request.getAnswerSnapshot());//读取数据库`answerSnapshot`字段存储的 JSON 字符串，执行反序列化
            //校验对外输出的应答对象 `ChatAskResponse` 的完整性与接口契约合规性
            validatePublicResponse(response, false);
            //校验应答对象 `ChatAskResponse` 与原始请求问题的匹配性
            if (assistant == null || !Objects.equals(assistant.getContent(), response.answer())) {
                //如果应答对象 `ChatAskResponse` 与原始请求问题的匹配性校验失败，则抛出异常
                throw new RequestTerminalFailureException("successful request assistant and snapshot do not match");
            }
            return response;
        } catch (JsonProcessingException exception) {
            throw new RequestTerminalFailureException("stored answer snapshot is invalid", exception);
        }
    }


    //**AI 服务调用成功后，在独立事务内完成结果持久化与状态更新**。
    private ChatAskResponse persistSuccess(
            Long userId, Long conversationId, String question, UUID requestId, AiAskResponse response
    ) {
        return inIndependentTransaction(() -> {
            //=========================在独立数据库事务内查询请求记录，执行带悲观行锁的 SQL 查询=========================
            //在独立数据库事务内查询请求记录，执行带悲观行锁的 SQL 查询（`openRequestGate` 里面本身包含先查询，查不到就插入的逻辑）
            MessageEntity request = selectRequestForUpdate(requestId);
            if (request == null || !Objects.equals(request.getConversationId(), conversationId)
                    || !"user".equals(request.getRole())) {
                throw new RequestTerminalFailureException("request row disappeared before terminal write");
            }
            if (!MessageRequestState.RUNNING.name().equals(request.getRequestState())) {
                throw new RequestTerminalFailureException("request is no longer running");
            }
            //===================   ==校验持久化记录状态一致性，RUNNING状态不能存在助手消息、快照、错误字段=========================
            //校验数据库持久化存储的请求记录状态与附属数据之间的一致性
            validatePersistedState(request, null, MessageRequestState.RUNNING);
            if (hasAssistant(request.getId())) {
                throw new RequestTerminalFailureException("running request already has an assistant message");
            }
           //========================将Python返回的内部AiAskResponse转换为对外响应模型ChatAskResponse，并校验对外响应对象合法性=========================
            ChatAskResponse publicResponse = ChatAskResponse.from(response);
            validatePublicResponse(publicResponse, true);

            //=========================将对外响应对象序列化为JSON字符串，生成应答快照=========================
            final String snapshot;
            try {
                //把 Java 对象`publicResponse`（ChatAskResponse 对外响应对象）转换成 JSON 格式字符串。
                snapshot = objectMapper.writeValueAsString(publicResponse);
            } catch (JsonProcessingException exception) {
                throw new RequestTerminalFailureException("failed to serialize answer snapshot", exception);
            }

            //=========================构造assistant助手消息实体，插入message表，保存AI回答纯文本=========================
            MessageEntity assistant = new MessageEntity();
            assistant.setConversationId(conversationId);
            assistant.setRole("assistant");
            assistant.setContent(response.answer());
            assistant.setReplyToMessageId(request.getId());
            assistant.setCreateTime(utcNow());
            if (messageMapper.insert(assistant) != 1) {
                throw new RequestTerminalFailureException("failed to persist assistant message");
            }

            //=========================构造问答日志实体，序列化检索来源，插入qa_log表，用于审计统计=========================
            QaLogEntity qaLog = new QaLogEntity();
            qaLog.setUserId(userId);
            qaLog.setConversationId(conversationId);
            qaLog.setRequestId(requestId.toString());
            qaLog.setQuestion(question);
            qaLog.setAnswer(response.answer());
            try {
                qaLog.setSources(objectMapper.writeValueAsString(publicResponse.sources()));
            } catch (JsonProcessingException exception) {
                throw new RequestTerminalFailureException("failed to serialize qa_log sources", exception);
            }
            qaLog.setTraceId(response.traceId().toString());
            qaLog.setCreateTime(utcNow());
            if (qaLogMapper.insert(qaLog) != 1) {
                throw new RequestTerminalFailureException("failed to persist successful qa log");
            }

            //=========================更新用户消息记录：修改状态为SUCCEEDED，写入应答快照与版本号，清空错误字段=========================
            request.setRequestState(MessageRequestState.SUCCEEDED.name());
            request.setAnswerSnapshot(snapshot);
            request.setAnswerSnapshotVersion(ANSWER_SNAPSHOT_VERSION);
            request.setRequestErrorHttp(null);
            request.setRequestErrorCode(null);
            request.setRequestErrorMessage(null);
            if (messageMapper.updateById(request) != 1) {
                throw new RequestTerminalFailureException("failed to update successful request state");
            }
            //=========================返回校验完成的对外响应对象ChatAskResponse=========================
            return publicResponse;
        });
    }

    //在独立事务内持久化 AI 调用失败的请求状态与错误信息，返回布尔值表示本次失败状态更新是否成功
    private boolean persistFailure(
            Long conversationId, UUID requestId, int httpStatus, int businessCode, String safeMessage
    ) {
        try {
            //事务执行完成后获取返回布尔值，再判断是否为 true
            return Boolean.TRUE.equals(inIndependentTransaction(() -> {
                MessageEntity request = selectRequestForUpdate(requestId);
                if (request == null || !Objects.equals(request.getConversationId(), conversationId)) {
                    throw new RequestTerminalFailureException("request row disappeared before failure write");
                }
                if (!MessageRequestState.RUNNING.name().equals(request.getRequestState())) {
                    return false;
                }
                validatePersistedState(request, null, MessageRequestState.RUNNING);
                if (hasAssistant(request.getId())) {
                    throw new RequestTerminalFailureException("running request already has an assistant message");
                }
                request.setRequestState(MessageRequestState.FAILED.name());
                request.setRequestErrorHttp(httpStatus);
                request.setRequestErrorCode(businessCode);
                request.setRequestErrorMessage(safeMessage);
                request.setAnswerSnapshot(null);
                request.setAnswerSnapshotVersion(null);
                if (messageMapper.updateById(request) != 1) {
                    throw new RequestTerminalFailureException("failed to persist failed request state");
                }
                return true;
            }));
        } catch (RuntimeException failure) {
            throw new RequestTerminalFailureException("failed to persist AI request failure", failure);
        }
    }

    //  查询并筛选当前会话可用的历史对话消息，作为传给 Python AI 服务的上下文
    private List<MessageEntity> selectUsableHistory(Long conversationId) {
        List<MessageEntity> messages = messageMapper.selectList(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getConversationId, conversationId)
                .and(query -> query.ne(MessageEntity::getRole, "user")
                        .or().isNull(MessageEntity::getRequestState)
                        .or().eq(MessageEntity::getRequestState, MessageRequestState.SUCCEEDED.name()))
                //先按 message_id 倒序取最近消息，再在内存中恢复为提示词需要的升序
                .orderByDesc(MessageEntity::getId)
                //数据库层面限制最多读取`MAX_HISTORY_MESSAGES`条
                .last("LIMIT " + MAX_HISTORY_MESSAGES)).stream()
                .filter(this::isUsableHistoryMessage)
                .sorted((left, right) -> left.getId().compareTo(right.getId()))
                .toList();
        return boundHistory(messages);
    }

    // 对历史消息做字节容量截断，从最新消息开始累加预估字节，达到总字节上限即停止
    private List<MessageEntity> boundHistory(List<MessageEntity> history) {
        List<MessageEntity> selected = new java.util.ArrayList<>();
        long totalBytes = 0;
        for (int index = history.size() - 1; index >= 0; index--) {
            MessageEntity message = history.get(index);
            //若`message.getContent()`返回值不为`null`，则直接取该值；
            // 若返回值为`null`，则使用空字符串`""`作为兜底默认值。
            String content = Objects.requireNonNullElse(message.getContent(), "");
            //计算单条历史消息预估占用的字节大小
            long itemBytes = content.getBytes(StandardCharsets.UTF_8).length + HISTORY_MESSAGE_OVERHEAD_BYTES;
            //若当前历史消息累加的字节大小超过限制，则跳出循环
            if (totalBytes + itemBytes > MAX_HISTORY_UTF8_BYTES) {
                //跳出循环，停止添加历史消息
                break;
            }
            selected.add(0, message);
            totalBytes += itemBytes;
        }
        return List.copyOf(selected);
    }

    //消息能不能当做对话上下文（历史消息）传给 Python AI
    private boolean isUsableHistoryMessage(MessageEntity message) {
        if (!"user".equals(message.getRole())) return true;
        return message.getRequestState() == null
                || MessageRequestState.SUCCEEDED.name().equals(message.getRequestState());
    }

    //校验数据库持久化存储的请求记录状态与附属数据之间的一致性
    private void validatePersistedState(
            MessageEntity request, MessageEntity assistant, MessageRequestState state
    ) {
        boolean hasAssistant = assistant != null;
        switch (state) {
            case RUNNING -> {
                if (hasAssistant || request.getAnswerSnapshot() != null
                        || request.getAnswerSnapshotVersion() != null || hasRequestErrorFields(request)) {
                    throw new RequestTerminalFailureException("running request has terminal data");
                }
            }
            case SUCCEEDED -> {
                if (!hasAssistant || request.getAnswerSnapshot() == null
                        || !Objects.equals(request.getAnswerSnapshotVersion(), ANSWER_SNAPSHOT_VERSION)
                        || hasRequestErrorFields(request)) {
                    throw new RequestTerminalFailureException("successful request is missing terminal data");
                }
            }
            case FAILED -> {
                if (hasAssistant || request.getAnswerSnapshot() != null
                        || request.getAnswerSnapshotVersion() != null
                        || request.getRequestErrorHttp() == null || request.getRequestErrorCode() == null
                        || request.getRequestErrorHttp() < 400 || request.getRequestErrorHttp() > 599
                        || request.getRequestErrorCode() <= 0
                        || request.getRequestErrorMessage() == null || request.getRequestErrorMessage().isBlank()
                        || request.getRequestErrorMessage().length() > 500) {
                    throw new RequestTerminalFailureException("failed request is missing consistent terminal data");
                }
            }
        }
    }

    private boolean hasRequestErrorFields(MessageEntity request) {
        return request.getRequestErrorHttp() != null || request.getRequestErrorCode() != null
                || request.getRequestErrorMessage() != null;
    }

    //是否存在ai消息
    private boolean hasAssistant(Long requestMessageId) {
        return messageMapper.selectCount(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getReplyToMessageId, requestMessageId)
                .eq(MessageEntity::getRole, "assistant")) > 0;
    }

    private MessageEntity selectAssistant(Long requestMessageId) {
        return messageMapper.selectOne(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getReplyToMessageId, requestMessageId)
                .eq(MessageEntity::getRole, "assistant")
                .last("LIMIT 1"));
    }

    // 根据请求 ID 查询请求消息，并加锁
    private MessageEntity selectRequestForUpdate(UUID requestId) {
        return messageMapper.selectOne(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getRequestId, requestId.toString())
                .last("LIMIT 1 FOR UPDATE"));
    }

    private void validateRequestIdentity(MessageEntity existing, Long conversationId, String question) {
        if (!Objects.equals(existing.getConversationId(), conversationId)
                || !"user".equals(existing.getRole())
                || !Objects.equals(existing.getContent(), question)) {
            throw new RequestIdConflictException("request id has already been used");
        }
    }

    //**requestState转换成枚举 `MessageRequestState`
    private MessageRequestState stateOf(MessageEntity request) {
        try {
            if (request.getRequestState() == null) throw new IllegalArgumentException("missing state");
            return MessageRequestState.valueOf(request.getRequestState());
        } catch (IllegalArgumentException exception) {
            throw new RequestTerminalFailureException("request row has an invalid state", exception);
        }
    }

    private MessageEntity newRunningMessage(Long conversationId, String question, UUID requestId) {
        MessageEntity message = new MessageEntity();
        message.setConversationId(conversationId);
        message.setRole("user");
        message.setContent(question);
        message.setRequestId(requestId.toString());
        message.setRequestState(MessageRequestState.RUNNING.name());
        message.setCreateTime(utcNow());
        return message;
    }

    //校验对外输出的应答对象 `ChatAskResponse` 的完整性与接口契约合规性
    private void validatePublicResponse(ChatAskResponse response, boolean requireRealResult) {
        //requireRealResult：标记是否要求本次应答为**真实业务结果，不允许 mock 模拟结果**
        if (response == null || response.answer() == null || response.sources() == null
                || response.taskType() == null || response.runId() == null || response.traceId() == null
                || response.executionTrace() == null || response.executionTrace().steps() == null
                || response.executionTrace().toolCalls() == null
                || response.mock() == null
                || (requireRealResult && !Boolean.FALSE.equals(response.mock()))) {
            throw new RequestTerminalFailureException("answer response is incomplete or not a real result");
        }
        if (!java.util.Set.of("knowledge_qa", "retrieval", "chitchat", "reasoning")
                .contains(response.taskType())) {
            throw new RequestTerminalFailureException("answer response has an unsupported task type");
        }
        if (!java.util.Set.of("COMPLETED", "FAILED", "CANCELLED")
                .contains(response.executionTrace().status())) {
            throw new RequestTerminalFailureException("answer response has an unsupported execution status");
        }
        if (response.sources().stream().anyMatch(source -> source == null || source.docId() == null
                || source.docName() == null || (source.page() != null && source.page() < 1)
                || (source.chunkIndex() != null && source.chunkIndex() < 0)
                || (source.score() != null && (source.score() < 0 || source.score() > 1)))) {
            throw new RequestTerminalFailureException("answer response has invalid sources");
        }
        if (response.usage() != null && (response.usage().promptTokens() == null
                || response.usage().completionTokens() == null || response.usage().totalTokens() == null
                || response.usage().promptTokens() < 0 || response.usage().completionTokens() < 0
                || response.usage().totalTokens() < 0
                || (response.usage().estimatedCost() != null
                && !response.usage().estimatedCost().matches("\\d+(\\.\\d{1,6})?")))) {
            throw new RequestTerminalFailureException("answer response has invalid usage");
        }
        if (response.executionTrace().steps().stream().anyMatch(step -> step == null
                || step.stepId() == null || step.stepName() == null
                || !java.util.Set.of("RUNNING", "COMPLETED", "FAILED", "CANCELLED")
                .contains(step.status()) || (step.durationMs() != null && step.durationMs() < 0))) {
            throw new RequestTerminalFailureException("answer response has invalid trace steps");
        }
        if (response.executionTrace().toolCalls().stream().anyMatch(call -> call == null
                || call.toolCallId() == null || call.toolName() == null
                || !java.util.Set.of("RUNNING", "COMPLETED", "FAILED", "CANCELLED")
                .contains(call.status()) || (call.durationMs() != null && call.durationMs() < 0))) {
            throw new RequestTerminalFailureException("answer response has invalid trace tool calls");
        }
    }

//    会话资源的存在性校验与所属权鉴权
    private ConversationEntity requireOwned(AuthenticatedIdentity identity, long conversationId) {
        ConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) throw new ResourceNotFoundException("conversation not found");
        if (!Objects.equals(conversation.getUserId(), identity.userId())) {
            throw new ConversationAccessDeniedException("conversation does not belong to current user");
        }
        return conversation;
    }

//解析 HTTP 请求头传入的`X-Request-Id`字符串
    private UUID parseRequestId(String requestIdHeader) {
        try {
            return UUID.fromString(requestIdHeader);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("X-Request-Id must be a UUID", exception);
        }
    }

//    **基于原始幂等`requestId`和用途标识，生成稳定可复现的衍生 UUID**，用于唯一标识请求记录。
    private UUID stableUuid(UUID requestId, String purpose) {
        return UUID.nameUUIDFromBytes((purpose + ":" + requestId).getBytes(StandardCharsets.UTF_8));
    }

    private OffsetDateTime utcNow() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private <T> T inIndependentTransaction(java.util.function.Supplier<T> operation) {
        return independentTransaction.execute(status -> operation.get());
    }

    private record RequestGate(
            MessageEntity request,//用户侧消息记录
            MessageEntity assistant,//助手侧消息记录
            MessageRequestState state,//消息状态
            boolean createdNow,//是否新创建
            List<MessageEntity> history//消息历史
    ) {
    }

    private enum MessageRequestState {
        RUNNING,
        SUCCEEDED,
        FAILED
    }
}
