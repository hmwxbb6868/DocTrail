package com.doctrail.service.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.doctrail.dto.ai.health.LivenessResponse;
import com.doctrail.dto.ai.health.ReadinessCheck;
import com.doctrail.dto.ai.health.ReadinessResponse;
import com.doctrail.exception.AiServiceClientException;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.regex.Pattern;

public class AiHealthClient {
    /*
    * **AiHealthClient 是专门用来调用你 Python DocTrail 服务的两个健康接口
    *  `/health/live` 和 `/health/ready` 的客户端。**
    * 它不止单纯发 HTTP 请求拿 JSON，还会做**多层校验**：
    * HTTP 状态码校验、JSON 契约校验、业务规则校验；
    * 只要任何一项校验不通过，就抛出前面自定义的 `AiServiceClientException`*/
    private static final String SERVICE_NAME = "doctrail-python";
    private static final Pattern CHECK_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AiHealthClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public LivenessResponse getLiveness() {
        try {
            return restClient.get()
                    .uri("/health/live")
//   发送请求，并把完整的请求对象和响应对象交给我们自己处理。
//   request：已经发送出去的请求信息；
//   response：Python 返回的原始响应信息。
//   括号里面是 lambda 回调：请求发出去拿到 response 之后，就执行这段 lambda 代码。
                    .exchange((request, response) -> {
                        int statusCode = response.getStatusCode().value();
                        if (statusCode != 200) {
                            throw new AiServiceClientException(
                                    "Unexpected liveness HTTP status: " + statusCode
                            );
                        }

                        LivenessResponse body = readBody(response, LivenessResponse.class);
                        validateLiveness(body);
                        return body;
                    });
        } catch (AiServiceClientException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new AiServiceClientException(
                    "Failed to call Python liveness endpoint",
                    exception
            );
        }
    }

    public ReadinessResponse getReadiness() {
        try {
            return restClient.get()
                    .uri("/health/ready")
                    .exchange((request, response) -> {
                        int statusCode = response.getStatusCode().value();
                        if (statusCode != 200 && statusCode != 503) {
                            throw new AiServiceClientException(
                                    "Unexpected readiness HTTP status: " + statusCode
                            );
                        }
//                      读取响应体，并尝试反序列化成 ReadinessResponse 对象
                        ReadinessResponse body = readBody(response, ReadinessResponse.class);
//            强制校验「HTTP 状态数字」和「JSON 里业务状态枚举」是否符合预先约定的契约规则
                        validateReadiness(statusCode, body);
                        return body;
                    });
//     如果本来就是 AiServiceClientException，就原样继续抛出去。
        } catch (AiServiceClientException exception) {
            throw exception;
//         这一段捕获 Spring RestClient 相关的异常。例如：无法连接 Python 服务；
//         连接超时；读取响应超时；HTTP 请求失败；响应解析失败；底层客户端发生其他错误。
        } catch (RestClientException exception) {
            throw new AiServiceClientException(
                    "Failed to call Python readiness endpoint",
                    exception
            );
        }
    }

    private <T> T readBody(ClientHttpResponse response, Class<T> responseType) {
        try {
//            `responseType`：传入的类对象，`getLiveness`调用时传的是`LivenessResponse.class`，
//            告诉 Jackson：请把 JSON 转成`LivenessResponse`这个 DTO 实例
            T body = objectMapper.readValue(response.getBody(), responseType);
            if (body == null) {
                throw new AiServiceClientException("Python health endpoint returned an empty body");
            }
            return body;
        } catch (IOException exception) {
            throw new AiServiceClientException(
                    "Python health response does not match the frozen contract",
                    exception
            );
        }
    }

    private void validateLiveness(LivenessResponse response) {
        if (response.status() != LivenessResponse.Status.UP) {
            throw new AiServiceClientException("Liveness response status must be UP");
        }
        validateCommonFields(response.service(), response.timestamp(), response.mock());
    }

    private void validateReadiness(int statusCode, ReadinessResponse response) {
        boolean statusMatchesBody =
                (statusCode == 200 && response.status() == ReadinessResponse.Status.READY)
                        || (statusCode == 503
                        && response.status() == ReadinessResponse.Status.NOT_READY);
//        HTTP 状态码和响应体不匹配
        if (!statusMatchesBody) {
            throw new AiServiceClientException(
                    "Readiness HTTP status and response body disagree"
            );
        }
//     检查 service、timestamp、mock
        validateCommonFields(response.service(), response.timestamp(), response.mock());
//        就绪检查列表不能为空
        if (response.checks() == null || response.checks().isEmpty()) {
            throw new AiServiceClientException("Readiness checks must not be empty");
        }
//     遍历 response.checks() 中的每一个检查项
        for (ReadinessCheck check : response.checks()) {
            validateCheck(check);
        }
//      checks 列表中是否至少有一个检查项状态是 DOWN。
        boolean hasDownCheck = response.checks().stream()
                .anyMatch(check -> check.status() == ReadinessCheck.Status.DOWN);
        if (response.status() == ReadinessResponse.Status.READY && hasDownCheck) {
            throw new AiServiceClientException("READY response cannot contain a DOWN check");
        }
        if (response.status() == ReadinessResponse.Status.NOT_READY && !hasDownCheck) {
            throw new AiServiceClientException("NOT_READY response must contain a DOWN check");
        }
    }

    private void validateCommonFields(
            String service,
            OffsetDateTime timestamp,
            Boolean mock
    ) {
        if (!SERVICE_NAME.equals(service)) {
            throw new AiServiceClientException("Unexpected Python service name: " + service);
        }
        if (timestamp == null || !ZoneOffset.UTC.equals(timestamp.getOffset())) {
            throw new AiServiceClientException("Health timestamp must use UTC offset Z");
        }
        if (mock == null) {
            throw new AiServiceClientException("Health response must contain mock");
        }
    }

    private void validateCheck(ReadinessCheck check) {
        if (check == null) {
            throw new AiServiceClientException("Readiness check must not be null");
        }
        if (check.name() == null || !CHECK_NAME_PATTERN.matcher(check.name()).matches()) {
            throw new AiServiceClientException("Invalid readiness check name: " + check.name());
        }
        if (check.status() == null) {
            throw new AiServiceClientException(
                    "Readiness check status must not be null: " + check.name()
            );
        }
    }
}
