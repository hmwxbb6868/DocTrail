package com.doctrail.dto.ai.ask;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;
import java.util.UUID;
/*
`AiAskResponse`：**后端内部调用 AI 大模型服务拿到的返回对象
* 这 4 个 record 全是用来表示【内部 AI 服务返回数据】的模型
* iAskResponse里面引用另外 3 个。
Source / Usage / ExecutionTrace 是AiAskResponse 的内部静态 Record
*/
// Jackson序列化注解，转成 AiAskResponse Java 对象（反序列化）
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiAskResponse(
        String answer,        // 大模型回答文本
        List<Source> sources,// 引用的知识库来源列表，元素是内部静态record Source
        String taskType,// 任务类型，比如chat、rag等
        UUID runId,// 一次会话的运行id，UUID全局唯一
        UUID traceId,// 链路追踪traceId，日志追踪全链路请求
        Usage usage,        // token使用情况和费用估算
        ExecutionTrace executionTrace,        // 执行跟踪信息，包含工具调用等
        Boolean mock
) {
//     内部记录类 Source：RAG检索出来的文档源信息
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Source(Long docId, String docName, Integer page, Integer chunkIndex, Double score) {
    }
    // 内部记录类 Usage：Token用量统计
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens, String estimatedCost) {
    }
    // 内部记录类 ExecutionTrace：AI执行过程追踪（调用工具、步骤）
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExecutionTrace(String status, List<TraceStep> steps, List<TraceToolCall> toolCalls) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TraceStep(
            UUID stepId,
            String stepName,
            String status,
            Long durationMs,//该步骤耗时
            Integer errorCode,
            String errorMessage
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TraceToolCall(
            UUID toolCallId,
            String toolName,
            String status,
            Map<String, Object> inputParams,//调用工具时传入的参数
            Map<String, Object> output,//工具调用的输出结果
            Long durationMs,//工具调用耗时
            Integer errorCode,
            String errorMessage
    ) {
    }
}
