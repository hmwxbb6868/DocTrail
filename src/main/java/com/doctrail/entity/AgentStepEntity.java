package com.doctrail.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;

/** Java-owned audit row; schema is managed exclusively by Flyway. */
@Getter
@Setter
@TableName("agent_step")
public class AgentStepEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField(value = "run_id")
    private String runId;
    @TableField(value = "step_id")
    private String stepId;
    @TableField(value = "step_name")
    private String stepName;
    @TableField(value = "status")
    private String status;
    @TableField(value = "duration_ms", updateStrategy = FieldStrategy.ALWAYS)
    private Long durationMs; //本步骤耗时
    @TableField(value = "error_code", updateStrategy = FieldStrategy.ALWAYS)
    private Integer errorCode; //错误码
    @TableField(value = "error_message", updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage; //错误信息
    @TableField(value = "create_time")
    private OffsetDateTime createTime;//创建时间
}
