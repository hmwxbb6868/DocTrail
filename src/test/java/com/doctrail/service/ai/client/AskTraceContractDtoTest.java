package com.doctrail.service.ai.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.doctrail.dto.ai.ask.AiAskResponse;
import com.doctrail.dto.user.response.ChatAskResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AskTraceContractDtoTest {
    private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TRACE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID STEP_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TOOL_CALL_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void internalTraceUsesSnakeCaseAndPublicTraceUsesCamelCase() throws Exception {
        AiAskResponse internal = objectMapper.readValue("""
                {
                  "answer": "answer",
                  "sources": [],
                  "task_type": "knowledge_qa",
                  "run_id": "%s",
                  "trace_id": "%s",
                  "usage": null,
                  "execution_trace": {
                    "status": "COMPLETED",
                    "steps": [{
                      "step_id": "%s",
                      "step_name": "retrieve",
                      "status": "COMPLETED",
                      "duration_ms": 12,
                      "error_code": null,
                      "error_message": null
                    }],
                    "tool_calls": [{
                      "tool_call_id": "%s",
                      "tool_name": "search",
                      "status": "COMPLETED",
                      "input_params": {"query": "question"},
                      "output": {"count": 1},
                      "duration_ms": 8,
                      "error_code": null,
                      "error_message": null
                    }]
                  },
                  "mock": false
                }
                """.formatted(RUN_ID, TRACE_ID, STEP_ID, TOOL_CALL_ID), AiAskResponse.class);

        assertThat(internal.executionTrace().steps().getFirst().stepId()).isEqualTo(STEP_ID);
        assertThat(internal.executionTrace().toolCalls().getFirst().inputParams())
                .containsEntry("query", "question");

        ChatAskResponse publicResponse = ChatAskResponse.from(internal);
        var publicJson = objectMapper.readTree(objectMapper.writeValueAsString(publicResponse));
        assertThat(publicJson.at("/executionTrace/steps/0/stepId").asText()).isEqualTo(STEP_ID.toString());
        assertThat(publicJson.at("/executionTrace/toolCalls/0/toolCallId").asText())
                .isEqualTo(TOOL_CALL_ID.toString());
        assertThat(publicJson.at("/executionTrace/toolCalls/0/inputParams/query").asText())
                .isEqualTo("question");
        assertThat(publicJson.at("/executionTrace/steps/0/step_id").isMissingNode()).isTrue();
        assertThat(publicJson.at("/executionTrace/toolCalls/0/input_params").isMissingNode()).isTrue();
    }

    @Test
    void strictInternalMapperRejectsUnknownNestedTraceField() {
        String json = """
                {
                  "answer": "answer",
                  "sources": [],
                  "task_type": "knowledge_qa",
                  "run_id": "%s",
                  "trace_id": "%s",
                  "usage": null,
                  "execution_trace": {
                    "status": "COMPLETED",
                    "steps": [{
                      "step_id": "%s",
                      "step_name": "retrieve",
                      "status": "COMPLETED",
                      "duration_ms": null,
                      "error_code": null,
                      "error_message": null,
                      "unexpected": true
                    }],
                    "tool_calls": []
                  },
                  "mock": false
                }
                """.formatted(RUN_ID, TRACE_ID, STEP_ID);

        assertThatThrownBy(() -> objectMapper.readValue(json, AiAskResponse.class))
                .isInstanceOf(UnrecognizedPropertyException.class)
                .hasMessageContaining("unexpected");
    }

    @Test
    void strictInternalMapperRejectsUnknownToolCallField() {
        String json = """
                {
                  "answer": "answer",
                  "sources": [],
                  "task_type": "knowledge_qa",
                  "run_id": "%s",
                  "trace_id": "%s",
                  "usage": null,
                  "execution_trace": {
                    "status": "COMPLETED",
                    "steps": [],
                    "tool_calls": [{
                      "tool_call_id": "%s",
                      "tool_name": "search",
                      "status": "COMPLETED",
                      "input_params": {},
                      "output": {},
                      "duration_ms": 8,
                      "error_code": null,
                      "error_message": null,
                      "unexpected": true
                    }]
                  },
                  "mock": false
                }
                """.formatted(RUN_ID, TRACE_ID, TOOL_CALL_ID);

        assertThatThrownBy(() -> objectMapper.readValue(json, AiAskResponse.class))
                .isInstanceOf(UnrecognizedPropertyException.class)
                .hasMessageContaining("unexpected");
    }

    @Test
    void publicTraceCopiesArbitraryToolPayloadsWithoutChangingTheirShape() {
        AiAskResponse response = new AiAskResponse(
                "answer", List.of(), "knowledge_qa", RUN_ID, TRACE_ID, null,
                new AiAskResponse.ExecutionTrace(
                        "COMPLETED",
                        List.of(new AiAskResponse.TraceStep(
                                STEP_ID, "retrieve", "COMPLETED", 12L, null, null)),
                        List.of(new AiAskResponse.TraceToolCall(
                                TOOL_CALL_ID, "search", "COMPLETED",
                                Map.of("query", "question"), Map.of("count", 1), 8L, null, null))
                ),
                false
        );

        ChatAskResponse publicResponse = ChatAskResponse.from(response);

        assertThat(publicResponse.executionTrace().steps().getFirst().stepName()).isEqualTo("retrieve");
        assertThat(publicResponse.executionTrace().toolCalls().getFirst().output()).containsEntry("count", 1);
    }
}
