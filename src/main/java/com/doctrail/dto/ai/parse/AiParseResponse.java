package com.doctrail.dto.ai.parse;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.UUID;

/** Java-side representation of the frozen Java-to-Python parse response. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiParseResponse(
        Long docId,
        String status,//解析状态，表示 AI 解析任务的当前状态。
        UUID batchId,//批次 ID，本次 AI 解析任务所属批次标识。
        Integer chunkCount,//分块数量，表示文档被分成多少块进行解析。
        UUID indexBuildId,//索引构建 ID，本次 AI 解析任务所属索引构建标识。
        UUID traceId,//跟踪 ID，用于跟踪和排查问题。
        String errorMessage//错误信息，如果解析失败，这里会包含错误原因。
) {
}
