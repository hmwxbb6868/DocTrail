package com.doctrail.exception;

/**
 * Raised when a contract-visible endpoint is intentionally unavailable in
 * the current route (for example, a cancelled stage or a disabled profile).
 */
public class StageFeatureUnavailableException extends RuntimeException {
    public StageFeatureUnavailableException(String message) {
        super(message);
    }
}
