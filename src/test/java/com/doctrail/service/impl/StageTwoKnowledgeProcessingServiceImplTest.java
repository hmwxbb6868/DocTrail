package com.doctrail.service.impl;

import com.doctrail.dto.ai.parse.AiParseResponse;
import com.doctrail.entity.KnowledgeDocEntity;
import com.doctrail.mapper.KnowledgeDocMapper;
import com.doctrail.service.ai.client.AiParseClient;
import com.doctrail.service.admin.KnowledgeProcessingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StageTwoKnowledgeProcessingServiceImplTest {
    @Mock KnowledgeDocMapper knowledgeDocMapper;
    @Mock AiParseClient aiParseClient;
    @Mock TaskExecutor taskExecutor;
    @Mock KnowledgeProcessingService transactionalProxy;
    @Mock com.doctrail.config.KnowledgeProcessingProperties processingProperties;
    @InjectMocks StageTwoKnowledgeProcessingServiceImpl service;

    @Test
    void completedParseMovesDocumentFromProcessingToCompleted() {
        KnowledgeDocEntity document = pendingDocument();
        List<String> statuses = new ArrayList<>();
        when(knowledgeDocMapper.selectById(46L)).thenReturn(document);
        org.mockito.Mockito.doAnswer(invocation -> {
            statuses.add(invocation.<KnowledgeDocEntity>getArgument(0).getStatus());
            return 1;
        }).when(knowledgeDocMapper).updateById(any(KnowledgeDocEntity.class));
        when(aiParseClient.parse(any())).thenAnswer(invocation -> {
            var request = invocation.<com.doctrail.dto.ai.parse.AiParseRequest>getArgument(0);
            return new AiParseResponse(46L, "COMPLETED", UUID.randomUUID(), 3,
                    UUID.randomUUID(), request.traceId(), null);
        });

        service.processNow(46L);

        verify(knowledgeDocMapper, org.mockito.Mockito.times(2)).updateById(any(KnowledgeDocEntity.class));
        assertThat(statuses).containsExactly("PROCESSING", "COMPLETED");
        assertThat(document.getErrorMessage()).isNull();
    }

    @Test
    void clientFailureMovesDocumentToFailedWithSafeMessage() {
        KnowledgeDocEntity document = pendingDocument();
        List<KnowledgeDocEntity> snapshots = new ArrayList<>();
        when(knowledgeDocMapper.selectById(46L)).thenReturn(document);
        org.mockito.Mockito.doAnswer(invocation -> {
            KnowledgeDocEntity source = invocation.getArgument(0);
            KnowledgeDocEntity snapshot = pendingDocument();
            snapshot.setStatus(source.getStatus());
            snapshot.setErrorMessage(source.getErrorMessage());
            snapshots.add(snapshot);
            return 1;
        }).when(knowledgeDocMapper).updateById(any(KnowledgeDocEntity.class));
        when(aiParseClient.parse(any())).thenThrow(new RuntimeException("python unavailable"));

        service.processNow(46L);

        verify(knowledgeDocMapper, org.mockito.Mockito.times(2)).updateById(any(KnowledgeDocEntity.class));
        assertThat(snapshots.get(1).getStatus()).isEqualTo("FAILED");
        assertThat(snapshots.get(1).getErrorMessage()).isEqualTo("python unavailable");
    }

    @Test
    void enqueueDelegatesToConfiguredExecutor() {
        service.enqueue(46L);

        verify(taskExecutor).execute(any(Runnable.class));
    }

    @Test
    void explicitRetryForceFlagReachesPythonClient() {
        KnowledgeDocEntity document = pendingDocument();
        document.setStatus("PENDING");
        when(knowledgeDocMapper.selectById(46L)).thenReturn(document);
        when(knowledgeDocMapper.updateById(any(KnowledgeDocEntity.class))).thenReturn(1);
        when(aiParseClient.parse(any())).thenAnswer(invocation -> {
            var request = invocation.<com.doctrail.dto.ai.parse.AiParseRequest>getArgument(0);
            assertThat(request.force()).isTrue();
            return new AiParseResponse(46L, "COMPLETED", UUID.randomUUID(), 3,
                    UUID.randomUUID(), request.traceId(), null);
        });

        service.processNow(46L, true);

        verify(aiParseClient).parse(any());
    }

    @Test
    void recoveryScanMarksExpiredProcessingAndDeletingAsFailed() {
        KnowledgeDocEntity processing = pendingDocument();
        processing.setStatus("PROCESSING");
        KnowledgeDocEntity deleting = pendingDocument();
        deleting.setId(47L);
        deleting.setStatus("DELETING");
        when(processingProperties.processingTimeout()).thenReturn(Duration.ofMinutes(5));
        when(processingProperties.deletingTimeout()).thenReturn(Duration.ofMinutes(5));
        when(knowledgeDocMapper.selectList(any())).thenReturn(List.of(processing), List.of(deleting));
        when(knowledgeDocMapper.updateById(any(KnowledgeDocEntity.class))).thenReturn(1);

        service.recoverStuckTasks();

        verify(knowledgeDocMapper, org.mockito.Mockito.times(2)).selectList(any());
        verify(knowledgeDocMapper, org.mockito.Mockito.times(2)).updateById(any(KnowledgeDocEntity.class));
        assertThat(processing.getStatus()).isEqualTo("FAILED");
        assertThat(deleting.getStatus()).isEqualTo("FAILED");
    }

    private KnowledgeDocEntity pendingDocument() {
        KnowledgeDocEntity document = new KnowledgeDocEntity();
        document.setId(46L);
        document.setStorageKey("2026/09/document.pdf");
        document.setContentHash("a".repeat(64));
        document.setStatus("PENDING");
        return document;
    }
}
