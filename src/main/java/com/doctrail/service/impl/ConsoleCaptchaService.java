package com.doctrail.service.impl;

import com.doctrail.config.AuthPolicyProperties;
import com.doctrail.dto.user.response.CaptchaChallengeResponse;
import com.doctrail.exception.StageFeatureUnavailableException;
import com.doctrail.exception.CaptchaValidationException;
import com.doctrail.service.user.CaptchaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;


@Service
@Profile("stage2-auth")
public class ConsoleCaptchaService implements CaptchaService {
    private static final Logger log = LoggerFactory.getLogger(ConsoleCaptchaService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthPolicyProperties properties;
    private final Clock clock;
    private final Map<String, CaptchaEntry> challenges = new ConcurrentHashMap<>();
//  正常业务代码（Spring 注入，最常用）
    @Autowired
    public ConsoleCaptchaService(AuthPolicyProperties properties) {
        this(properties, Clock.systemUTC());
    }
// **单元测试场景**（手动指定时间）
    ConsoleCaptchaService(AuthPolicyProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    //  后端生成 captchaId，连同过期时间一起返回给前端
    @Override
    public CaptchaChallengeResponse issue() {
        //保证 `ConsoleCaptchaImpl` 这个实现类，只有在配置 `captcha.mode=console` 的情况下才允许被使用
        if (!properties.captcha().consoleMode()) {
            throw new StageFeatureUnavailableException("console captcha is not enabled");
        }
//        `UUID.randomUUID()` 生成一个全局唯一字符串
        String captchaId = UUID.randomUUID().toString();
        String code = String.format("%05d", RANDOM.nextInt(100000));
        Instant expiresAt = Instant.now(clock).plus(properties.captcha().ttl());
        challenges.put(captchaId, new CaptchaEntry(code, expiresAt));
        log.info("development captcha issued captchaId={} code={} expiresAt={}", captchaId, code, expiresAt);
        return new CaptchaChallengeResponse(captchaId, properties.captcha().ttl().toSeconds());
    }


//    `captchaCode`：**用户输入的验证码**（console 模式下就是 5 位数字，开发人员从后端日志抄过来填到输入框）
    @Override
    public void verifyAndConsume(String captchaId, String captchaCode) {
        // 1. 根据captchaId删除并取出验证码记录，captchaId为null则entry为null
//        `remove(key)`：**原子操作**，从 Map 中根据 key 查找，**查到就把这条记录从 Map 删掉，同时返回这条记录**；
        CaptchaEntry entry = captchaId == null ? null : challenges.remove(captchaId);
        // 2. 任意一种失败情况，抛出验证码校验异常
        if (entry == null || Instant.now(clock).isAfter(entry.expiresAt())
                || !constantTimeEquals(entry.code(), captchaCode)) {
            throw new CaptchaValidationException();
        }
    }

//    固定时间字符串比对方法，用来安全对比验证码
//    `expected`：服务端保存的**正确验证码**；`actual`：用户前端传过来输入的验证码。
    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
//expiresAt代表一个精确时间点, **code--验证码正确答案
    private record CaptchaEntry(String code, Instant expiresAt) {
    }
}
