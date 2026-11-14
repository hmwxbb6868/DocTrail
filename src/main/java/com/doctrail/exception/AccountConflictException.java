package com.doctrail.exception;
//**自定义运行时异常**：`账号冲突异常`
public class AccountConflictException extends RuntimeException {
    public AccountConflictException(String message) {
        super(message);
    }
}
