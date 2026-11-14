package com.doctrail.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.doctrail.dto.admin.response.KnowledgeDocumentResponse;
import com.doctrail.entity.KnowledgeDocEntity;
import com.doctrail.exception.KnowledgeDocumentConflictException;
import com.doctrail.exception.ResourceNotFoundException;
import com.doctrail.mapper.KnowledgeDocMapper;
import com.doctrail.service.admin.KnowledgeAdminService;
import com.doctrail.service.admin.KnowledgeProcessingService;
import com.doctrail.service.ai.client.AiDeleteClient;
import com.doctrail.dto.ai.parse.AiDeleteRequest;
import com.doctrail.dto.ai.parse.AiDeleteResponse;
import com.doctrail.exception.AiServiceClientException;
import com.doctrail.service.user.KnowledgeReadService;
import com.doctrail.storage.ObjectStorage;
import com.doctrail.storage.UploadMetadata;
import com.doctrail.storage.UploadValidator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@Profile("stage2-auth")
public class StageTwoKnowledgeServiceImpl implements KnowledgeAdminService, KnowledgeReadService {
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final UploadValidator uploadValidator;
    private final ObjectStorage objectStorage;
    private final KnowledgeProcessingService processingService;
    private final AiDeleteClient aiDeleteClient;

    public StageTwoKnowledgeServiceImpl(KnowledgeDocMapper knowledgeDocMapper,
                                        UploadValidator uploadValidator,
                                        ObjectStorage objectStorage,
                                         KnowledgeProcessingService processingService,
                                         AiDeleteClient aiDeleteClient) {
        this.knowledgeDocMapper = knowledgeDocMapper;
        this.uploadValidator = uploadValidator;
        this.objectStorage = objectStorage;
        this.processingService = processingService;
        this.aiDeleteClient = aiDeleteClient;
    }

    @Override
    public List<KnowledgeDocumentResponse> listAdmin() {
        return knowledgeDocMapper.selectList(Wrappers.<KnowledgeDocEntity>lambdaQuery()
                        .ne(KnowledgeDocEntity::getStatus, "DELETED")   // ← 新增：过滤掉软删的墓碑行
                        .orderByDesc(KnowledgeDocEntity::getUpdateTime)
                        .orderByDesc(KnowledgeDocEntity::getId))
                .stream().map(this::toAdminResponse).toList();
    }

    @Override
    @Transactional
    public KnowledgeDocumentResponse upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file must not be empty");
        }
        byte[] bytes = readBytes(file);
//        上传文件校验 + 提取文件元信息；校验通过后，返回封装好的上传元数据对象 `UploadMetadata`
        UploadMetadata metadata = uploadValidator.validate(file.getOriginalFilename(), file.getContentType(), bytes);
//        系统里有没有已经存在相同文件内容的文档记录
        KnowledgeDocEntity existing = knowledgeDocMapper.selectOne(Wrappers.<KnowledgeDocEntity>lambdaQuery()
                .eq(KnowledgeDocEntity::getContentHash, metadata.contentHash())
                .last("FOR UPDATE"));
//        如果存在相同内容的文档记录，则根据记录的状态，进行不同的处理
        if (existing != null) {
            switch (existing.getStatus()) {
                case "PENDING", "PROCESSING", "COMPLETED" ->
                        // Same content is a replay: preserve the original object, batch and task.
                        // The caller can poll the existing document instead of creating another job.
                        { return toAdminResponse(existing); }
                case "DELETING" -> throw new KnowledgeDocumentConflictException(
                        "document content is being deleted");
                case "FAILED", "DELETED" -> {
//                    这里**不 return、不抛异常，直接跳出 switch，继续往下执行后面的新建 / 更新逻辑**
                      }
//                      出现了代码没定义的未知状态，直接抛异常
                default -> throw new KnowledgeDocumentConflictException(
                        "document content has an unsupported lifecycle state");
            }
        }
        boolean retry = existing != null && "FAILED".equals(existing.getStatus());
//        本次上传准备用的新 key。
        String storageKey = metadata.storageKey();
//        标记是否**复用 OSS 上原来的旧文件，不上传新文件**。
        boolean reusedExistingObject = false;
        if (retry
                && objectStorage.exists(existing.getStorageKey())) {
            storageKey = existing.getStorageKey();// 直接复用旧OSS的key
            reusedExistingObject = true;
        } else {
//      三种情况进else：全新文档 / FAILED但OSS文件丢了 / DELETED文档
            objectStorage.put(storageKey, bytes, metadata.contentType());
        }
        boolean newObjectWritten = !reusedExistingObject;
        boolean persisted = false;
        try {
            KnowledgeDocEntity entity = existing == null ? new KnowledgeDocEntity() : existing;
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
            entity.setDocName(file.getOriginalFilename() == null ? "uploaded-document" : file.getOriginalFilename());
            entity.setStorageKey(storageKey);
            entity.setContentHash(metadata.contentHash());
            entity.setFileSize(metadata.size());
            entity.setContentType(metadata.contentType());
            entity.setStatus("PENDING");
            entity.setErrorMessage(null);
            entity.setCreateTime(existing == null ? now : existing.getCreateTime());
            entity.setUpdateTime(now);
            if (existing == null) {
                knowledgeDocMapper.insert(entity);
            } else {
                knowledgeDocMapper.updateById(entity);
            }
            persisted = true;
            // 入库后，入队任务，交给后台异步解析
            enqueueAfterCommit(entity.getId(), retry);
            return toAdminResponse(entity);
        } finally {
//            如果数据库持久化失败，就去对象存储 OSS 删除刚刚上传的这个文件，清理垃圾文件
            if (!persisted && newObjectWritten) {
                objectStorage.delete(storageKey);
            }
        }
    }

    private void enqueueAfterCommit(Long docId, boolean force) {
        if (docId == null) {
            return;
        }
        // 判断：当前代码是否运行在【活跃的数据库事务】里面
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 注册事务回调：**等事务提交成功之后，才执行enqueue**
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    processingService.enqueue(docId, force);
                }
            });
        } else {
            // 不在事务中：直接立刻调用enqueue提交任务
            processingService.enqueue(docId, force);
        }
    }

    @Override
    public void delete(long docId) {
        KnowledgeDocEntity entity = knowledgeDocMapper.selectById(docId);
        if (entity == null) {
            throw new ResourceNotFoundException("knowledge document not found");
        }
        if ("DELETED".equals(entity.getStatus())) {
            return;// 已经删完，直接返回，不重复执行
        }
        if ("DELETING".equals(entity.getStatus())) {
            // 正在删除中，并发删除冲突，拒绝重复删除
            throw new KnowledgeDocumentConflictException("knowledge document deletion is already in progress");
        }
        if (!"COMPLETED".equals(entity.getStatus()) && !"FAILED".equals(entity.getStatus())) {
            throw new KnowledgeDocumentConflictException("knowledge document is not deletable in its current state");
        }

        entity.setStatus("DELETING");
        entity.setErrorMessage(null);
        touch(entity);
        knowledgeDocMapper.updateById(entity);
        try {
            UUID traceId = UUID.randomUUID();
            AiDeleteResponse response = aiDeleteClient.delete(new AiDeleteRequest(entity.getId(), traceId));
            if (!"DELETED".equals(response.status())) {
                throw new AiServiceClientException(
                        response.errorMessage() == null ? "Python delete failed" : response.errorMessage());
            }
//            OSS删除操作，删除对象存储中的文件
            objectStorage.delete(entity.getStorageKey());
            entity.setStatus("DELETED");
            entity.setErrorMessage(null);
            touch(entity);
            knowledgeDocMapper.updateById(entity);

        } catch (RuntimeException exception) {
            entity.setStatus("DELETING");
            entity.setErrorMessage(safeError(exception));
            touch(entity);
            knowledgeDocMapper.updateById(entity);
            throw exception;
        }
    }

//    给普通用户用的文档列表查询接口：查询状态为 COMPLETED（解析完成）的知识库文档
    @Override
    public List<com.doctrail.dto.user.response.KnowledgeDocumentResponse> list() {
        return knowledgeDocMapper.selectList(Wrappers.<KnowledgeDocEntity>lambdaQuery()
                        .eq(KnowledgeDocEntity::getStatus, "COMPLETED")
                        .orderByDesc(KnowledgeDocEntity::getUpdateTime)
                        .orderByDesc(KnowledgeDocEntity::getId))
                .stream().map(this::toUserResponse).toList();
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("could not read uploaded file", exception);
        }
    }

    private KnowledgeDocumentResponse toAdminResponse(KnowledgeDocEntity entity) {
        return new KnowledgeDocumentResponse(entity.getId(), entity.getDocName(), entity.getStatus(),
                entity.getErrorMessage(), entity.getCreateTime(), entity.getUpdateTime(), false);
    }

    private com.doctrail.dto.user.response.KnowledgeDocumentResponse toUserResponse(KnowledgeDocEntity entity) {
        return new com.doctrail.dto.user.response.KnowledgeDocumentResponse(entity.getId(), entity.getDocName(),
                entity.getStatus(), entity.getErrorMessage(), entity.getCreateTime(), entity.getUpdateTime(), false);
    }

//    刷新这条数据库记录的更新时间字段 `updateTime`
    private void touch(KnowledgeDocEntity entity) {
        entity.setUpdateTime(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
    }

    private String safeError(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
