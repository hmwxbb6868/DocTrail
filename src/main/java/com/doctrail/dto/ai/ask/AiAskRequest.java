package com.doctrail.dto.ai.ask;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiAskRequest(
        String question,
        Long conversationId,
        Long userId,
        List<HistoryItem> history,
        Long historyVersion,// 历史版本号（你代码里传的是上一条消息id）
        String username,// 用户名称
        boolean isAdmin,
        UUID requestId,// X-Request-Id，前端生成的请求追踪ID，前端 → Java 后端
        UUID runId,// 运行ID——一次 AI 运行任务
        UUID traceId,// 跟踪ID——跨服务全链路（Java ↔ Python AI 服务）
        boolean includeTrace// 是否在AI返回结果里附带详细内部推理trace信息
) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record HistoryItem(Long messageId, String role, String content) {
    }
}
