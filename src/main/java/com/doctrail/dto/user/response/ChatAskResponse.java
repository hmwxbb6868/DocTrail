package com.doctrail.dto.user.response;

import com.doctrail.dto.ai.ask.AiAskResponse;

import java.util.List;
import java.util.Map;
//Controller 层对外返回给前端的 DTO
public record ChatAskResponse(
        String answer,// AI生成的回答文本
        List<Source> sources,// RAG检索出来的参考文档来源列表
        String taskType,// 任务类型，比如问答/摘要/Agent任务
        String runId,// 单次AI执行的唯一id
        String traceId,// 链路追踪id，排查日志用
        Usage usage,// token消耗、计费信息
        ExecutionTrace executionTrace,// Agent执行步骤、工具调用记录
        Boolean mock // 是否模拟数据：true=mock假数据，false=真实调用AI
) {
//    from() 方法，叫静态工厂方法，作用是：对象转换 / DTO 拷贝
//    DTO：`AiAskResponse`（后端调用 AI 大模型服务拿到的内部返回对象） → 转换成对外 Controller 给前端响应的 DTO：`ChatAskResponse`
    public static ChatAskResponse from(AiAskResponse response) {
        // 转换 sources 文档来源列表
        // 如果原始response.sources()是null → 给空集合 List.of()，防止后面遍历空指针
        // 如果不为null → 走stream流，循环每一个source，new Source包装，最后转成List
        List<Source> sources = response.sources() == null ? List.of() : response.sources().stream()
                .map(source -> new Source(source.docId(), source.docName(), source.page(), source.chunkIndex(), source.score()))
                .toList();
        Usage usage = response.usage() == null
                ? null
                : new Usage(response.usage().promptTokens(), response.usage().completionTokens(),
                response.usage().totalTokens(), response.usage().estimatedCost());
        // 转换Agent执行追踪信息ExecutionTrace
        ExecutionTrace trace = response.executionTrace() == null
                ? null
                : new ExecutionTrace(
                response.executionTrace().status(),
                response.executionTrace().steps().stream()
                        // 遍历每一步，把内部TraceStep字段逐个赋值，新建对外TraceStep
                        .map(step -> new TraceStep(
                                step.stepId(), step.stepName(), step.status(), step.durationMs(),
                                step.errorCode(), step.errorMessage()))
                        // 将stream结果收集为List<TraceStep>
                        .toList(),
                // 转换工具调用列表：将内部TraceToolCall映射为对外DTO的TraceToolCall
                response.executionTrace().toolCalls().stream()
                        // 遍历每一次工具调用，字段透传，新建对外TraceToolCall
                        .map(toolCall -> new TraceToolCall(
                                toolCall.toolCallId(), toolCall.toolName(), toolCall.status(),
                                toolCall.inputParams(), toolCall.output(), toolCall.durationMs(),
                                toolCall.errorCode(), toolCall.errorMessage()))
                        // 将stream结果收集为List<TraceToolCall>
                        .toList()
        );
        return new ChatAskResponse(response.answer(), sources, response.taskType(),
                response.runId().toString(), response.traceId().toString(), usage, trace, response.mock());
    }

    public record Source(Long docId, String docName, Integer page, Integer chunkIndex, Double score) {
    }

    public record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens, String estimatedCost) {
    }
//  一次 AI Agent 任务完整执行追踪的根对象，steps：执行步骤列表，toolCalls：工具调用列表
    public record ExecutionTrace(String status, List<TraceStep> steps, List<TraceToolCall> toolCalls) {
    }

//  Agent 流程里的单个执行步骤信息
    public record TraceStep(
            java.util.UUID stepId,//当前步骤唯一 UUID，链路追踪
            String stepName,//步骤名称
            String status,//步骤状态
            Long durationMs,//步骤耗时毫秒
            Integer errorCode,//错误码
            String errorMessage//错误信息
    ) {
    }
//  AI Agent 任务工具调用对象
    public record TraceToolCall(
            java.util.UUID toolCallId,//工具调用唯一 UUID，链路追踪
            String toolName,//工具名称
            String status,//工具调用状态
            Map<String, Object> inputParams,//工具调用输入参数
            Map<String, Object> output,//工具调用输出结果
            Long durationMs,//工具调用耗时毫秒
            Integer errorCode,//错误码
            String errorMessage//错误信息
    ) {
    }
}
