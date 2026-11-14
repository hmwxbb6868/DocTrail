package com.doctrail.service.ai.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.doctrail.dto.ai.ask.AiAskRequest;
import com.doctrail.dto.ai.ask.AiAskResponse;
import com.doctrail.exception.AiServiceClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class AiAskClientTest {
    private static final String BASE_URL = "http://127.0.0.1:8000";
    private MockRestServiceServer server;
    private AiAskClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiAskClient(
                builder.build(),
                JsonMapper.builder().findAndAddModules()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .build(),
                "test-token"
        );
    }

    @Test
    void sendsInternalHeadersAndParsesSnakeCaseResponse() {
        UUID requestId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        AiAskRequest request = new AiAskRequest(
                "test", 1L, null, List.of(new AiAskRequest.HistoryItem(2L, "user", "old")),
                null, null, false, requestId, runId, traceId, true
        );
        server.expect(once(), requestTo(BASE_URL + "/api/ask"))
                .andExpect(method(POST))
                .andExpect(header("X-Internal-Token", "test-token"))
                .andExpect(header("X-Trace-Id", traceId.toString()))
                .andExpect(jsonPath("$.conversation_id").value(1))
                .andExpect(jsonPath("$.history[0].message_id").value(2))
                .andRespond(withSuccess(responseJson(runId, traceId), MediaType.APPLICATION_JSON));

        AiAskResponse response = client.ask(request);

        assertThat(response.answer()).isEqualTo("mock answer");
        assertThat(response.runId()).isEqualTo(runId);
        assertThat(response.traceId()).isEqualTo(traceId);
        assertThat(response.mock()).isFalse();
        server.verify();
    }

    @Test
    void acceptsHistoricalStageOneMockResponseWithoutMakingTheGenericClientStageSpecific() {
        UUID requestId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        server.expect(once(), requestTo(BASE_URL + "/api/ask"))
                .andRespond(withSuccess(responseJson(runId, traceId, true), MediaType.APPLICATION_JSON));

        AiAskResponse response = client.ask(new AiAskRequest(
                "test", 1L, null, List.of(), null, null, false,
                requestId, runId, traceId, true));

        assertThat(response.mock()).isTrue();
        server.verify();
    }

    @Test
    void acceptsChitchatResponseWithoutSourcesUsageOrToolCalls() {
        UUID requestId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        server.expect(once(), requestTo(BASE_URL + "/api/ask"))
                .andRespond(withSuccess("""
                        {
                          "answer": "你好！我是 DocTrail 知识助手，很高兴见到你。",
                          "sources": [],
                          "task_type": "chitchat",
                          "run_id": "%s",
                          "trace_id": "%s",
                          "usage": null,
                          "execution_trace": {
                            "status": "COMPLETED",
                            "steps": [
                              {"step_id": "%s", "step_name": "route_intent", "status": "COMPLETED", "duration_ms": 0, "error_code": null, "error_message": null},
                              {"step_id": "%s", "step_name": "generate_chitchat_response", "status": "COMPLETED", "duration_ms": 0, "error_code": null, "error_message": null}
                            ],
                            "tool_calls": []
                          },
                          "mock": false
                        }
                        """.formatted(runId, traceId, UUID.randomUUID(), UUID.randomUUID()), MediaType.APPLICATION_JSON));

        AiAskResponse response = client.ask(new AiAskRequest(
                "你好", 1L, null, List.of(), null, null, false,
                requestId, runId, traceId, true));

        assertThat(response.taskType()).isEqualTo("chitchat");
        assertThat(response.sources()).isEmpty();
        assertThat(response.usage()).isNull();
        assertThat(response.executionTrace().steps()).extracting(AiAskResponse.TraceStep::stepName)
                .containsExactly("route_intent", "generate_chitchat_response");
        assertThat(response.executionTrace().toolCalls()).isEmpty();
        server.verify();
    }

    @Test
    void mapsFastApiValidationToContractError() {
        UUID requestId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        server.expect(once(), requestTo(BASE_URL + "/api/ask"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY));

        assertThatThrownBy(() -> client.ask(new AiAskRequest(
                "test", 1L, null, List.of(), null, null, false,
                requestId, runId, traceId, true)))
                .isInstanceOf(com.doctrail.exception.AiServiceClientException.class)
                .extracting("businessCode").isEqualTo(3003);
    }

    @Test
    void mapsPythonUnavailableTo3001() {
        UUID requestId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        server.expect(once(), requestTo(BASE_URL + "/api/ask"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.ask(new AiAskRequest(
                "test", 1L, null, List.of(), null, null, false,
                requestId, runId, traceId, true)))
                .isInstanceOf(com.doctrail.exception.AiServiceClientException.class)
                .extracting("businessCode").isEqualTo(3001);
    }

    @Test
    void mapsPythonInternalFailureTo5000() {
        UUID requestId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        server.expect(once(), requestTo(BASE_URL + "/api/ask"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withServerError());

        assertThatThrownBy(() -> client.ask(new AiAskRequest(
                "test", 1L, null, List.of(), null, null, false,
                requestId, runId, traceId, true)))
                .isInstanceOf(com.doctrail.exception.AiServiceClientException.class)
                .extracting("businessCode").isEqualTo(5000);
    }

    @Test
    void mapsInternalTokenFailureTo5000() {
        UUID requestId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        server.expect(once(), requestTo(BASE_URL + "/api/ask"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.ask(new AiAskRequest(
                "test", 1L, null, List.of(), null, null, false,
                requestId, runId, traceId, true)))
                .isInstanceOf(com.doctrail.exception.AiServiceClientException.class)
                .extracting("businessCode").isEqualTo(5000);
    }

    @Test
    void rejectsMissingInternalTokenAsConfigurationFailureBeforeHttpCall() {
        AiAskClient missingTokenClient = new AiAskClient(
                RestClient.builder().baseUrl(BASE_URL).build(),
                JsonMapper.builder().findAndAddModules()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .build(),
                ""
        );
        UUID requestId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();

        assertThatThrownBy(() -> missingTokenClient.ask(new AiAskRequest(
                "test", 1L, null, List.of(), null, null, false,
                requestId, runId, traceId, true)))
                .isInstanceOf(AiServiceClientException.class)
                .extracting("businessCode").isEqualTo(5000);
    }

    private String responseJson(UUID runId, UUID traceId) {
        return responseJson(runId, traceId, false);
    }

    private String responseJson(UUID runId, UUID traceId, boolean mock) {
        return """
                {
                  "answer": "mock answer",
                  "sources": [{"doc_id": 46, "doc_name": "doc.md", "page": 1, "chunk_index": 0, "score": 0.8}],
                  "task_type": "knowledge_qa",
                  "run_id": "%s",
                  "trace_id": "%s",
                  "usage": null,
                  "execution_trace": {"status": "COMPLETED", "steps": [], "tool_calls": []},
                  "mock": %s
                }
                """.formatted(runId, traceId, mock);
    }
}
