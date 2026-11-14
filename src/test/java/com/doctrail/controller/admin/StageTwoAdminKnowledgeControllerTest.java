package com.doctrail.controller.admin;

import com.doctrail.dto.admin.response.KnowledgeDocumentResponse;
import com.doctrail.service.admin.KnowledgeAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StageTwoAdminKnowledgeControllerTest {
    @Test
    void uploadUsesAcceptedResponseAndAdminService() {
        KnowledgeAdminService service = mock(KnowledgeAdminService.class);
        when(service.upload(org.mockito.ArgumentMatchers.any())).thenReturn(
                new KnowledgeDocumentResponse(1L, "guide.md", "PENDING", null,
                        OffsetDateTime.parse("2026-10-06T00:00:00Z"),
                        OffsetDateTime.parse("2026-10-06T00:00:00Z"), false));
        StageTwoAdminKnowledgeController controller = new StageTwoAdminKnowledgeController(service);

        var response = controller.upload(new MockMultipartFile("file", "guide.md", "text/plain", "guide".getBytes()));

        assertEquals(202, response.getStatusCode().value());
        assertEquals("PENDING", response.getBody().data().status());
    }
}
