package com.doctrail.dto.ai.health;

import java.time.OffsetDateTime;

public record LivenessResponse(
        Status status,
        String service,
        OffsetDateTime timestamp,
        Boolean mock
) {
    public enum Status {
        UP
    }
}
