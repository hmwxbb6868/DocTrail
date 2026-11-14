package com.doctrail.service.admin;

import com.doctrail.dto.admin.response.KnowledgeDocumentResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface KnowledgeAdminService {
    List<KnowledgeDocumentResponse> listAdmin();

    KnowledgeDocumentResponse upload(MultipartFile file);

    void delete(long docId);
}
