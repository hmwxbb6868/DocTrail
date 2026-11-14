package com.doctrail.controller.admin;

import com.doctrail.common.Result;
import com.doctrail.dto.admin.response.AdminAuthSessionResponse;
import com.doctrail.dto.user.request.AuthRequest;
import com.doctrail.security.RefreshTokenService;
import com.doctrail.service.admin.AdminAuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {
    private final AdminAuthService adminAuthService;
    private final ObjectProvider<RefreshTokenService> refreshTokenService;

    public AdminAuthController(AdminAuthService adminAuthService,
                               ObjectProvider<RefreshTokenService> refreshTokenService) {
        this.adminAuthService = adminAuthService;
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/login")
    public Result<AdminAuthSessionResponse> login(@Valid @RequestBody AuthRequest request,
                                                  HttpServletResponse response) {
        AdminAuthSessionResponse session = adminAuthService.login(request);
        RefreshTokenService refreshService = refreshTokenService.getIfAvailable();
        if (refreshService != null) {
            refreshService.issueFromAccessToken(session.accessToken(), response);
        }
        return Result.success(session);
    }
}
