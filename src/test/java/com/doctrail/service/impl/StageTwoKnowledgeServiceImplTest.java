package com.doctrail.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.doctrail.entity.KnowledgeDocEntity;
import com.doctrail.dto.ai.parse.AiDeleteResponse;
import com.doctrail.mapper.KnowledgeDocMapper;
import com.doctrail.service.admin.KnowledgeProcessingService;
import com.doctrail.service.ai.client.AiDeleteClient;
import com.doctrail.storage.ObjectStorage;
import com.doctrail.storage.UploadMetadata;
import com.doctrail.storage.UploadValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class StageTwoKnowledgeServiceImplTest {
    @Mock KnowledgeDocMapper knowledgeDocMapper;
    @Mock UploadValidator uploadValidator;
    @Mock ObjectStorage objectStorage;
    @Mock KnowledgeProcessingService processingService;
    @Mock AiDeleteClient aiDeleteClient;
    @InjectMocks StageTwoKnowledgeServiceImpl service;

    @Test
    void uploadValidatesStoresAndPersistsPendingMetadata() {
        MockMultipartFile file = new MockMultipartFile("file", "guide.md", "text/plain", "# guide\n".getBytes());
        UploadMetadata metadata = new UploadMetadata("guide.md", "pending/hash.md", "text/markdown", 8, "a".repeat(64));
        when(knowledgeDocMapper.selectOne(any())).thenReturn(null);
        when(uploadValidator.validate("guide.md", "text/plain", "# guide\n".getBytes())).thenReturn(metadata);

        var response = service.upload(file);

        verify(objectStorage).put("pending/hash.md", "# guide\n".getBytes(), "text/markdown");
        ArgumentCaptor<KnowledgeDocEntity> captor = ArgumentCaptor.forClass(KnowledgeDocEntity.class);
        verify(knowledgeDocMapper).insert(captor.capture());
        assertEquals("guide.md", captor.getValue().getDocName());
        assertEquals("PENDING", captor.getValue().getStatus());
        assertEquals(8L, captor.getValue().getFileSize());
        assertEquals("text/markdown", captor.getValue().getContentType());
        assertEquals(false, response.mock());
    }

    @Test
    void completedDuplicateContentReusesExistingDocumentWithoutWritingObjectOrQueueing() {
        MockMultipartFile file = new MockMultipartFile("file", "guide.txt", "text/plain", "guide".getBytes());
        UploadMetadata metadata = new UploadMetadata("guide.txt", "pending/hash.txt", "text/plain", 5, "b".repeat(64));
        KnowledgeDocEntity existing = new KnowledgeDocEntity();
        existing.setId(46L);
        existing.setDocName("original.txt");
        existing.setStatus("COMPLETED");
        when(uploadValidator.validate(any(), any(), any())).thenReturn(metadata);
        when(knowledgeDocMapper.selectOne(any())).thenReturn(existing);

        var response = service.upload(file);

        assertEquals(46L, response.id());
        verify(objectStorage, never()).put(any(), any(), any());
        verify(processingService, never()).enqueue(any(Long.class));
    }

    @Test
    void failedDuplicateContentReusesObjectAndQueuesOneRetry() {
        MockMultipartFile file = new MockMultipartFile("file", "guide.txt", "text/plain", "guide".getBytes());
        UploadMetadata metadata = new UploadMetadata("guide.txt", "pending/hash.txt", "text/plain", 5, "b".repeat(64));
        KnowledgeDocEntity existing = new KnowledgeDocEntity();
        existing.setId(46L);
        existing.setStatus("FAILED");
        existing.setStorageKey("pending/hash.txt");
        existing.setErrorMessage("previous failure");
        when(uploadValidator.validate(any(), any(), any())).thenReturn(metadata);
        when(knowledgeDocMapper.selectOne(any())).thenReturn(existing);
        when(objectStorage.exists("pending/hash.txt")).thenReturn(true);

        var response = service.upload(file);

        assertEquals("PENDING", existing.getStatus());
        assertEquals(46L, response.id());
        assertEquals(null, existing.getErrorMessage());
        verify(objectStorage, never()).put(any(), any(), any());
        verify(processingService).enqueue(46L, true);
    }

    @Test
    void storageFailureRollsBackObject() {
        MockMultipartFile file = new MockMultipartFile("file", "guide.txt", "text/plain", "guide".getBytes());
        UploadMetadata metadata = new UploadMetadata("guide.txt", "pending/hash.txt", "text/plain", 5, "b".repeat(64));
        when(uploadValidator.validate(any(), any(), any())).thenReturn(metadata);
        when(knowledgeDocMapper.selectOne(any())).thenReturn(null);
        org.mockito.Mockito.doThrow(new RuntimeException("db failed")).when(knowledgeDocMapper)
                .insert(any(KnowledgeDocEntity.class));

        assertThrows(RuntimeException.class, () -> service.upload(file));
        verify(objectStorage).delete("pending/hash.txt");
    }

    @Test
    void deletePublishesDeletingBeforePythonAndCompletesAfterObjectRemoval() {
        KnowledgeDocEntity entity = new KnowledgeDocEntity();
        entity.setId(46L);
        entity.setStatus("COMPLETED");
        entity.setStorageKey("2026/09/document.md");
        UUID traceId = UUID.randomUUID();
        when(knowledgeDocMapper.selectById(46L)).thenReturn(entity);
        when(aiDeleteClient.delete(any())).thenReturn(
                new AiDeleteResponse(46L, "DELETED", 3, traceId, null));

        service.delete(46L);

        verify(objectStorage).delete("2026/09/document.md");
        assertEquals("DELETED", entity.getStatus());
        verify(knowledgeDocMapper, org.mockito.Mockito.times(2)).updateById(entity);
    }

    @Test
    void deleteFailureLeavesDocumentRetryableInDeletingState() {
        KnowledgeDocEntity entity = new KnowledgeDocEntity();
        entity.setId(46L);
        entity.setStatus("COMPLETED");
        entity.setStorageKey("2026/09/document.md");
        UUID traceId = UUID.randomUUID();
        when(knowledgeDocMapper.selectById(46L)).thenReturn(entity);
        when(aiDeleteClient.delete(any())).thenReturn(
                new AiDeleteResponse(46L, "FAILED", 0, traceId, "index rebuild failed"));

        assertThrows(RuntimeException.class, () -> service.delete(46L));

        assertEquals("DELETING", entity.getStatus());
        assertEquals("index rebuild failed", entity.getErrorMessage());
        verify(objectStorage, never()).delete(any());
    }
}
