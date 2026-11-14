package com.doctrail.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "doctrail.auth.refresh")
public record RefreshTokenProperties(
        Duration tokenTtl,// refreshToken 有效期时长
        String cookieName,// cookie 的名字，前端拿到的cookie名称
        boolean cookieSecure,// cookie 是否只通过https协议传输
        String cookieSameSite,// 控制**跨站请求时浏览器要不要带上 Cookie**，用来防御 CSRF 攻击。
        // 属性：Lax / Strict / None
        String cookiePath// 哪些请求路径下，浏览器才会发送这个 Cookie
) {
    public RefreshTokenProperties {
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()) {
            throw new IllegalStateException("REFRESH_TOKEN_TTL_SECONDS must be positive");
        }
        if (cookieName == null || cookieName.isBlank()) {
            throw new IllegalStateException("REFRESH_COOKIE_NAME must be configured");
        }
        if (cookieSameSite == null || cookieSameSite.isBlank()) {
            throw new IllegalStateException("REFRESH_COOKIE_SAME_SITE must be configured");
        }
        if (!"Lax".equalsIgnoreCase(cookieSameSite)
                && !"Strict".equalsIgnoreCase(cookieSameSite)
                && !"None".equalsIgnoreCase(cookieSameSite)) {
            throw new IllegalStateException("REFRESH_COOKIE_SAME_SITE must be Lax, Strict, or None");
        }
        if ("None".equalsIgnoreCase(cookieSameSite) && !cookieSecure) {
            throw new IllegalStateException("REFRESH_COOKIE_SECURE must be true when SameSite is None");
        }
        if (cookiePath == null || !cookiePath.startsWith("/")) {
            throw new IllegalStateException("REFRESH_COOKIE_PATH must start with '/'");
        }
    }
}
