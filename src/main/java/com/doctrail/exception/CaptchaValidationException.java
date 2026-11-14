package com.doctrail.exception;
//验证码校验异常
public class CaptchaValidationException extends RuntimeException {
    public CaptchaValidationException() {
        super("invalid or expired captcha");
    }
}
