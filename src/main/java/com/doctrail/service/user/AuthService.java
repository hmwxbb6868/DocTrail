package com.doctrail.service.user;

import com.doctrail.dto.user.request.AuthRequest;
import com.doctrail.dto.user.request.RegisterRequest;
import com.doctrail.dto.user.response.AuthSessionResponse;

public interface AuthService {
    AuthSessionResponse login(AuthRequest request);

    AuthSessionResponse register(RegisterRequest request);

    AuthSessionResponse refresh();

    void logout();
}
