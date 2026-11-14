package com.doctrail.dto.user.response;

import java.time.OffsetDateTime;

public record ConversationResponse(
        Long id,//会话的唯一编号
        String title,//会话标题
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        boolean mock
) {
}
