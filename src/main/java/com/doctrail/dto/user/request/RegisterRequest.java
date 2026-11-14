package com.doctrail.dto.user.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 64) String username,
        @NotBlank @Size(min = 6, max = 72) String password,
        @NotBlank @Pattern(regexp = "^[0-9a-fA-F-]{36}$") String captchaId,
        @NotBlank @Pattern(regexp = "^[0-9]{4,8}$") String captchaCode
) {
}
