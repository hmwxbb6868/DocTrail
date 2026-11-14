package com.doctrail.service.admin;

public interface KnowledgeProcessingService {
    // 将文档解析任务提交到线程池（放到队列异步跑）
    void enqueue(long docId);

//    带 `default`，方法体内可以写实现
// 新增重载，带default兜底实现
    default void enqueue(long docId, boolean force) {
        enqueue(docId);
    }

    // 立刻同步执行解析，不走线程池（一般用于调试/重试）
    void processNow(long docId);

    default void processNow(long docId, boolean force) {
        processNow(docId);
    }
}
