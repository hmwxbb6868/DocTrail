package com.doctrail.dto.user.response;
//expiresInSeconds：过期时间；`captchaId`：**验证码唯一标识**
public record CaptchaChallengeResponse(String captchaId, long expiresInSeconds) {
}
