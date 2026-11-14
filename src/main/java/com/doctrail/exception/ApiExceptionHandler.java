package com.doctrail.exception;

import com.doctrail.common.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import com.doctrail.storage.UnsupportedUploadTypeException;
import com.doctrail.storage.UploadTooLargeException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("invalid request");
        return ResponseEntity.badRequest().body(Result.failure(4000, message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Result<Void>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Result.failure(4000, exception.getMessage()));
    }

    @ExceptionHandler(AuthenticationFailureException.class)
    ResponseEntity<Result<Void>> handleAuthenticationFailure(AuthenticationFailureException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.failure(1001, "invalid username or password"));
    }

    @ExceptionHandler(StageFeatureUnavailableException.class)
    ResponseEntity<Result<Void>> handleStageFeatureUnavailable(StageFeatureUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Result.failure(5010, "feature is not implemented in the current stage"));
    }

    @ExceptionHandler(CaptchaValidationException.class)
    ResponseEntity<Result<Void>> handleCaptchaValidation(CaptchaValidationException exception) {
        return ResponseEntity.badRequest()
                .body(Result.failure(4001, "invalid or expired captcha"));
    }

    @ExceptionHandler(AccountConflictException.class)
    ResponseEntity<Result<Void>> handleAccountConflict(AccountConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.failure(1004, "username already exists"));
    }

    /*
    * 这是**全局异常处理器**，专门捕获 `RequestIdConflictException` 这个自定义异常。
      *当代码抛出该异常时，这个方法自动执行：返回 HTTP 状态码`409 CONFLICT`，
      * 并封装业务错误码`1004`和提示信息给前端。*/
    @ExceptionHandler(RequestIdConflictException.class)
    ResponseEntity<Result<Void>> handleRequestIdConflict(RequestIdConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.failure(1004, "request id has already been used"));
    }

    @ExceptionHandler(RequestInProgressException.class)
    ResponseEntity<Result<Void>> handleRequestInProgress(RequestInProgressException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.failure(2002, "request is still being processed"));
    }

    @ExceptionHandler(PersistedRequestFailureException.class)
    ResponseEntity<Result<Void>> handlePersistedRequestFailure(PersistedRequestFailureException exception) {
        return ResponseEntity.status(exception.httpStatus())
                .body(Result.failure(exception.businessCode(), exception.safeMessage()));
    }

    @ExceptionHandler(RefreshTokenFailureException.class)
    ResponseEntity<Result<Void>> handleRefreshTokenFailure(RefreshTokenFailureException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.failure(1002, "refresh token is missing, expired, or revoked"));
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class})
    ResponseEntity<Result<Void>> handleMissingOrUnreadableRequest(Exception exception) {
        return ResponseEntity.badRequest().body(Result.failure(4000, "invalid request"));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Result<Void>> handleMethodValidation(HandlerMethodValidationException exception) {
        return ResponseEntity.badRequest().body(Result.failure(4000, "invalid request"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Result<Void>> handleNoResourceFound(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.failure(2001, "resource not found"));
    }

    @ExceptionHandler(AiServiceClientException.class)
    ResponseEntity<Result<Void>> handleAiService(AiServiceClientException exception) {
        return ResponseEntity.status(exception.httpStatus())
                .body(Result.failure(exception.businessCode(), exception.safeMessage()));
    }

    @ExceptionHandler(RequestTerminalFailureException.class)
    ResponseEntity<Result<Void>> handleRequestTerminalFailure(RequestTerminalFailureException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.failure(5000, "internal server error"));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Result<Void>> handleNotFound(ResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.failure(2001, exception.getMessage()));
    }

    @ExceptionHandler(UploadTooLargeException.class)
    ResponseEntity<Result<Void>> handleUploadTooLarge(UploadTooLargeException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Result.failure(4002, "uploaded file exceeds the configured size limit"));
    }

    @ExceptionHandler(UnsupportedUploadTypeException.class)
    ResponseEntity<Result<Void>> handleUnsupportedUploadType(UnsupportedUploadTypeException exception) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Result.failure(4003, "file type is not supported"));
    }

    @ExceptionHandler(KnowledgeDocumentConflictException.class)
    ResponseEntity<Result<Void>> handleKnowledgeDocumentConflict(KnowledgeDocumentConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.failure(2002, "knowledge document operation conflicts with its current state"));
    }

    @ExceptionHandler(ConversationAccessDeniedException.class)
    ResponseEntity<Result<Void>> handleConversationAccessDenied(ConversationAccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.failure(1003, "forbidden"));
    }

    @ExceptionHandler(MessageFeedbackStateException.class)
    ResponseEntity<Result<Void>> handleMessageFeedbackState(MessageFeedbackStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.failure(2002, exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Result<Void>> handleUnexpected(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.failure(5000, "internal server error"));
    }
}
