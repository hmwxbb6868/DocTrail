package com.doctrail.dto.ai.health;

import java.time.OffsetDateTime;
import java.util.List;

public record ReadinessResponse(
        Status status,
        String service,
        OffsetDateTime timestamp,
        Boolean mock,
        List<ReadinessCheck> checks
) {
    public enum Status {
        READY,
        NOT_READY
    }
}
