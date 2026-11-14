package com.doctrail.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/*
* 上传文档后，状态置为 `PENDING`，**会提交异步任务交给这个线程池去解析文档**（文本提取、向量化入库）。
这个类就是用来读取解析任务线程池的配置参数，并且校验参数合法性
*/
@ConfigurationProperties(prefix = "doctrail.knowledge.processing")
public record KnowledgeProcessingProperties(
//        优先使用核心线程；核心线程全部繁忙，任务进入队列排队
//          队列满了之后，才扩容线程到最大线程数；线程数顶满 + 队列满，拒绝新任务**
        int corePoolSize,//核心线程数
        int maxPoolSize,//最大线程数
        int queueCapacity,//队列容量
        java.time.Duration processingTimeout,//文档解析超时时间
        java.time.Duration deletingTimeout,//文档删除超时时间
        java.time.Duration recoveryScanInterval//卡死任务恢复扫描间隔
) {
    public void validate() {
        if (corePoolSize < 1 || maxPoolSize < corePoolSize || queueCapacity < 1
                || processingTimeout == null || processingTimeout.isNegative() || processingTimeout.isZero()
                || deletingTimeout == null || deletingTimeout.isNegative() || deletingTimeout.isZero()
                || recoveryScanInterval == null || recoveryScanInterval.isNegative() || recoveryScanInterval.isZero()) {
            throw new IllegalStateException("knowledge processing executor settings are invalid");
        }
    }
}
