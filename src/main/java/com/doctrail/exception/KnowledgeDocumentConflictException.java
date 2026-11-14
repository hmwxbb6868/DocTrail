package com.doctrail.exception;

//上传文档时，检测到相同内容的文档已经存在（冲突）
public class KnowledgeDocumentConflictException extends RuntimeException {
    public KnowledgeDocumentConflictException(String message) {
        super(message);
    }
}
