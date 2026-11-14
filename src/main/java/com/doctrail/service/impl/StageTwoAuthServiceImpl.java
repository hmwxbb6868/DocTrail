package com.doctrail.service.impl;

import com.doctrail.dto.user.request.AuthRequest;
import com.doctrail.dto.user.request.RegisterRequest;
import com.doctrail.dto.user.response.AuthSessionResponse;
import com.doctrail.config.AuthPolicyProperties;
import com.doctrail.entity.UserAccountEntity;
import com.doctrail.exception.AuthenticationFailureException;
import com.doctrail.exception.StageFeatureUnavailableException;
import com.doctrail.exception.AccountConflictException;
import com.doctrail.mapper.UserAccountMapper;
import com.doctrail.security.JwtService;
import com.doctrail.service.user.CaptchaService;
import com.doctrail.service.user.AuthService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;

@Service
@Profile("stage2-auth")
public class StageTwoAuthServiceImpl implements AuthService {
    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CaptchaService captchaService;
    private final AuthPolicyProperties authPolicyProperties;

    public StageTwoAuthServiceImpl(UserAccountMapper userAccountMapper, PasswordEncoder passwordEncoder,
                                   JwtService jwtService, CaptchaService captchaService,
                                   AuthPolicyProperties authPolicyProperties) {
        this.userAccountMapper = userAccountMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.captchaService = captchaService;
        this.authPolicyProperties = authPolicyProperties;
    }

    @Override
    public AuthSessionResponse login(AuthRequest request) {
        UserAccountEntity account = userAccountMapper.selectByUsername(request.username().trim());
        if (account == null || account.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), account.getPasswordHash())
                || !"USER".equals(account.getRole())) {
            throw new AuthenticationFailureException("invalid credentials");
        }
        return new AuthSessionResponse(
                jwtService.issue(account.getId(), account.getUsername(), account.getRole()), false
        );
    }

    @Override
    public AuthSessionResponse register(RegisterRequest request) {
        // 1. 判断：是否开启开发控制台注册功能
        if (!authPolicyProperties.developmentConsoleRegistrationEnabled()) {
            throw new StageFeatureUnavailableException("public registration is disabled by server policy");
        }
        // ========== 2. 校验验证码！==========
        captchaService.verifyAndConsume(request.captchaId(), request.captchaCode());
        // 能走到下面代码，说明验证码校验通过！
        // 如果验证码错误/过期/无效，这一行直接抛异常，后面注册代码不会执行
        String username = request.username().trim();
        if (userAccountMapper.selectByUsername(username) != null) {
            throw new AccountConflictException("username already exists");
        }
        // 4. 组装账号实体，密码加密
        UserAccountEntity account = new UserAccountEntity();
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        account.setRole("USER");
        try {
            // 5. 插入数据库新建账号
            userAccountMapper.insert(account);
        } catch (DuplicateKeyException exception) {
            throw new AccountConflictException("username already exists");
        }
        // 6. 签发JWT，返回登录会话
        return new AuthSessionResponse(jwtService.issue(account.getId(), username, "USER"), false);
    }

    @Override
    public AuthSessionResponse refresh() {
        throw new StageFeatureUnavailableException("refresh token service is not available");
    }

    @Override
    public void logout() {
        throw new StageFeatureUnavailableException("refresh token service is not available");
    }
}
