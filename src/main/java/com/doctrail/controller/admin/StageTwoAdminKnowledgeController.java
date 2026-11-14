package com.doctrail.controller.admin;

import com.doctrail.common.Result;
import com.doctrail.dto.admin.response.KnowledgeDocumentResponse;
import com.doctrail.service.admin.KnowledgeAdminService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@Profile("stage2-auth")
@RequestMapping("/api/admin/knowledge/documents")
public class StageTwoAdminKnowledgeController {
    private final KnowledgeAdminService knowledgeAdminService;

    public StageTwoAdminKnowledgeController(KnowledgeAdminService knowledgeAdminService) {
        this.knowledgeAdminService = knowledgeAdminService;
    }

    //知识库文档的 列表查询
    @GetMapping
    public Result<List<KnowledgeDocumentResponse>> list() {
        return Result.success(knowledgeAdminService.listAdmin());
    }

    //文件上传
    @PostMapping
    public ResponseEntity<Result<KnowledgeDocumentResponse>> upload(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Result.success(knowledgeAdminService.upload(file)));
    }

    //文档删除
    @DeleteMapping("/{docId}")
    public Result<Void> delete(@PathVariable long docId) {
        knowledgeAdminService.delete(docId);
        return Result.success(null);
    }
}
