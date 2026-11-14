package com.doctrail.exception;

public class RefreshTokenFailureException extends RuntimeException {
    public RefreshTokenFailureException(String message) {
        super(message);
    }

    public RefreshTokenFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
