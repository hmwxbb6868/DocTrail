package com.doctrail.service.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.doctrail.dto.ai.parse.AiDeleteRequest;
import com.doctrail.dto.ai.parse.AiDeleteResponse;
import com.doctrail.exception.AiServiceClientException;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

/** Internal Java-to-Python client for the frozen document delete contract. */
public class AiDeleteClient {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    public AiDeleteClient(RestClient restClient, ObjectMapper objectMapper, String internalToken) {
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.internalToken = Objects.requireNonNull(internalToken, "internalToken must not be null");
    }

//    AiDeleteRequest: docId,traceId
//    AiDeleteResponse: docId, status, deletedChunkCount, traceId, errorMessage
    public AiDeleteResponse delete(AiDeleteRequest request) {
        try {
            return restClient.post()
                    .uri("/api/delete")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Internal-Token", internalToken)
                    .header("X-Trace-Id", request.traceId().toString())
                    .body(request)
                    .exchange((clientRequest, response) -> {
                        if (response.getStatusCode().value() != 200) {
                            throw new AiServiceClientException(
                                    "Unexpected Python delete HTTP status: " + response.getStatusCode().value());
                        }
                        try {
                            AiDeleteResponse body = objectMapper.readValue(response.getBody(), AiDeleteResponse.class);
                            validate(request, body);
                            return body;
                        } catch (IOException exception) {
                            throw new AiServiceClientException(
                                    "Python delete response does not match the frozen contract", exception);
                        }
                    });
        } catch (AiServiceClientException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new AiServiceClientException("Failed to call Python delete endpoint", exception);
        }
    }

    private void validate(AiDeleteRequest request, AiDeleteResponse response) {
        if (response == null || response.docId() == null || response.status() == null
                || response.deletedChunkCount() == null || response.traceId() == null
                || response.deletedChunkCount() < 0
                || response.errorMessage() != null && response.errorMessage().length() > 500) {
            throw new AiServiceClientException("Python delete response is missing required fields");
        }
        if (!request.docId().equals(response.docId()) || !request.traceId().equals(response.traceId())) {
            throw new AiServiceClientException("Python delete response identity does not match the request");
        }
        if (!Set.of("DELETED", "FAILED").contains(response.status())) {
            throw new AiServiceClientException("Python delete response has an unsupported status");
        }
    }
}
