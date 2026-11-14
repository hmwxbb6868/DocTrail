package com.doctrail.exception;

/** Carries the exact safe HTTP/business error persisted for an idempotent request. */
public class PersistedRequestFailureException extends RuntimeException {
    private final int httpStatus;
    private final int businessCode;
    private final String safeMessage;

    //**持久化请求失败异常**，自定义业务异
    public PersistedRequestFailureException(int httpStatus, int businessCode, String safeMessage) {
        super("persisted request failure");
        if (httpStatus < 400 || httpStatus > 599 || businessCode <= 0 || safeMessage == null) {
            throw new IllegalArgumentException("persisted request error fields are invalid");
        }
        this.httpStatus = httpStatus;
        this.businessCode = businessCode;
        this.safeMessage = safeMessage;
    }

    public int httpStatus() { return httpStatus; }
    public int businessCode() { return businessCode; }
    public String safeMessage() { return safeMessage; }
}
