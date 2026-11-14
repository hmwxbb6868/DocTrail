package com.doctrail.service.user;

import com.doctrail.dto.user.response.CaptchaChallengeResponse;

public interface CaptchaService {
    CaptchaChallengeResponse issue();

    void verifyAndConsume(String captchaId, String captchaCode);
}
