package com.doctrail.service.user;

import com.doctrail.dto.user.request.ChatAskRequest;
import com.doctrail.dto.user.response.ChatAskResponse;
import com.doctrail.security.AuthenticatedIdentity;

public interface StageTwoMessageService {
    ChatAskResponse send(
            AuthenticatedIdentity identity,
            ChatAskRequest request,
            String requestIdHeader
    );
}
