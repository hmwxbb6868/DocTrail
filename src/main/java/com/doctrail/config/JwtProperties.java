package com.doctrail.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
//标注在配置绑定类`JwtProperties`上，用于**声明该类为配置绑定类**，指定配置项前缀`doctrail.auth.jwt`。
//**不会自动触发配置加载，也不会将该类注册为 Spring Bean**。
@ConfigurationProperties(prefix = "doctrail.auth.jwt")
public record JwtProperties(String secret, Duration accessTokenTtl) {
//    Record 的特殊语法：**紧凑构造器**
//    不是普通构造方法，**在字段赋值完成之后自动执行**，专门用来做参数校验！
    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be configured for stage2-auth");
        }
        if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        if (accessTokenTtl == null || accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
            throw new IllegalStateException("ACCESS_TOKEN_TTL_SECONDS must be positive");
        }
    }
}
