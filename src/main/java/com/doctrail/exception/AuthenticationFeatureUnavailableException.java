package com.doctrail.exception;

// Compatibility subtype retained for authentication-specific call sites.
// The public error semantics are the shared StageFeatureUnavailableException
// contract and are mapped to HTTP 501 / Result.code=5010.
public class AuthenticationFeatureUnavailableException extends StageFeatureUnavailableException {
    public AuthenticationFeatureUnavailableException(String message) {
        super(message);
    }
}
