package com.doctrail.service.admin;

import com.doctrail.dto.admin.response.AdminAuthSessionResponse;
import com.doctrail.dto.user.request.AuthRequest;

public interface AdminAuthService {
    AdminAuthSessionResponse login(AuthRequest request);
}
