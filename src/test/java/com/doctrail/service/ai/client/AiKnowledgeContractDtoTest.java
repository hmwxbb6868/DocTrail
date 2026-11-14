package com.doctrail.service.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.doctrail.dto.ai.parse.AiDeleteRequest;
import com.doctrail.dto.ai.parse.AiDeleteResponse;
import com.doctrail.dto.ai.parse.AiParseRequest;
import com.doctrail.dto.ai.parse.AiParseResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiKnowledgeContractDtoTest {
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();
    private final UUID traceId = UUID.fromString("15fcdb04-b282-4c39-a51d-4fb7f948b43c");

    @Test
    void parseDtoUsesSnakeCaseAndRoundTripsContractFields() throws Exception {
        AiParseRequest request = new AiParseRequest(
                46L,
                "2026/09/8d914a68-acde-4ceb-91b5-document.pdf",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                false,
                traceId
        );
        String json = objectMapper.writeValueAsString(request);

        assertThat(json).contains("\"doc_id\":46", "\"storage_key\"", "\"content_hash\"", "\"trace_id\"");
        assertThat(objectMapper.readValue(json, AiParseRequest.class)).isEqualTo(request);

        AiParseResponse response = new AiParseResponse(
                46L,
                "COMPLETED",
                UUID.fromString("4d7f1b54-9e9f-4f76-bd2a-4db0d5e1134a"),
                12,
                UUID.fromString("c6e0f85a-4a4c-4e55-a5ca-0f8f0d4ac5b8"),
                traceId,
                null
        );
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(response)).get("chunk_count").asInt())
                .isEqualTo(12);
    }

    @Test
    void deleteDtoUsesSnakeCase() throws Exception {
        AiDeleteRequest request = new AiDeleteRequest(46L, traceId);
        String json = objectMapper.writeValueAsString(request);

        assertThat(json).contains("\"doc_id\":46", "\"trace_id\"");
        assertThat(objectMapper.readValue(json, AiDeleteRequest.class)).isEqualTo(request);

        AiDeleteResponse response = new AiDeleteResponse(46L, "DELETED", 12, traceId, null);
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(response)).get("deleted_chunk_count").asInt())
                .isEqualTo(12);
    }
}
