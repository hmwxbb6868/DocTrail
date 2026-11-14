package com.doctrail.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.doctrail.config.RefreshTokenProperties;
import com.doctrail.dto.user.response.AuthSessionResponse;
import com.doctrail.exception.RefreshTokenFailureException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

@Service
@Profile("stage2-auth")
public class RefreshTokenService {
    private static final String KEY_PREFIX = "doctrail:auth:refresh:";
//    **`SecureRandom`**：安全随机数生成器
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final JwtService jwtService;
    private final RefreshTokenProperties properties;

    public RefreshTokenService(StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper,
                                JwtService jwtService,
                                RefreshTokenProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.jwtService = jwtService;
        this.properties = properties;
    }
// **登录环节使用**，拿到合法 accessToken 之后，**首次创建 refreshToken 会话，下发 refresh Cookie**
    public void issueFromAccessToken(String accessToken, HttpServletResponse response) {
        storeNewSession(jwtService.parse(accessToken), response);
    }

//    **accessToken 过期后刷新环节使用**，拿 Cookie 里的旧 refreshToken，换取新 accessToken + 换新 refreshToken
    public AuthSessionResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String rawToken = readCookie(request);
        if (rawToken == null) {
            throw new RefreshTokenFailureException("refresh token is missing or invalid");
        }
//        `getAndDelete(key)`：**原子操作**去 Redis 查询这个 key 对应的值
//        ✅ 如果查到：返回这个 value，**同时立刻删除这条 key**
//-       ✅ 如果查不到：返回 null，什么都不删
//        serialized：读出用户信息的字符串
        String serialized = redisTemplate.opsForValue().getAndDelete(redisKey(rawToken));
        if (serialized == null) {
            throw new RefreshTokenFailureException("refresh token is missing or invalid");
        }
//        专门用来和 Redis 打交道：Redis 取出 JSON，反序列化成 RefreshSession
        RefreshSession session = deserialize(serialized);
//        ## AuthenticatedIdentity，**通用身份对象，整个 JWT 认证体系通用**
        AuthenticatedIdentity identity = new AuthenticatedIdentity(session.userId(), session.username(), session.role());
        String accessToken = jwtService.issue(identity.userId(), identity.username(), identity.role());
        storeNewSession(identity, response);
        return new AuthSessionResponse(accessToken, false);
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String rawToken = readCookie(request);
        if (rawToken != null) {
            redisTemplate.opsForValue().getAndDelete(redisKey(rawToken));
        }
        clearCookie(response);
    }

    private void storeNewSession(AuthenticatedIdentity identity, HttpServletResponse response) {
        //生成安全随机字符串
        String rawToken = newRawToken();
        try {
//            用户身份信息组装成 Map，再转成 JSON 字符串，作为 Redis 的值
            String serialized = objectMapper.writeValueAsString(
                    Map.of("userId", identity.userId(), "username", identity.username(), "role", identity.role())
            );
            redisTemplate.opsForValue().set(redisKey(rawToken), serialized, properties.tokenTtl());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize refresh session", exception);
        }
        writeCookie(response, rawToken);
    }


//    反序列化Redis会话JSON，校验会话字段合法性，解析失败/数据非法则抛出刷新令牌异常
    private RefreshSession deserialize(String serialized) {
        try {
            RefreshSession session = objectMapper.readValue(serialized, RefreshSession.class);
            if (session.userId() == null || session.userId() < 1 || session.username() == null
                    || session.username().isBlank() || session.role() == null
                    || (!"USER".equals(session.role()) && !"ADMIN".equals(session.role()))) {
                throw new RefreshTokenFailureException("refresh token session is invalid");
            }
            return session;
            // JSON格式不对，无法转成对象，抛异常
        } catch (JsonProcessingException exception) {
            throw new RefreshTokenFailureException("refresh token session is invalid", exception);
        }
    }


//    从本次请求的 Cookie 数组里，找到名字指定的 refresh_token Cookie
    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (properties.cookieName().equals(cookie.getName()) && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }


//    创建一个高安全级别的 Cookie，存放 refreshToken，
//    设置 HttpOnly、Secure、SameSite、path、过期时间，通过 Set-Cookie 头返回浏览器。
    private void writeCookie(HttpServletResponse response, String rawToken) {
        ResponseCookie cookie = ResponseCookie.from(properties.cookieName(), rawToken)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite(properties.cookieSameSite())
                .path(properties.cookiePath())
                .maxAge(properties.tokenTtl().toSeconds())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }


    private void clearCookie(HttpServletResponse response) {
//        创建 Cookie 建造器，指定 Cookie 名字，并且把 Cookie 的值置空
//        必须和创建这个 Cookie 时的配置完全一致，浏览器是靠【Cookie 名称 + Path + Domain】定位 Cookie；只要属性对不上，浏览器就找不到旧 Cookie，删除指令失效。
//`       .maxAge(0)` 是核心，代表**立刻删除**。
        ResponseCookie cookie = ResponseCookie.from(properties.cookieName(), "")
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite(properties.cookieSameSite())
                .path(properties.cookiePath())
                .maxAge(0)
                .build();
//        `HttpHeaders.SET_COOKIE`：常量，代表响应头名字 `Set-Cookie`
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }


//    生成一串随机的 refreshToken 原始字符串
    private String newRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
//        把二进制随机字节，转成**URL 安全的 Base64 字符串**
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }


//接收从 Cookie 读取出来的**原始 rawToken 明文**对它做 SHA‑256 单向哈希计算，
// 再转成 URL 安全 Base64 字符串，拼接前缀，生成**Redis 查询用的 key**。
    private String redisKey(String rawToken) {
        try {
//            MessageDigest.getInstance("SHA-256")拿到 SHA-256 哈希算法实例。
//            `.digest(rawToken.getBytes(StandardCharsets.UTF_8));`
//            把原始 refreshToken 字符串转成 UTF-8 字节，做 SHA256 计算，得到 256 位哈希字节数组。
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record RefreshSession(Long userId, String username, String role) {
    }
}
