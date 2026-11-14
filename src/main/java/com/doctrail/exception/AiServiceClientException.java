package com.doctrail.exception;

/*
* 自定义运行时异常类。
* 专门用来表示：调用远端 AI 服务（Python DocTrail）的时候，客户端这边发生了错误。
* java 项目里有很多异常（空指针、IO 异常、JSON 解析异常）。专门造这个异常，
* 用来**统一标识：问题发生在【调用 AI 远端服务的客户端 AiHealthClient】这一层**。
* */
public class AiServiceClientException extends RuntimeException {
    private final int httpStatus;
    private final int businessCode;
    private final String safeMessage;

    public AiServiceClientException(String message) {
        this(message, null, 502, 3001, "AI service unavailable");
    }

    public AiServiceClientException(String message, Throwable cause) {
        this(message, cause, 502, 3001, "AI service unavailable");
    }

    private AiServiceClientException(String message, Throwable cause, int httpStatus,
                                     int businessCode, String safeMessage) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.businessCode = businessCode;
        this.safeMessage = safeMessage;
    }

    public static AiServiceClientException timeout(String message, Throwable cause) {
        return new AiServiceClientException(message, cause, 504, 3002, "AI request timed out");
    }

    //Java 后端 和 Python AI 服务之间约定的接口契约被破坏
    public static AiServiceClientException contract(String message, Throwable cause) {
        return new AiServiceClientException(message, cause, 502, 3003, "AI response violated the contract");
    }

    public static AiServiceClientException unavailable(String message, Throwable cause) {
        return new AiServiceClientException(message, cause, 502, 3001, "AI service unavailable");
    }
    //配置类错误
    public static AiServiceClientException configuration(String message, Throwable cause) {
        return new AiServiceClientException(message, cause, 500, 5000, "internal server error");
    }

    public int httpStatus() { return httpStatus; }
    public int businessCode() { return businessCode; }
    public String safeMessage() { return safeMessage; }
}
