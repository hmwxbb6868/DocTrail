package com.doctrail.dto.admin.response;

import java.time.OffsetDateTime;
//后端查询知识库文档，返回给前端的**轻量返回对象**
public record KnowledgeDocumentResponse(
        Long id,//文档主键ID
        String name,//文档名称
        String status,//文档状态
        String failureReason,//失败原因
        OffsetDateTime createdAt,//创建时间
        OffsetDateTime updatedAt,//更新时间
        boolean mock//是否mock数据
) {
}
