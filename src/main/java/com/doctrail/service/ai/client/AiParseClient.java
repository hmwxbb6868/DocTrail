package com.doctrail.service.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.doctrail.dto.ai.parse.AiParseRequest;
import com.doctrail.dto.ai.parse.AiParseResponse;
import com.doctrail.exception.AiServiceClientException;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.util.Objects;

/** Internal Java-to-Python client for the frozen document parse contract. */
public class AiParseClient {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    public AiParseClient(RestClient restClient, ObjectMapper objectMapper, String internalToken) {
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.internalToken = Objects.requireNonNull(internalToken, "internalToken must not be null");
    }

    public AiParseResponse parse(AiParseRequest request) {
        try {
            return restClient.post()
                    .uri("/api/parse")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Internal-Token", internalToken)// 内部接口鉴权token，防止非法调用
                    .header("X-Trace-Id", request.traceId().toString())
                    .body(request)
                    .exchange((clientRequest, response) -> {
                        if (response.getStatusCode().value() != 200) {
                            throw new AiServiceClientException(
                                    "Unexpected Python parse HTTP status: " + response.getStatusCode().value());
                        }
                        try {
                            AiParseResponse body = objectMapper.readValue(response.getBody(), AiParseResponse.class);
                            validate(request, body);
                            return body;
                        } catch (IOException exception) {
                            throw new AiServiceClientException(
                                    "Python parse response does not match the frozen contract", exception);
                        }
                    });
        } catch (AiServiceClientException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new AiServiceClientException("Failed to call Python parse endpoint", exception);
        }
    }

    private void validate(AiParseRequest request, AiParseResponse response) {
        if (response == null || response.docId() == null || response.status() == null
                || response.chunkCount() == null || response.traceId() == null
                || response.errorMessage() != null && response.errorMessage().length() > 500) {
            throw new AiServiceClientException("Python parse response is missing required fields");
        }
        if (!request.docId().equals(response.docId()) || !request.traceId().equals(response.traceId())) {
            throw new AiServiceClientException("Python parse response identity does not match the request");
        }
        if (!java.util.Set.of("COMPLETED", "FAILED").contains(response.status())) {
            throw new AiServiceClientException("Python parse response has an unsupported status");
        }
        if (response.chunkCount() < 0) {
            throw new AiServiceClientException("Python parse response has a negative chunk_count");
        }
        if ("COMPLETED".equals(response.status())
                && (response.batchId() == null || response.indexBuildId() == null)) {
            throw new AiServiceClientException(
                    "Python completed parse response must include batch_id and index_build_id");
        }
    }
}
