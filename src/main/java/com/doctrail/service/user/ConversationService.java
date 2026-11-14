package com.doctrail.service.user;

import com.doctrail.dto.user.request.CreateConversationRequest;
import com.doctrail.dto.user.request.UpdateConversationRequest;
import com.doctrail.dto.user.response.ConversationResponse;
import com.doctrail.dto.user.response.MessageResponse;
import com.doctrail.dto.user.request.MessageFeedbackRequest;
import com.doctrail.security.AuthenticatedIdentity;

import java.util.List;

public interface ConversationService {
    List<ConversationResponse> list(AuthenticatedIdentity identity);

    ConversationResponse create(AuthenticatedIdentity identity, CreateConversationRequest request);

    ConversationResponse update(
            AuthenticatedIdentity identity,
            long conversationId,
            UpdateConversationRequest request
    );

    void delete(AuthenticatedIdentity identity, long conversationId);

    List<MessageResponse> listMessages(AuthenticatedIdentity identity, long conversationId);

    MessageResponse submitFeedback(AuthenticatedIdentity identity, long messageId, MessageFeedbackRequest request);
}
