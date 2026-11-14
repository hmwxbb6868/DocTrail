package com.doctrail.service.ai.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.doctrail.dto.ai.parse.AiDeleteRequest;
import com.doctrail.dto.ai.parse.AiDeleteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiDeleteClientTest {
    private static final String BASE_URL = "http://127.0.0.1:8000";
    private MockRestServiceServer server;
    private AiDeleteClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiDeleteClient(
                builder.build(),
                JsonMapper.builder().findAndAddModules()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .build(),
                "test-token"
        );
    }

    @Test
    void sendsDeleteRequestAndAcceptsCompletedResponse() {
        UUID traceId = UUID.randomUUID();
        AiDeleteRequest request = new AiDeleteRequest(46L, traceId);
        server.expect(once(), requestTo(BASE_URL + "/api/delete"))
                .andExpect(method(POST))
                .andExpect(header("X-Internal-Token", "test-token"))
                .andExpect(header("X-Trace-Id", traceId.toString()))
                .andExpect(jsonPath("$.doc_id").value(46))
                .andRespond(withSuccess("""
                        {"doc_id":46,"status":"DELETED","deleted_chunk_count":3,"trace_id":"%s","error_message":null}
                        """.formatted(traceId), MediaType.APPLICATION_JSON));

        AiDeleteResponse response = client.delete(request);

        assertThat(response.status()).isEqualTo("DELETED");
        assertThat(response.deletedChunkCount()).isEqualTo(3);
        server.verify();
    }
}
