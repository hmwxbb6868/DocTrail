package com.doctrail.service.audit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.doctrail.dto.user.response.ChatAskResponse;
import com.doctrail.entity.*;
import com.doctrail.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Independent audit transactions. Never owns, rolls back or re-executes an AI answer. */
@Service
@Profile("stage2-auth")
public class AgentAuditService {
    private static final Logger log = LoggerFactory.getLogger(AgentAuditService.class);
    private final MessageMapper messages;
    private final AgentRunMapper runs;
    private final AgentStepMapper steps;
    private final ToolCallMapper tools;
    private final ObjectMapper json;
//    编程式事务模板，用于执行独立审计事务，与主业务事务隔离
    private final TransactionTemplate transaction;

    public AgentAuditService(MessageMapper messages, AgentRunMapper runs, AgentStepMapper steps,
                             ToolCallMapper tools, ObjectMapper json, PlatformTransactionManager manager) {
        this.messages = messages;
        this.runs = runs;
        this.steps = steps;
        this.tools = tools;
        this.json = json;
        //实例化**编程式事务模板对象**
        transaction = new TransactionTemplate(manager);
//      //采用REQUIRES_NEW传播机制，确保审计事务与主业务事务隔离（挂起当前外层已存在的事务）
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 审计记录初始化（AI 调用前执行） */
    public void start(UUID requestId, UUID runId, UUID traceId) {
        transaction.executeWithoutResult(tx -> {
            MessageEntity request = lockMessage(requestId);
            //如果请求不存在，或者请求角色不是用户，或者请求状态不是运行中，则抛出异常
            require(request != null && "user".equals(request.getRole())
                    && "RUNNING".equals(request.getRequestState()), "request is no longer running");
            //根据requestId查询审计记录
            AgentRunEntity existing = lockRun(requestId);
            if (existing != null) {
                //身份一致性校验
                requireIdentity(existing, request, runId.toString(), traceId.toString());
                require("RUNNING".equals(existing.getStatus()), "audit is already terminal");
                return;
            }
            AgentRunEntity run = new AgentRunEntity();
            run.setRunId(runId.toString());
            run.setTraceId(traceId.toString());
            run.setRequestId(requestId.toString());
            run.setConversationId(request.getConversationId());
            run.setStatus("RUNNING");
            run.setStartedAt(now());
            run.setCreateTime(run.getStartedAt());
            run.setUpdateTime(run.getStartedAt());
            require(runs.insert(run) == 1, "failed to create audit run");
        });
    }

    //安全地执行审计收尾逻辑，捕获 `reconcile()` 抛出的运行时异常，不把异常向上抛，交给后台补偿扫描任务后续重试。
    public void reconcileSafely(UUID requestId) {
        try {
            reconcile(requestId);
        } catch (RuntimeException failure) {
            log.warn("Agent audit compensation pending for requestId={}; a later scan will retry", requestId);
        }
    }

    /** Lock order is always message -> run. All children and run terminal commit atomically. */
    public void reconcile(UUID requestId) {
        transaction.executeWithoutResult(tx -> {
            MessageEntity request = lockMessage(requestId);
            if (request == null || !"user".equals(request.getRole())
                    || "RUNNING".equals(request.getRequestState())) return;
            AgentRunEntity run = lockRun(requestId);
            // Do not fabricate audits for stage-5 historical requests.
            if (run == null || !"RUNNING".equals(run.getStatus())) return;
            require(Objects.equals(run.getConversationId(), request.getConversationId()), "audit owner mismatch");
            if ("SUCCEEDED".equals(request.getRequestState())) {
                //获取ai消息的ChatAskResponse对象
                ChatAskResponse answer = readSnapshot(request);
                requireIdentity(run, request, answer.runId(), answer.traceId());
                require(Boolean.FALSE.equals(answer.mock()) && answer.executionTrace() != null
                        && "COMPLETED".equals(answer.executionTrace().status()), "invalid successful trace");
                require(messages.selectCount(Wrappers.<MessageEntity>lambdaQuery()
                        .eq(MessageEntity::getReplyToMessageId, request.getId())
                        .eq(MessageEntity::getRole, "assistant")
                        .eq(MessageEntity::getContent, answer.answer())) == 1L, "successful assistant missing");
                // 遍历Agent所有执行步骤，保存至审计步骤明细表agent_step
                for (ChatAskResponse.TraceStep step : answer.executionTrace().steps()) saveStep(run, step);
                // 遍历Agent所有工具调用记录，保存至工具调用审计明细表tool_call
                for (ChatAskResponse.TraceToolCall tool : answer.executionTrace().toolCalls()) saveTool(run, tool);
                run.setTaskType(answer.taskType());
                run.setStatus("COMPLETED");
                run.setErrorCode(null);
                run.setErrorMessage(null);
            } else if ("FAILED".equals(request.getRequestState())) {
                require(request.getRequestErrorHttp() != null && request.getRequestErrorCode() != null
                        && request.getRequestErrorMessage() != null && !request.getRequestErrorMessage().isBlank()
                        && request.getAnswerSnapshot() == null, "failed request has incomplete error");
                require(messages.selectCount(Wrappers.<MessageEntity>lambdaQuery()
                        .eq(MessageEntity::getReplyToMessageId, request.getId())
                        .eq(MessageEntity::getRole, "assistant")) == 0L, "failed request has an assistant");
                run.setStatus("FAILED");
                run.setErrorCode(request.getRequestErrorCode());
                run.setErrorMessage(request.getRequestErrorMessage());
            } else {
                throw new IllegalStateException("unsupported message state");
            }
            // Explicit audit terminal timestamp; never infer it from update_time.
            run.setFinishedAt(now());
            run.setUpdateTime(run.getFinishedAt());
            require(runs.updateById(run) == 1, "failed to finish audit run");
        });
    }

    //兜底重试，用来处理主路径尝试审计失败的场景。
    @Scheduled(fixedDelayString = "${doctrail.message.recovery-scan-interval:60s}")
    public void compensatePendingAudits() {
        try {
            //遍历查询出来的待补偿 requestId 列表，逐个重试审计收尾
            //待补偿 requestId：AI 请求已经跑完成功 / 失败，但审计收尾还没完成的请求 ID
            for (String id : runs.selectPendingTerminalRequestIds()) {
                try {
                    reconcileSafely(UUID.fromString(id));
                } catch (IllegalArgumentException invalid) {
                    log.warn("Skipping audit with invalid request UUID");
                }
            }
        } catch (RuntimeException failure) {
            log.warn("Agent audit compensation scan unavailable; a later scan will retry");
        }
    }

    //执行步骤，保存至审计步骤明细表agent_step
    private void saveStep(AgentRunEntity run, ChatAskResponse.TraceStep trace) {
        AgentStepEntity row = steps.selectOne(Wrappers.<AgentStepEntity>lambdaQuery()
                .eq(AgentStepEntity::getStepId, trace.stepId().toString()).last("FOR UPDATE"));
        boolean insert = row == null;
        if (insert) {
            row = new AgentStepEntity();
            row.setStepId(trace.stepId().toString());
            row.setRunId(run.getRunId());
            row.setCreateTime(now());
        }
        require(Objects.equals(row.getRunId(), run.getRunId()), "step UUID belongs to another run");
        row.setStepName(trace.stepName());
        row.setStatus(trace.status());
        row.setDurationMs(trace.durationMs());
        row.setErrorCode(trace.errorCode());
        row.setErrorMessage(trace.errorMessage());
        require((insert ? steps.insert(row) : steps.updateById(row)) == 1, "failed to save audit step");
    }

    //工具调用记录保存至工具调用审计明细表tool_call
    private void saveTool(AgentRunEntity run, ChatAskResponse.TraceToolCall trace) {
        ToolCallEntity row = tools.selectOne(Wrappers.<ToolCallEntity>lambdaQuery()
                .eq(ToolCallEntity::getToolCallId, trace.toolCallId().toString()).last("FOR UPDATE"));
        boolean insert = row == null;
        if (insert) {
            row = new ToolCallEntity();
            row.setToolCallId(trace.toolCallId().toString());
            row.setRunId(run.getRunId());
            row.setCreateTime(now());
        }
        require(Objects.equals(row.getRunId(), run.getRunId()), "tool UUID belongs to another run");
        row.setToolName(trace.toolName());
        row.setStatus(trace.status());
        row.setInputParams(encode(trace.inputParams()));
        row.setOutput(encode(trace.output()));
        row.setDurationMs(trace.durationMs());
        row.setErrorCode(trace.errorCode());
        row.setErrorMessage(trace.errorMessage());
        require((insert ? tools.insert(row) : tools.updateById(row)) == 1, "failed to save tool audit");
    }

    //从 Message 消息实体的`answerSnapshot`，反序列化成 Java 对象 ChatAskResponse
    private ChatAskResponse readSnapshot(MessageEntity request) {
        require(Objects.equals(request.getAnswerSnapshotVersion(), 1) && request.getAnswerSnapshot() != null,
                "unsupported answer snapshot");
        try {
            return json.readerFor(ChatAskResponse.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(request.getAnswerSnapshot());
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("invalid answer snapshot", failure);
        }
    }

    private String encode(Object value) {
        if (value == null) return null;
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("invalid tool audit JSON", failure);
        }
    }

    //根据requestId查询消息记录，并对该行添加悲观排他锁（FOR UPDATE）
    private MessageEntity lockMessage(UUID requestId) {
        return messages.selectOne(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getRequestId, requestId.toString()).last("LIMIT 1 FOR UPDATE"));
    }

    //根据requestId查询审计记录，并对该行添加悲观排他锁（FOR UPDATE）
    private AgentRunEntity lockRun(UUID requestId) {
        return runs.selectOne(Wrappers.<AgentRunEntity>lambdaQuery()
                .eq(AgentRunEntity::getRequestId, requestId.toString()).last("LIMIT 1 FOR UPDATE"));
    }

    //身份一致性校验，校验审计主记录 `AgentRunEntity` 和消息记录、入参的标识信息全部匹配
    private void requireIdentity(AgentRunEntity run, MessageEntity request, String runId, String traceId) {
        require(Objects.equals(run.getRequestId(), request.getRequestId())
                && Objects.equals(run.getConversationId(), request.getConversationId())
                && Objects.equals(run.getRunId(), runId) && Objects.equals(run.getTraceId(), traceId),
                "audit identifiers do not match message snapshot");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
