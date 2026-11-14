package com.doctrail.dto.ai.parse;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.UUID;

/** Java-side representation of the frozen Java-to-Python delete response. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiDeleteResponse(
        Long docId,
        String status,
        Integer deletedChunkCount,
        UUID traceId,
        String errorMessage
) {
}
