package com.doctrail.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;

/** Java-owned audit row; schema is managed exclusively by Flyway. */
@Getter
@Setter
@TableName("agent_run")
//单次 AI Agent 请求的审计主记录
public class AgentRunEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField(value = "run_id")
    private String runId;  //Agent执行任务编号，一次AI Agent任务的标识
    @TableField(value = "trace_id")
    private String traceId;  // 跟踪编号
    @TableField(value = "request_id")
    private String requestId; // AI请求业务唯一标识，【和MessageEntity里requestId是同一个值！】
    @TableField(value = "conversation_id")
    private Long conversationId; // 会话编号
    @TableField(value = "task_type", updateStrategy = FieldStrategy.ALWAYS)
    private String taskType; // 任务类型
    @TableField(value = "status")
    private String status; // 状态
    @TableField(value = "started_at")
    private OffsetDateTime startedAt; // 开始时间
    @TableField(value = "finished_at", updateStrategy = FieldStrategy.ALWAYS)
    private OffsetDateTime finishedAt; // 结束时间
    @TableField(value = "error_code", updateStrategy = FieldStrategy.ALWAYS)
    private Integer errorCode; // 错误码
    @TableField(value = "error_message", updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage; // 错误信息
    @TableField(value = "create_time")
    private OffsetDateTime createTime; // 创建时间
    @TableField(value = "update_time")
    private OffsetDateTime updateTime; // 更新时间
}
