package com.doctrail.dto.user.response;

import java.time.OffsetDateTime;
import java.util.List;

public record MessageResponse(
        Long id,
        Long conversationId,
        String role,
        String content,
        OffsetDateTime createdAt,
        String feedback,
        List<ChatAskResponse.Source> sources,
        boolean mock
) {
}
