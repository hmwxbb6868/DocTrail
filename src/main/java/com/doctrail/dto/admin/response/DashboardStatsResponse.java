package com.doctrail.dto.admin.response;

public record DashboardStatsResponse(
        long documentCount,
        long questionCount,
        long runCount,
        double successRate,
        long finishedRunCount,
        boolean mock
) {
}
