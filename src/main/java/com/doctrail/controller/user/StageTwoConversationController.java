package com.doctrail.controller.user;

import com.doctrail.common.Result;
import com.doctrail.dto.user.request.CreateConversationRequest;
import com.doctrail.dto.user.request.UpdateConversationRequest;
import com.doctrail.dto.user.response.ConversationResponse;
import com.doctrail.dto.user.response.MessageResponse;
import com.doctrail.dto.user.request.MessageFeedbackRequest;
import com.doctrail.security.AuthenticatedIdentity;
import com.doctrail.service.user.ConversationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Profile("stage2-auth")
@RequestMapping("/api/chat")
public class StageTwoConversationController {
    private final ConversationService conversationService;

    public StageTwoConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }
//  展示用户会话列表
    @GetMapping("/conversations")
    public Result<List<ConversationResponse>> list() {
        return Result.success(conversationService.list(currentIdentity()));
    }

//    创建新会话
//    返回ConversationResponse：前端侧边栏会话列表 + 跳转会话页面，都要用到这个返回结果
    @PostMapping("/conversations")
    public Result<ConversationResponse> create(
//           CreateConversationRequest：包含会话标题 String title
            @Valid @RequestBody CreateConversationRequest request
    ) {
        return Result.success(conversationService.create(currentIdentity(), request));
    }

//    修改会话title
    @PutMapping("/conversations/{conversationId}")
    public Result<ConversationResponse> update(
            @PathVariable @Positive long conversationId,
            @Valid @RequestBody UpdateConversationRequest request
    ) {
        return Result.success(conversationService.update(currentIdentity(), conversationId, request));
    }

//    删除会话
    @DeleteMapping("/conversations/{conversationId}")
    public Result<Void> delete(@PathVariable @Positive long conversationId) {
        conversationService.delete(currentIdentity(), conversationId);
        return Result.success(null);
    }
//    展示会话消息列表
    @GetMapping("/messages")
    public Result<List<MessageResponse>> listMessages(
            @RequestParam @Positive long conversationId
    ) {
        return Result.success(conversationService.listMessages(currentIdentity(), conversationId));
    }

//    给某一条消息提交用户反馈（点赞 / 点踩、评价、备注等）
    @PutMapping("/messages/{messageId}/feedback")
    public Result<MessageResponse> submitFeedback(
            @PathVariable @Positive long messageId,
//            MessageFeedbackRequest：String value
            @Valid @RequestBody MessageFeedbackRequest request
    ) {
        return Result.success(conversationService.submitFeedback(currentIdentity(), messageId, request));
    }

    private AuthenticatedIdentity currentIdentity() {
        // 1. 从Spring Security上下文拿到当前请求的认证对象
//        `SecurityContextHolder`：Spring Security 提供的工具，底层基于`ThreadLocal`，**每个 HTTP 请求线程独立存储认证信息**。
//        - `getContext()`：拿到当前请求的安全上下文容器。
//        - `getAuthentication()`：从容器取出**Authentication 认证对象**（认证的 “包装盒子”，
//        有三个对象：*principal 主体（你是谁） *credentials 凭证  *authorities 权限集合**）。
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
//        authentication.getPrincipal() instanceof AuthenticatedIdentity identity
//        - 判断`principal`是不是`AuthenticatedIdentity`这个类型；
//       如果是，**自动强转，直接赋值给变量`identity`**。
//       前面加`!`：取反 → **如果不是这个类型，条件成立**
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedIdentity identity)) {
//            AuthenticatedIdentity：Long userId, String username, String role
            throw new IllegalStateException("authenticated identity is missing");
        }
        return identity;
    }
}
