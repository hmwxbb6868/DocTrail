package com.doctrail.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;

/** Java-owned audit row; schema is managed exclusively by Flyway. */
@Getter
@Setter
@TableName("tool_call")
public class ToolCallEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField(value = "run_id")
    private String runId;
    @TableField(value = "tool_call_id")
    private String toolCallId;
    @TableField(value = "tool_name")
    private String toolName;
    @TableField(value = "status")
    private String status;
    @TableField(value = "input_params", updateStrategy = FieldStrategy.ALWAYS)
    private String inputParams;
    @TableField(value = "output", updateStrategy = FieldStrategy.ALWAYS)
    private String output;
    @TableField(value = "duration_ms", updateStrategy = FieldStrategy.ALWAYS)
    private Long durationMs;
    @TableField(value = "error_code", updateStrategy = FieldStrategy.ALWAYS)
    private Integer errorCode;
    @TableField(value = "error_message", updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;
    @TableField(value = "create_time")
    private OffsetDateTime createTime;
}
