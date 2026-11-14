package com.doctrail.service.impl;

import com.doctrail.config.AuthPolicyProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsoleCaptchaServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private final AuthPolicyProperties properties = new AuthPolicyProperties(
            "development", true, "", new AuthPolicyProperties.Captcha("console", Duration.ofMinutes(5)));
    private final ConsoleCaptchaService service = new ConsoleCaptchaService(
            properties, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void issueReturnsChallengeAndWrongCodeIsConsumed() {
        var challenge = service.issue();

        assertThat(challenge.captchaId()).isNotBlank();
        assertThat(challenge.expiresInSeconds()).isEqualTo(300);
        assertThatThrownBy(() -> service.verifyAndConsume(challenge.captchaId(), "wrong"))
                .isInstanceOf(com.doctrail.exception.CaptchaValidationException.class);
        assertThatThrownBy(() -> service.verifyAndConsume(challenge.captchaId(), "wrong"))
                .isInstanceOf(com.doctrail.exception.CaptchaValidationException.class);
    }

    @Test
    void disabledCaptchaModeCannotIssueChallenge() {
        var disabled = new ConsoleCaptchaService(
                new AuthPolicyProperties("production", false, "", 
                        new AuthPolicyProperties.Captcha("disabled", Duration.ofMinutes(5))),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(disabled::issue)
                .isInstanceOf(com.doctrail.exception.StageFeatureUnavailableException.class);
    }
}
