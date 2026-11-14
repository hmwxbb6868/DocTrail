package com.doctrail.mapper;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Select;

public interface DashboardStatsMapper {
    record Counts(long documentCount, long questionCount, long runCount, long completedRunCount, long finishedRunCount) {}

    @Select("""
            SELECT
                (SELECT COUNT(*) FROM knowledge_doc WHERE status = 'COMPLETED') AS document_count,
                (SELECT COUNT(*) FROM message
                 WHERE role = 'user' AND request_id IS NOT NULL
                   AND request_state IN ('SUCCEEDED', 'FAILED')) AS question_count,
                COUNT(*) AS run_count,
                COUNT(CASE WHEN status = 'COMPLETED' THEN 1 END) AS completed_run_count,
                COUNT(CASE WHEN status IN ('COMPLETED', 'FAILED') THEN 1 END) AS finished_run_count
            FROM agent_run
            """)
    @ConstructorArgs({
            @Arg(column = "document_count", javaType = long.class),
            @Arg(column = "question_count", javaType = long.class),
            @Arg(column = "run_count", javaType = long.class),
            @Arg(column = "completed_run_count", javaType = long.class),
            @Arg(column = "finished_run_count", javaType = long.class)
    })
    Counts countDashboardRecords();
}
