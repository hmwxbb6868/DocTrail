package com.doctrail.exception;

/** A duplicate request is still owned by another in-flight execution. */
public class RequestInProgressException extends RuntimeException {
    public RequestInProgressException(String message) {
        super(message);
    }
}
