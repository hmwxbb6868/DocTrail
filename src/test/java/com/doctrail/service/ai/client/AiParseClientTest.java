package com.doctrail.service.ai.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.doctrail.dto.ai.parse.AiParseRequest;
import com.doctrail.dto.ai.parse.AiParseResponse;
import com.doctrail.exception.AiServiceClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiParseClientTest {
    private static final String BASE_URL = "http://127.0.0.1:8000";
    private MockRestServiceServer server;
    private AiParseClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiParseClient(
                builder.build(),
                JsonMapper.builder().findAndAddModules()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .build(),
                "test-token"
        );
    }

    @Test
    void sendsSnakeCaseRequestAndAcceptsFailedParseResult() {
        UUID traceId = UUID.randomUUID();
        AiParseRequest request = new AiParseRequest(46L, "2026/09/document.pdf", "a".repeat(64), false, traceId);
        server.expect(once(), requestTo(BASE_URL + "/api/parse"))
                .andExpect(method(POST))
                .andExpect(header("X-Internal-Token", "test-token"))
                .andExpect(header("X-Trace-Id", traceId.toString()))
                .andExpect(jsonPath("$.doc_id").value(46))
                .andExpect(jsonPath("$.storage_key").value("2026/09/document.pdf"))
                .andRespond(withSuccess(responseJson(traceId), MediaType.APPLICATION_JSON));

        AiParseResponse response = client.parse(request);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.traceId()).isEqualTo(traceId);
        server.verify();
    }

    @Test
    void acceptsCompletedParseResultWithBatchAndIndexBuildIds() {
        UUID traceId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID indexBuildId = UUID.randomUUID();
        AiParseRequest request = new AiParseRequest(46L, "2026/09/document.pdf", "a".repeat(64), false, traceId);
        server.expect(once(), requestTo(BASE_URL + "/api/parse"))
                .andRespond(withSuccess("""
                        {
                          "doc_id": 46,
                          "status": "COMPLETED",
                          "batch_id": "%s",
                          "chunk_count": 3,
                          "index_build_id": "%s",
                          "trace_id": "%s",
                          "error_message": null
                        }
                        """.formatted(batchId, indexBuildId, traceId), MediaType.APPLICATION_JSON));

        AiParseResponse response = client.parse(request);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.batchId()).isEqualTo(batchId);
        assertThat(response.indexBuildId()).isEqualTo(indexBuildId);
        server.verify();
    }

    @Test
    void rejectsResponseForAnotherDocument() {
        UUID traceId = UUID.randomUUID();
        AiParseRequest request = new AiParseRequest(46L, "2026/09/document.pdf", "a".repeat(64), false, traceId);
        server.expect(once(), requestTo(BASE_URL + "/api/parse"))
                .andRespond(withSuccess(responseJsonWithDocId(47L, traceId), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.parse(request))
                .isInstanceOf(AiServiceClientException.class)
                .hasMessage("Python parse response identity does not match the request");
        server.verify();
    }

    private String responseJson(UUID traceId) {
        return responseJsonWithDocId(46L, traceId);
    }

    private String responseJsonWithDocId(long docId, UUID traceId) {
        return """
                {
                  "doc_id": %d,
                  "status": "FAILED",
                  "batch_id": null,
                  "chunk_count": 0,
                  "index_build_id": null,
                  "trace_id": "%s",
                  "error_message": "document parser is not implemented in this slice"
                }
                """.formatted(docId, traceId);
    }
}
