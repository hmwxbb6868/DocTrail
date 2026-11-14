package com.doctrail.controller.user;

import com.doctrail.common.Result;
import com.doctrail.dto.user.request.AuthRequest;
import com.doctrail.dto.user.request.RegisterRequest;
import com.doctrail.dto.user.response.AuthSessionResponse;
import com.doctrail.dto.user.response.CaptchaChallengeResponse;
import com.doctrail.security.RefreshTokenService;
import com.doctrail.service.user.AuthService;
import com.doctrail.service.user.CaptchaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
//    **延迟获取**，不是启动立刻注入实例；可以 `.getIfAvailable()` 安全获取 Bean
    private final ObjectProvider<RefreshTokenService> refreshTokenService;
    private final ObjectProvider<CaptchaService> captchaService;

    @Autowired
    public AuthController(AuthService authService, ObjectProvider<RefreshTokenService> refreshTokenService,
                          ObjectProvider<CaptchaService> captchaService) {
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
        this.captchaService = captchaService;
    }

    @PostMapping("/login")
    public Result<AuthSessionResponse> login(@Valid @RequestBody AuthRequest request,
                                             HttpServletResponse response) {
        AuthSessionResponse session = authService.login(request);
        RefreshTokenService refreshService = refreshTokenService.getIfAvailable();
        if (refreshService != null) {
            // 调用refreshService，根据本次登录生成的accessToken，生成新refreshToken，写入Set-Cookie响应头返回浏览器
            refreshService.issueFromAccessToken(session.accessToken(), response);
        }
//        统一包装返回结果给前端，响应体包含accessToken
        return Result.success(session);
    }
//  后端生成 captchaId，连同过期时间一起返回给前端
    @GetMapping("/captcha")
    public Result<CaptchaChallengeResponse> captcha() {
        CaptchaService service = captchaService.getIfAvailable();
        if (service == null) {
            throw new com.doctrail.exception.StageFeatureUnavailableException(
                    "captcha is not available for the active profile");
        }
        return Result.success(service.issue());
    }

    @PostMapping("/register")
    public Result<AuthSessionResponse> register(@Valid @RequestBody RegisterRequest request,
                                                HttpServletResponse response) {
        // 调用业务层注册逻辑
        AuthSessionResponse session = authService.register(request);
        // 获取刷新令牌服务
        RefreshTokenService refreshService = refreshTokenService.getIfAvailable();
        if (refreshService != null) {
            // 如果刷新令牌功能开启，根据accessToken生成refreshToken，写入response（一般是Cookie）
            refreshService.issueFromAccessToken(session.accessToken(), response);
        }
        return Result.success(session);
    }

    @PostMapping("/refresh")
    public Result<AuthSessionResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        RefreshTokenService refreshService = refreshTokenService.getIfAvailable();
        return Result.success(refreshService == null
//               简单模式
                ? authService.refresh()
                : refreshService.refresh(request, response));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        RefreshTokenService refreshService = refreshTokenService.getIfAvailable();
//        判断**Spring 容器里面有没有成功创建 RefreshTokenService 这个 Bean**。
        if (refreshService == null) {
            authService.logout();
        } else {
            refreshService.logout(request, response);
        }
        return Result.success(null);
    }
}
