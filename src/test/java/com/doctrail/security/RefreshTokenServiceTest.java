package com.doctrail.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.doctrail.config.JwtProperties;
import com.doctrail.config.RefreshTokenProperties;
import com.doctrail.dto.user.response.AuthSessionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RefreshTokenServiceTest {
    private static final String SECRET = "01234567890123456789012345678901";
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RefreshTokenProperties properties = new RefreshTokenProperties(
            Duration.ofDays(7), "refresh_token", false, "Lax", "/api/auth");
    private final JwtService jwtService = new JwtService(
            new JwtProperties(SECRET, Duration.ofMinutes(30)),
            Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC));
    private final RefreshTokenService service = new RefreshTokenService(
            redis, new ObjectMapper(), jwtService, properties);

    @Test
    void issueStoresOnlyHashedKeyAndSetsHttpOnlyCookie() {
        when(redis.opsForValue()).thenReturn(values);
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.issueFromAccessToken(jwtService.issue(7L, "demo", "USER"), response);

        verify(values).set(argThat((String key) -> key.startsWith("doctrail:auth:refresh:")
                        && !key.contains("refresh_token")), anyString(), eq(Duration.ofDays(7)));
        assertThat(response.getHeader("Set-Cookie"))
                .contains("refresh_token=")
                .contains("HttpOnly")
                .contains("Path=/api/auth")
                .contains("SameSite=Lax");
    }

    @Test
    void logoutDeletesSessionAndClearsCookie() {
        when(redis.opsForValue()).thenReturn(values);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("refresh_token", "raw-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.logout(request, response);

        verify(values).getAndDelete(anyString());
        assertThat(response.getHeader("Set-Cookie"))
                .contains("refresh_token=")
                .contains("Max-Age=0");
    }

    @Test
    void refreshConsumesOldCookieAndIssuesReplacementSession() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.getAndDelete(anyString())).thenReturn(
                "{\"userId\":7,\"username\":\"demo\",\"role\":\"USER\"}");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("refresh_token", "old-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        AuthSessionResponse result = service.refresh(request, response);

        assertThat(result.mock()).isFalse();
        assertThat(jwtService.parse(result.accessToken()))
                .isEqualTo(new AuthenticatedIdentity(7L, "demo", "USER"));
        verify(values).getAndDelete(anyString());
        verify(values, times(1)).set(anyString(), anyString(), eq(Duration.ofDays(7)));
        assertThat(response.getHeader("Set-Cookie")).contains("HttpOnly");
    }
}
