package com.doctrail.service.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.doctrail.dto.ai.ask.AiAskRequest;
import com.doctrail.dto.ai.ask.AiAskResponse;
import com.doctrail.exception.AiServiceClientException;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
/*
* Java 后端调用 Python AI 服务的 HTTP 客户端，专门负责发起 HTTP 请求，和 Python 后端通信
*/
public class AiAskClient {
//    RestClient 是 Spring 提供的同步 HTTP 客户端，用来调用其他 HTTP 服务
//    它负责向 Python 服务发送 HTTP 请求，并处理响应
    private final RestClient restClient;
//    ObjectMapper 是 Jackson 提供的 JSON 转换工具。把 Python 返回的 JSON 转换成 Java 对象
    private final ObjectMapper objectMapper;
//     Java 后端调用 Python 服务时使用的内部认证令牌，后面会放到请求头中
    private final String internalToken;

    public AiAskClient(RestClient restClient, ObjectMapper objectMapper, String internalToken) {
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.internalToken = Objects.requireNonNull(internalToken, "internalToken must not be null");
    }

    public AiAskResponse ask(AiAskRequest request) {
        if (internalToken.isBlank()) {
            throw AiServiceClientException.configuration(
                    "Python internal token configuration is invalid", null);
        }
        try {
            return restClient.post()
                    .uri("/api/ask")
//                    表示请求体使用 JSON 格式。等价于 HTTP 请求头：Content-Type: application/json
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Internal-Token", internalToken)
                    .header("X-Trace-Id", request.traceId().toString())
                    .body(request)
//                    当执行到 .exchange(...) 的时候，RestClient 底层把你上面配置的所有信息组装，
//                    生成一个 ClientRequest 对象 → 就是 lambda 里的clientRequest
//                    response 是远端 Python AI 服务返回的 HTTP 报文，被 Spring RestClient 底层封装成 ClientResponse 对象
//                    response 里面包含原始报文，body 是从 response 里解析出来的模型，AiAskResponse对象。
                    .exchange((clientRequest, response) -> {
                        if (response.getStatusCode().value() != 200) {
                            int status = response.getStatusCode().value();
                            if (status == 504) {
                                throw AiServiceClientException.timeout(
                                        "Python ask endpoint timed out: " + status, null);
                            }
                            if (status == 400 || status == 422) {
                                throw AiServiceClientException.contract(
                                        "Python ask endpoint rejected the frozen contract: " + status, null);
                            }
                            if (status == 401) {
                                throw AiServiceClientException.configuration(
                                        "Python internal token configuration is invalid", null);
                            }
                            if (status == 500) {
                                throw AiServiceClientException.configuration(
                                        "Python ask endpoint failed internally", null);
                            }
                            throw AiServiceClientException.unavailable(
                                    "Python ask endpoint unavailable: " + status, null);
                        }
                        try {
                            if (response.getBody() == null) {
                                throw AiServiceClientException.contract(
                                        "Python ask response body is missing", null);
                            }
//                            把前面拿到的 JSON 字符串，按照 `AiAskResponse` 这个 Record 类的结构，解析成 Java 对象
                            AiAskResponse body = objectMapper.readValue(response.getBody(), AiAskResponse.class);
                            validate(body);
                            return body;
                        } catch (IOException exception) {
                            throw AiServiceClientException.contract(
                                    "Python ask response does not match the frozen contract", exception);
                        }
                    });
        } catch (AiServiceClientException exception) {
            throw exception;
        } catch (RestClientException exception) {
            if (hasTimeout(exception)) {
                throw AiServiceClientException.timeout("Failed to call Python ask endpoint within the timeout", exception);
            }
            throw AiServiceClientException.unavailable("Failed to call Python ask endpoint", exception);
        }
    }

    private void validate(AiAskResponse response) {
        if (response == null || response.answer() == null || response.taskType() == null
                || response.sources() == null || response.runId() == null || response.traceId() == null
                || response.executionTrace() == null || response.executionTrace().status() == null
                || response.executionTrace().steps() == null || response.executionTrace().toolCalls() == null
                || response.mock() == null) {
            throw AiServiceClientException.contract("Python ask response is missing required fields", null);
        }
        if (!java.util.Set.of("COMPLETED", "FAILED", "CANCELLED")
                .contains(response.executionTrace().status())) {
            throw AiServiceClientException.contract("Python ask response has an unsupported execution status", null);
        }
//        =========引用源列表 sources不符合约定规范异常=====================
        if (response.sources().stream().anyMatch(source -> source == null || source.docId() == null
                || source.docName() == null || (source.page() != null && source.page() < 1)
                || (source.chunkIndex() != null && source.chunkIndex() < 0)
                || (source.score() != null && (source.score() < 0 || source.score() > 1)))) {
            throw AiServiceClientException.contract("Python ask response has invalid sources", null);
        }
//        =========执行步骤列表 steps不符合约定规范异常=====================
        if (response.executionTrace().steps().stream().anyMatch(step -> step == null || step.stepId() == null
                || step.stepName() == null || !java.util.Set.of("RUNNING", "COMPLETED", "FAILED", "CANCELLED")
                .contains(step.status()) || (step.durationMs() != null && step.durationMs() < 0))) {
            throw AiServiceClientException.contract("Python ask response has invalid trace steps", null);
        }
//        =========工具调用列表 tool_calls不符合约定规范异常=====================
        if (response.executionTrace().toolCalls().stream().anyMatch(call -> call == null || call.toolCallId() == null
                || call.toolName() == null || !java.util.Set.of("RUNNING", "COMPLETED", "FAILED", "CANCELLED")
                .contains(call.status()) || (call.durationMs() != null && call.durationMs() < 0))) {
            throw AiServiceClientException.contract("Python ask response has invalid trace tool calls", null);
        }
        //        =========使用情况列表 usage不符合约定规范异常=====================
        if (response.usage() != null && (response.usage().promptTokens() == null
                || response.usage().completionTokens() == null || response.usage().totalTokens() == null
                || response.usage().promptTokens() < 0 || response.usage().completionTokens() < 0
                || response.usage().totalTokens() < 0
                || (response.usage().estimatedCost() != null
                && !response.usage().estimatedCost().matches("\\d+(\\.\\d{1,6})?")))) {
            throw AiServiceClientException.contract("Python ask response has invalid usage", null);
        }
//        这里规定了允许的任务类型只能是以下四种：knowledge_qa, retrieval, chitchat, reasoning
//        这样可以防止 Java 服务接收到无法识别的业务类型。
//        knowledge_qa：知识问答；retrieval：检索类任务；chitchat：闲聊；reasoning：推理类任务。
        if (!java.util.Set.of("knowledge_qa", "retrieval", "chitchat", "reasoning")
                .contains(response.taskType())) {
            throw AiServiceClientException.contract("Python ask response has an unsupported task_type", null);
        }
    }
//  超时异常识别工具方法
    private boolean hasTimeout(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException || current instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
