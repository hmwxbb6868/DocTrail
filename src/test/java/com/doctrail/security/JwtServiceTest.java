package com.doctrail.security;

import com.doctrail.config.JwtProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {
    private static final String SECRET = "01234567890123456789012345678901";
    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    @Test
    void issuedTokenContainsIdentityAndCanBeParsed() {
        JwtService service = service(Duration.ofMinutes(30));

        String token = service.issue(7L, "demo", "USER");

        assertThat(service.parse(token)).isEqualTo(new AuthenticatedIdentity(7L, "demo", "USER"));
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService service = service(Duration.ofSeconds(30));
        String token = service.issue(7L, "demo", "USER");
        JwtService later = new JwtService(new JwtProperties(SECRET, Duration.ofSeconds(30)),
                Clock.fixed(NOW.plusSeconds(31), ZoneOffset.UTC));

        assertThatThrownBy(() -> later.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void shortSecretIsRejected() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new JwtProperties("too-short", Duration.ofMinutes(30)));
    }

    @Test
    void tokenWithMismatchedSubjectIsRejected() {
        String token = Jwts.builder()
                .subject("different-user")
                .claim("userId", 7L)
                .claim("username", "demo")
                .claim("role", "USER")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(30)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> service(Duration.ofMinutes(30)).parse(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JWT identity claims are incomplete");
    }

    private JwtService service(Duration ttl) {
        return new JwtService(new JwtProperties(SECRET, ttl), Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
