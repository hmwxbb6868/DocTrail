package com.doctrail.dto.user.response;

import java.time.OffsetDateTime;

public record KnowledgeDocumentResponse(
        Long id,
        String name,
        String status,//文档状态，常见取值：`pending`等待解析、`success`解析成功、`failed`解析失败
        String failureReason,//失败原因
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        boolean mock
) {
}
