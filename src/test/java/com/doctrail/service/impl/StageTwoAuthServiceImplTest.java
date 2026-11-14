package com.doctrail.service.impl;

import com.doctrail.config.JwtProperties;
import com.doctrail.config.AuthPolicyProperties;
import com.doctrail.dto.user.request.AuthRequest;
import com.doctrail.entity.UserAccountEntity;
import com.doctrail.exception.AuthenticationFailureException;
import com.doctrail.exception.StageFeatureUnavailableException;
import com.doctrail.mapper.UserAccountMapper;
import com.doctrail.security.JwtService;
import com.doctrail.service.user.CaptchaService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StageTwoAuthServiceImplTest {
    private static final String SECRET = "01234567890123456789012345678901";
    private final UserAccountMapper mapper = mock(UserAccountMapper.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final CaptchaService captchaService = mock(CaptchaService.class);
    private final AuthPolicyProperties policy = new AuthPolicyProperties(
            "development", true, "", new AuthPolicyProperties.Captcha("console", Duration.ofMinutes(5)));
    private final JwtService jwtService = new JwtService(
            new JwtProperties(SECRET, Duration.ofMinutes(30)),
            Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void loginIssuesNonMockAccessTokenForValidUser() {
        UserAccountEntity account = account(7L, "demo", "USER", "demo-password");
        when(mapper.selectByUsername("demo")).thenReturn(account);
        StageTwoAuthServiceImpl service = new StageTwoAuthServiceImpl(mapper, encoder, jwtService, captchaService, policy);

        var result = service.login(new AuthRequest("demo", "demo-password"));

        assertThat(result.mock()).isFalse();
        assertThat(jwtService.parse(result.accessToken()))
                .isEqualTo(new com.doctrail.security.AuthenticatedIdentity(7L, "demo", "USER"));
    }

    @Test
    void invalidPasswordDoesNotIssueToken() {
        when(mapper.selectByUsername("demo")).thenReturn(account(7L, "demo", "USER", "demo-password"));
        StageTwoAuthServiceImpl service = new StageTwoAuthServiceImpl(mapper, encoder, jwtService, captchaService, policy);

        assertThatThrownBy(() -> service.login(new AuthRequest("demo", "wrong-password")))
                .isInstanceOf(AuthenticationFailureException.class);
    }

    @Test
    void registerConsumesCaptchaAndStoresBcryptUser() {
        doAnswer(invocation -> {
            UserAccountEntity account = invocation.getArgument(0);
            account.setId(8L);
            return 1;
        }).when(mapper).insert(any(UserAccountEntity.class));
        StageTwoAuthServiceImpl service = new StageTwoAuthServiceImpl(mapper, encoder, jwtService, captchaService, policy);

        var result = service.register(new com.doctrail.dto.user.request.RegisterRequest(
                "new-user", "new-password", "00000000-0000-0000-0000-000000000000", "12345"));

        verify(captchaService).verifyAndConsume("00000000-0000-0000-0000-000000000000", "12345");
        var captor = org.mockito.ArgumentCaptor.forClass(UserAccountEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("new-user");
        assertThat(captor.getValue().getRole()).isEqualTo("USER");
        assertThat(captor.getValue().getPasswordHash()).isNotEqualTo("new-password");
        assertThat(encoder.matches("new-password", captor.getValue().getPasswordHash())).isTrue();
        assertThat(result.mock()).isFalse();
    }

    @Test
    void productionPolicyRejectsPublicRegistrationBeforeCaptchaUse() {
        var productionPolicy = new AuthPolicyProperties(
                "production", true, "", new AuthPolicyProperties.Captcha("sms", Duration.ofMinutes(5)));
        StageTwoAuthServiceImpl service = new StageTwoAuthServiceImpl(
                mapper, encoder, jwtService, captchaService, productionPolicy);

        assertThatThrownBy(() -> service.register(new com.doctrail.dto.user.request.RegisterRequest(
                "new-user", "new-password", "00000000-0000-0000-0000-000000000000", "12345")))
                .isInstanceOf(StageFeatureUnavailableException.class);
        verifyNoInteractions(captchaService, mapper);
    }

    private UserAccountEntity account(Long id, String username, String role, String password) {
        UserAccountEntity account = new UserAccountEntity();
        account.setId(id);
        account.setUsername(username);
        account.setRole(role);
        account.setPasswordHash(encoder.encode(password));
        return account;
    }
}
