package com.doctrail.exception;

/** Internal failure while persisting a request terminal state. */
public class RequestTerminalFailureException extends RuntimeException {
    //接收异常描述信息与原始根因异常对象，用于包装底层异常，例如 JSON 序列化异常、数据库操作异常，保留原始堆栈。
    public RequestTerminalFailureException(String message, Throwable cause) {
        super(message, cause);
    }
    //仅接收异常描述文本，用于业务状态校验失败场景
    public RequestTerminalFailureException(String message) {
        super(message);
    }
}
