package com.doctrail.dto.admin.response;

import com.doctrail.dto.user.response.ChatAskResponse.TraceStep;
import com.doctrail.dto.user.response.ChatAskResponse.TraceToolCall;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AgentRunResponse(
        UUID runId,
        UUID traceId,
        UUID requestId,
        String taskType,
        String status,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        List<TraceStep> steps,
        List<TraceToolCall> toolCalls,
        Integer errorCode,
        String errorMessage,
        boolean mock
) {
}
