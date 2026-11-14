package com.doctrail.dto.user.request;

import jakarta.validation.constraints.NotBlank;

//`value` 就是**前端传过来的反馈内容字段名**
public record MessageFeedbackRequest(@NotBlank String value) {
}
