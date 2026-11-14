package com.doctrail.security;

import com.doctrail.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;


@Service
@Profile("stage2-auth")
@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
public class JwtService {
    private final JwtProperties properties;  // JWT配置对象（密钥、token有效期）
    private final SecretKey signingKey;   // HMAC签名密钥对象，用于签名/验签
    private final Clock clock;  // 时钟对象，用于获取当前时间
//  **Spring 容器用的入口构造**。单参数 public 构造**给 Spring 使用，
//  因为`JwtProperties` 是我们注册好的 Bean，`Clock`（系统时钟）我们**没有注册成 Spring Bean**
//  `this(参数)` 的固定含义：**调用本类里面的另一个构造方法**。
//  这里表示调用 `JwtService(JwtProperties properties, Clock clock)` 构造方法。
    @Autowired
    public JwtService(JwtProperties properties) {
        this(properties, Clock.systemUTC());
    }
// 包访问权限，真正做成员变量赋值、生成签名密钥
    public JwtService(JwtProperties properties, Clock clock) {
        this.properties = properties;
//        `Keys.hmacShaKeyFor(字节数组)`JJWT 提供的工具静态方法：根据传入的字节数组，生成一个 HMAC 算法的签名密钥对象**
//        `getBytes(StandardCharsets.UTF_8)`，把密钥字符串，按照 UTF-8 编码，转成**字节数组**
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }
    //，签发生成 JWT 令牌
    public String issue(Long userId, String username, String role) {
//        令牌**签发时间**：`clock.instant()`：获取**当前 UTC 时间**
        Instant issuedAt = clock.instant();
//        令牌过期时间
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim("role", role)
                .claim("username", username)
//                把 Instant 转为 Date 对象填入。
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
//                使用前面构造方法生成的`signingKey`（HMAC 密钥）对整个 JWT 做**数字签名**。
                .signWith(signingKey)
//               把组装好的 JWT 压缩，拼接成三段式字符串 `header.payload.signature`，返回给调用方
                .compact();
    }

    public AuthenticatedIdentity parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)  // 配置这个校验工具：**使用我们的 HMAC 密钥 `signingKey` 做签名校验**。
                .clock(() -> Date.from(clock.instant()))  // 指定当前时间，用于校验token是否过期
                .build()   //把上面所有配置（验签密钥、时间来源）打包，生成一个真正可用的 JWT 解析器实例。
                .parseSignedClaims(token)    // 解析token；签名错误/过期，直接抛出异常
                .getPayload(); // 从解析成功的对象中，取出**载荷 Claims**
                // （就是我们 issue 方法里放进去的 userId、username、role、iat、exp 等全部数据）
        Number userId = claims.get("userId", Number.class);
        String username = claims.get("username", String.class);
        String role = claims.get("role", String.class);
        Date issuedAt = claims.getIssuedAt();
        Date expiration = claims.getExpiration();
        String subject = claims.getSubject();
        if (userId == null || userId.longValue() < 1 || username == null || username.isBlank()
                || subject == null || !username.equals(subject) || issuedAt == null || expiration == null
                || !expiration.after(issuedAt)
                || role == null || (!"USER".equals(role) && !"ADMIN".equals(role))) {
            throw new IllegalArgumentException("JWT identity claims are incomplete");
        }
        return new AuthenticatedIdentity(userId.longValue(), username, role);
    }
}
