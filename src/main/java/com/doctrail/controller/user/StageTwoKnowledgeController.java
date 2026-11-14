package com.doctrail.controller.user;

import com.doctrail.common.Result;
import com.doctrail.dto.user.response.KnowledgeDocumentResponse;
import com.doctrail.service.user.KnowledgeReadService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Profile("stage2-auth")
@RequestMapping("/api/knowledge")
public class StageTwoKnowledgeController {
    private final KnowledgeReadService knowledgeReadService;

    public StageTwoKnowledgeController(KnowledgeReadService knowledgeReadService) {
        this.knowledgeReadService = knowledgeReadService;
    }

    @GetMapping("/documents")
    public Result<List<KnowledgeDocumentResponse>> list() {
        return Result.success(knowledgeReadService.list());
    }
}
