package com.doctrail.service.user;

import com.doctrail.dto.user.response.KnowledgeDocumentResponse;

import java.util.List;

public interface KnowledgeReadService {
    List<KnowledgeDocumentResponse> list();
}
