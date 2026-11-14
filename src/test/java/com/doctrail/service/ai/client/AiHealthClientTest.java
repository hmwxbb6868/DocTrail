package com.doctrail.service.ai.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.doctrail.dto.ai.health.LivenessResponse;
import com.doctrail.dto.ai.health.ReadinessCheck;
import com.doctrail.dto.ai.health.ReadinessResponse;
import com.doctrail.exception.AiServiceClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiHealthClientTest {
/*
* 专门用来测试AiHealthClient类是否能够正确调用 Python 健康检查接口*/
    private static final String BASE_URL = "http://127.0.0.1:8000";

    private MockRestServiceServer server;
    private AiHealthClient client;

//    每个测试方法执行之前，先执行一次下面的方法。
    @BeforeEach
    void setUp() {
//        每个测试开始前，都会重新创建测试服务器和客户端。
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiHealthClient(
                builder.build(),
                JsonMapper.builder()
                        .findAndAddModules()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .build()
        );
    }
/*
* 当 Python 的 /health/live 接口返回符合约定的正常响应时，
* Java 的 AiHealthClient 能不能正确请求、解析并返回结果*/
    @Test
    void returnsLivenessWhenContractMatches() {
//       在告诉模拟服务器：我期待收到一次对 /health/live 的请求。
//        once()表示这个请求必须发生一次。
        server.expect(once(), requestTo(BASE_URL + "/health/live"))
                .andExpect(method(HttpMethod.GET))
//                当收到符合要求的请求后，返回一个成功的 JSON 响应。
                .andRespond(withSuccess(liveJson(), MediaType.APPLICATION_JSON));

        LivenessResponse response = client.getLiveness();

        assertThat(response.status()).isEqualTo(LivenessResponse.Status.UP);
        assertThat(response.service()).isEqualTo("doctrail-python");
        assertThat(response.timestamp().getOffset().getTotalSeconds()).isZero();
//      检查前面设置的请求规则是否真的被满足。
        assertThat(response.mock()).isTrue();
        server.verify();
    }

/*
* 当 Python 的 /health/ready 接口返回符合约定的正常响应时，
* Java 的 AiHealthClient 能不能正确请求、解析并返回结果*/
    @Test
    void returnsReadyWhenHttp200AndBodyAgree() {
        server.expect(once(), requestTo(BASE_URL + "/health/ready"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(readyJson(), MediaType.APPLICATION_JSON));

        ReadinessResponse response = client.getReadiness();

        assertThat(response.status()).isEqualTo(ReadinessResponse.Status.READY);
        assertThat(response.checks())
                .singleElement()
                .extracting(ReadinessCheck::status)
                .isEqualTo(ReadinessCheck.Status.UP);
        assertThat(response.mock()).isTrue();
        server.verify();
    }

    @Test
    void returnsNotReadyBodyForHttp503() {
        server.expect(once(), requestTo(BASE_URL + "/health/ready"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(notReadyJson()));

        ReadinessResponse response = client.getReadiness();

        assertThat(response.status()).isEqualTo(ReadinessResponse.Status.NOT_READY);
        assertThat(response.checks())
                .singleElement()
                .extracting(ReadinessCheck::status)
                .isEqualTo(ReadinessCheck.Status.DOWN);
        server.verify();
    }

    @Test
    void rejectsHttp200WithNotReadyBody() {
        server.expect(once(), requestTo(BASE_URL + "/health/ready"))
                .andRespond(withSuccess(notReadyJson(), MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::getReadiness)
                .isInstanceOf(AiServiceClientException.class)
                .hasMessage("Readiness HTTP status and response body disagree");
        server.verify();
    }

    @Test
    void rejectsUnexpectedHttpStatus() {
        server.expect(once(), requestTo(BASE_URL + "/health/ready"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(client::getReadiness)
                .isInstanceOf(AiServiceClientException.class)
                .hasMessage("Unexpected readiness HTTP status: 500");
        server.verify();
    }

    @Test
    void rejectsUnknownContractField() {
        String responseWithUnknownField = liveJson().replace(
                "\"mock\": true",
                "\"mock\": true, \"unexpected\": true"
        );
        server.expect(once(), requestTo(BASE_URL + "/health/live"))
                .andRespond(withSuccess(responseWithUnknownField, MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::getLiveness)
                .isInstanceOf(AiServiceClientException.class)
                .hasMessage("Python health response does not match the frozen contract");
        server.verify();
    }

    @Test
    void rejectsMissingRequiredMockField() {
        String responseWithoutMock = """
                {
                  "status": "UP",
                  "service": "doctrail-python",
                  "timestamp": "2026-09-20T03:00:00Z"
                }
                """;
        server.expect(once(), requestTo(BASE_URL + "/health/live"))
                .andRespond(withSuccess(responseWithoutMock, MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::getLiveness)
                .isInstanceOf(AiServiceClientException.class)
                .hasMessage("Health response must contain mock");
        server.verify();
    }

    private String liveJson() {
        return """
                {
                  "status": "UP",
                  "service": "doctrail-python",
                  "timestamp": "2026-09-20T03:00:00Z",
                  "mock": true
                }
                """;
    }

    private String readyJson() {
        return """
                {
                  "status": "READY",
                  "service": "doctrail-python",
                  "timestamp": "2026-09-20T03:00:00Z",
                  "mock": true,
                  "checks": [
                    {
                      "name": "contract_stub",
                      "status": "UP",
                      "detail": "Stage 1 contract stub is ready."
                    }
                  ]
                }
                """;
    }

    private String notReadyJson() {
        return """
                {
                  "status": "NOT_READY",
                  "service": "doctrail-python",
                  "timestamp": "2026-09-20T03:00:00Z",
                  "mock": true,
                  "checks": [
                    {
                      "name": "contract_stub",
                      "status": "DOWN",
                      "detail": "Stage 1 contract stub is not ready."
                    }
                  ]
                }
                """;
    }
}
