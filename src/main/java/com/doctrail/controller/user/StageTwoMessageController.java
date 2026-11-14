package com.doctrail.controller.user;

import com.doctrail.common.Result;
import com.doctrail.dto.user.request.ChatAskRequest;
import com.doctrail.dto.user.response.ChatAskResponse;
import com.doctrail.security.AuthenticatedIdentity;
import com.doctrail.service.user.StageTwoMessageService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("stage2-auth")
@RequestMapping("/api/chat")
public class StageTwoMessageController {
    private final StageTwoMessageService messageService;

    public StageTwoMessageController(StageTwoMessageService messageService) {
        this.messageService = messageService;
    }

//    **发送消息接口**
    @PostMapping("/messages")
    public Result<ChatAskResponse> send(
//            ChatAskRequest：question conversationId
            @Valid @RequestBody ChatAskRequest request,
//            requestId 标识的是单次 HTTP 请求，每一次点击【发送消息】，都生成一个全新独立的 requestId
            @RequestHeader("X-Request-Id") String requestId
    ) {
        return Result.success(messageService.send(currentIdentity(), request, requestId));
    }

    private AuthenticatedIdentity currentIdentity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedIdentity identity)) {
            throw new IllegalStateException("authenticated identity is missing");
        }
        return identity;
    }
}
