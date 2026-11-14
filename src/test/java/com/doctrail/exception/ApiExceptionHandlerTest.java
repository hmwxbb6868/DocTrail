package com.doctrail.exception;

import com.doctrail.common.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void mapsHandlerMethodValidationToBadRequest() {
        ResponseEntity<Result<Void>> response = handler.handleMethodValidation(
                mock(HandlerMethodValidationException.class));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(4000);
    }

    @Test
    void mapsMissingResourceToNotFound() {
        ResponseEntity<Result<Void>> response = handler.handleNoResourceFound(
                mock(NoResourceFoundException.class));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(2001);
    }
}
