package com.doctrail.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "doctrail.auth")
public record AuthPolicyProperties(
        String environment,//环境标识
        boolean publicRegistrationEnabled,//是否开放自助注册。`true`：允许用户自己注册账号,`false`：关闭注册，只能管理员创建账号
        String smsProvider,//短信服务商。
        Captcha captcha //嵌套配置对象
) {
    public AuthPolicyProperties {
        if (environment == null || environment.isBlank()) {
            throw new IllegalStateException("AUTH_ENVIRONMENT must be configured");
        }
        if (!"development".equalsIgnoreCase(environment)
                && !"production".equalsIgnoreCase(environment)) {
            throw new IllegalStateException("AUTH_ENVIRONMENT must be development or production");
        }
        if (captcha == null) {
            throw new IllegalStateException("doctrail.auth.captcha must be configured");
        }
        if ("production".equalsIgnoreCase(environment) && captcha.consoleMode()) {
            throw new IllegalStateException("console captcha cannot be enabled in production");
        }
    }

    public boolean developmentConsoleRegistrationEnabled() {
        //当前配置里的环境 `environment` 必须等于 `development`
        return "development".equalsIgnoreCase(environment)
                && publicRegistrationEnabled
                && captcha.consoleMode();//consoleMode：控制台模式。一般这个模式下**跳过图形验证码校验**;
    }
//`String mode`：验证码模式，只允许 3 种取值
//- `console`：控制台模式  `disabled`：验证码**完全关闭**    `sms`：短信验证码模式
//    `Duration ttl`：验证码有效期
    public record Captcha(String mode, Duration ttl) {
        public Captcha {
            if (mode == null || mode.isBlank()) {
                throw new IllegalStateException("AUTH_CAPTCHA_MODE must be configured");
            }
            if (!"console".equalsIgnoreCase(mode) && !"disabled".equalsIgnoreCase(mode)
                    && !"sms".equalsIgnoreCase(mode)) {
                throw new IllegalStateException("AUTH_CAPTCHA_MODE must be console, sms, or disabled");
            }
            if (ttl == null || ttl.isZero() || ttl.isNegative()
                    || ttl.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalStateException("AUTH_CAPTCHA_TTL_SECONDS must be between 1 and 600");
            }
        }

        public boolean consoleMode() {
            return "console".equalsIgnoreCase(mode);
        }
    }
}
