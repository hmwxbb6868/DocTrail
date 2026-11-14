package com.doctrail.dto.ai.parse;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.UUID;

/** Java-side representation of the frozen Java-to-Python delete request. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiDeleteRequest(
        Long docId,
        UUID traceId
) {
}
