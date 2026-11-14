package com.doctrail.dto.operations;

public record JavaLivenessResponse(
        String status,
        String service,
        String timestamp
) {
}
