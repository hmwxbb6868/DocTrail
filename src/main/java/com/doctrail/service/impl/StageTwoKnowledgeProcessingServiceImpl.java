package com.doctrail.service.impl;

import com.doctrail.dto.ai.parse.AiParseRequest;
import com.doctrail.dto.ai.parse.AiParseResponse;
import com.doctrail.entity.KnowledgeDocEntity;
import com.doctrail.mapper.KnowledgeDocMapper;
import com.doctrail.service.admin.KnowledgeProcessingService;
import com.doctrail.service.ai.client.AiParseClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import com.doctrail.config.KnowledgeProcessingProperties;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

@Service
@Profile("stage2-auth")
public class StageTwoKnowledgeProcessingServiceImpl implements KnowledgeProcessingService {
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final AiParseClient aiParseClient;
    private final TaskExecutor taskExecutor;
    private final KnowledgeProcessingService transactionalProxy;
    private final KnowledgeProcessingProperties processingProperties;

    public StageTwoKnowledgeProcessingServiceImpl(
            KnowledgeDocMapper knowledgeDocMapper,
            AiParseClient aiParseClient,
            //`@Qualifier("knowledgeProcessingExecutor")`：**按 Bean 名称精确挑选 Bean**
            @Qualifier("knowledgeProcessingExecutor") TaskExecutor taskExecutor,
            //`@Lazy`：延迟加载
            //`@Transactional`：告诉 Spring，给这整个Service 创建代理（**生成代理的触发点**）
            //1. `@Lazy KnowledgeProcessingService transactionalProxy`：从 Spring 容器把这个代理取出来注入进来（**拿到代理**）
            //2. `transactionalProxy.processNow()`：**使用代理**
//          //为什么拿代理：代理里面藏了 AOP 切面代码，专门处理事务；不经过代理，事务代码根本不会跑。
//          AOP 看到这个方法有`@Transactional`👉 开启数据库事务
            @Lazy KnowledgeProcessingService transactionalProxy,
            KnowledgeProcessingProperties processingProperties
    ) {
        this.knowledgeDocMapper = knowledgeDocMapper;
        this.aiParseClient = aiParseClient;
        this.taskExecutor = taskExecutor;
        this.transactionalProxy = transactionalProxy;
        this.processingProperties = processingProperties;
    }

    @Override
//    **`() -> transactionalProxy.processNow(docId)`：这是 Lambda 表达式，用来表示一个`Runnable`任务
//    调用`execute` → 把 lambda 包装成 Runnable 任务丢进线程池 → 线程池里的空闲线程去执行`processNow(docId)`
    public void enqueue(long docId) {
        enqueue(docId, false);
    }

    @Override
    public void enqueue(long docId, boolean force) {
        taskExecutor.execute(() -> transactionalProxy.processNow(docId, force));
    }

    @Override
    public void processNow(long docId) {
        processNow(docId, false);
    }

    @Override
    public void processNow(long docId, boolean force) {
        // 根据文档ID查询数据库里的文档记录
        KnowledgeDocEntity document = knowledgeDocMapper.selectById(docId);
        // 如果文档不存在，或者当前状态不允许解析（比如已经成功COMPLETED），直接退出，不处理
        if (document == null || !isProcessable(document.getStatus())) {
            return;
        }
//       判断是否强制重试：如果文档上一次解析失败（FAILED），本次标记为强制重试
        boolean effectiveForce = force || "FAILED".equals(document.getStatus());
        // 更新状态为PROCESSING【正在解析】，防止别的任务重复解析同一个文档
        updateStatus(document, "PROCESSING", null);
        try {
            UUID traceId = UUID.randomUUID();
            // 调用AI解析客户端：读取OSS文件、提取文本、向量化
            AiParseResponse response = aiParseClient.parse(new AiParseRequest(
                    document.getId(), document.getStorageKey(), document.getContentHash(), effectiveForce, traceId));
            String error = response.errorMessage();
            // 根据AI返回结果更新状态：成功→COMPLETED；失败→FAILED，并保存错误信息
            updateStatus(document, "COMPLETED".equals(response.status()) ? "COMPLETED" : "FAILED", error);
        } catch (RuntimeException exception) {
            // 捕获所有运行时异常，标记文档为FAILED，记录异常信息
            updateStatus(document, "FAILED", safeError(exception));
        }
    }

    /*
    * 定时巡检任务：：每隔一段时间，去数据库查有没有「卡死」的文档记录（状态卡在`PROCESSING`或者`DELETING`长时间没更新），
    * 找到之后强制改成`FAILED`，打上错误原因，防止任务永远卡死在中间状态。*/
    //fixedDelayString：上一轮任务执行完成之后，再等待指定时间，才跑下一轮。从配置文件读取`:60s` 代表默认值
    @Scheduled(fixedDelayString = "${doctrail.knowledge.processing.recovery-scan-interval:60s}")
    public void recoverStuckTasks() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        markStuck("PROCESSING", now.minus(processingProperties.processingTimeout()),
                "processing task exceeded configured timeout");
        markStuck("DELETING", now.minus(processingProperties.deletingTimeout()),
                "deleting task exceeded configured timeout");
    }

    private void markStuck(String status, OffsetDateTime cutoff, String reason) {
        //cutoff：临界时间点
        // 1. 查询数据库，找出卡死的文档记录
        List<KnowledgeDocEntity> stuck = knowledgeDocMapper.selectList(
                Wrappers.<KnowledgeDocEntity>lambdaQuery()
                        .eq(KnowledgeDocEntity::getStatus, status)
                        .lt(KnowledgeDocEntity::getUpdateTime, cutoff));
        for (KnowledgeDocEntity document : stuck) {
            document.setStatus("FAILED");
            document.setErrorMessage(reason);
            updateStatus(document, "FAILED", reason);
        }
    }

    private boolean isProcessable(String status) {
        return "PENDING".equals(status) || "FAILED".equals(status);
    }

//
    private void updateStatus(KnowledgeDocEntity document, String status, String errorMessage) {
        document.setStatus(status);
        document.setErrorMessage(errorMessage);
        document.setUpdateTime(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        knowledgeDocMapper.updateById(document);
    }

    private String safeError(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
//        // 如果消息长度≤500直接返回；超过500，截断只保留前500字符，防止数据库字段超长报错
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
