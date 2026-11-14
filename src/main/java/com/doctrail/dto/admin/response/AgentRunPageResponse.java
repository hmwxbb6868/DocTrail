package com.doctrail.dto.admin.response;

import java.util.List;

public record AgentRunPageResponse(
        List<AgentRunResponse> items,
        int page,
        int size,
        long total,
        boolean mock
) {
}
