package com.doctrail.dto.ai.parse;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.UUID;

/** Java-side representation of the frozen Java-to-Python parse request. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiParseRequest(
        Long docId,
        String storageKey,
        String contentHash,//整个文档原始文件的哈希值
        boolean force,
        UUID traceId
) {
}
