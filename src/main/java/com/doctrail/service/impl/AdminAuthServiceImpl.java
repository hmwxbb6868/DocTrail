package com.doctrail.service.impl;

import com.doctrail.dto.admin.response.AdminAuthSessionResponse;
import com.doctrail.dto.user.request.AuthRequest;
import com.doctrail.service.admin.AdminAuthService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Bootstrap-only mock admin authentication for the one-off stage2-auth-bootstrap maintenance run. */
@Service
@Profile("stage2-auth-bootstrap")
public class AdminAuthServiceImpl implements AdminAuthService {
    @Override
    public AdminAuthSessionResponse login(AuthRequest request) {
        return new AdminAuthSessionResponse("stage1-mock-admin-access-token", true);
    }
}
