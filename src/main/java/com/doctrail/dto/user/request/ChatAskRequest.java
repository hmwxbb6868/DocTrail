package com.doctrail.dto.user.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ChatAskRequest(
        @NotBlank @Size(max = 4000) String question,
//        会话ID，@Positive：数值必须大于 0
        @NotNull @Positive Long conversationId
) {
}
