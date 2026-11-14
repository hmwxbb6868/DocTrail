package com.doctrail.exception;

//自定义业务异常，专门代表 requestId 冲突，用来抛出幂等校验失败的错误信息
public class RequestIdConflictException extends RuntimeException {
    public RequestIdConflictException(String message) {
        super(message);
    }
}
