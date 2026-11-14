package com.doctrail.service.impl;

import com.doctrail.dto.user.request.AuthRequest;
import com.doctrail.dto.user.request.RegisterRequest;
import com.doctrail.dto.user.response.AuthSessionResponse;
import com.doctrail.service.user.AuthService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Bootstrap-only mock authentication for the one-off stage2-auth-bootstrap maintenance run. */
@Service
@Profile("stage2-auth-bootstrap")
public class AuthServiceImpl implements AuthService {
    private static final AuthSessionResponse STUB_SESSION =
            new AuthSessionResponse("stage1-mock-access-token", true);

    @Override
    public AuthSessionResponse login(AuthRequest request) {
        return STUB_SESSION;
    }

    @Override
    public AuthSessionResponse register(RegisterRequest request) {
        return STUB_SESSION;
    }

    @Override
    public AuthSessionResponse refresh() {
        return STUB_SESSION;
    }

    @Override
    public void logout() {
        // No server-side session exists in the stage-1 stub.
    }
}
