package com.doctrail.service.audit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.doctrail.dto.admin.response.AgentRunPageResponse;
import com.doctrail.dto.admin.response.AgentRunResponse;
import com.doctrail.dto.user.response.ChatAskResponse.TraceStep;
import com.doctrail.dto.user.response.ChatAskResponse.TraceToolCall;
import com.doctrail.entity.*;
import com.doctrail.exception.ResourceNotFoundException;
import com.doctrail.mapper.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/** 审计查询服务，专门用来查询已经落库的审计数据.对外提供分页列表、单条详情接口 */
@Service
@Profile("stage2-auth")
@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class AgentAuditQueryService {
    private final AgentRunMapper runs;
    private final AgentStepMapper steps;
    private final ToolCallMapper tools;
    private final ObjectMapper json;

    public AgentAuditQueryService(AgentRunMapper runs, AgentStepMapper steps, ToolCallMapper tools, ObjectMapper json) {
        this.runs = runs;
        this.steps = steps;
        this.tools = tools;
        this.json = json;
    }

    public AgentRunPageResponse list(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("invalid audit page");
        long total = runs.selectCount(Wrappers.emptyWrapper());
        long offset = ((long) page - 1) * size;
        var rows = runs.selectList(Wrappers.<AgentRunEntity>lambdaQuery()
                .orderByDesc(AgentRunEntity::getStartedAt, AgentRunEntity::getId)
                .last("LIMIT " + size + " OFFSET " + offset));
        return new AgentRunPageResponse(rows.stream().map(this::response).toList(), page, size, total, false);
    }

    public AgentRunResponse detail(UUID runId) {
        AgentRunEntity row = runs.selectOne(Wrappers.<AgentRunEntity>lambdaQuery()
                .eq(AgentRunEntity::getRunId, runId.toString()));
        if (row == null) throw new ResourceNotFoundException("agent run not found");
        return response(row);
    }

    private AgentRunResponse response(AgentRunEntity row) {
        var runSteps = steps.selectList(Wrappers.<AgentStepEntity>lambdaQuery()
                .eq(AgentStepEntity::getRunId, row.getRunId()).orderByAsc(AgentStepEntity::getId)).stream()
                .map(s -> new TraceStep(UUID.fromString(s.getStepId()), s.getStepName(), s.getStatus(),
                        s.getDurationMs(), s.getErrorCode(), s.getErrorMessage())).toList();
        var runTools = tools.selectList(Wrappers.<ToolCallEntity>lambdaQuery()
                .eq(ToolCallEntity::getRunId, row.getRunId()).orderByAsc(ToolCallEntity::getId)).stream()
                .map(t -> new TraceToolCall(UUID.fromString(t.getToolCallId()), t.getToolName(), t.getStatus(),
                        decode(t.getInputParams()), decode(t.getOutput()), t.getDurationMs(),
                        t.getErrorCode(), t.getErrorMessage())).toList();
        return new AgentRunResponse(UUID.fromString(row.getRunId()), UUID.fromString(row.getTraceId()),
                UUID.fromString(row.getRequestId()), row.getTaskType(), row.getStatus(), utc(row.getStartedAt()),
                utc(row.getFinishedAt()), runSteps, runTools, row.getErrorCode(), row.getErrorMessage(), false);
    }

    //把数据库里保存的 JSON 字符串，反序列化成 `Map<String,Object>`，方便组装返回 DTO 给前端
    private Map<String, Object> decode(String value) {
        if (value == null) return null;
        try {
            return json.readValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("invalid persisted tool audit JSON", failure);
        }
    }

    private OffsetDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }
}
