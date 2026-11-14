package com.doctrail.service.impl;

import com.doctrail.dto.admin.response.AdminAuthSessionResponse;
import com.doctrail.dto.user.request.AuthRequest;
import com.doctrail.entity.AdminAccountEntity;
import com.doctrail.exception.AuthenticationFailureException;
import com.doctrail.mapper.AdminAccountMapper;
import com.doctrail.security.JwtService;
import com.doctrail.service.admin.AdminAuthService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Profile("stage2-auth")
public class StageTwoAdminAuthServiceImpl implements AdminAuthService {
    private final AdminAccountMapper adminAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public StageTwoAdminAuthServiceImpl(AdminAccountMapper adminAccountMapper, PasswordEncoder passwordEncoder,
                                        JwtService jwtService) {
        this.adminAccountMapper = adminAccountMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Override
    public AdminAuthSessionResponse login(AuthRequest request) {
        AdminAccountEntity account = adminAccountMapper.selectByUsername(request.username().trim());
        if (account == null || account.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), account.getPasswordHash())
                || !"ADMIN".equals(account.getRole())) {
            throw new AuthenticationFailureException("invalid credentials");
        }
        return new AdminAuthSessionResponse(
                jwtService.issue(account.getId(), account.getUsername(), account.getRole()), false
        );
    }
}
