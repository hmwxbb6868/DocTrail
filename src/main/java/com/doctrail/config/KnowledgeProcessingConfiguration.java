package com.doctrail.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/*
* 在`stage2-auth`环境加载，读取并校验知识库解析线程池配置，创建专用异步线程池 Bean 并注册到 Spring，
* 配置优雅停机处理文档解析任务。*/
@Configuration(proxyBeanMethods = false)
@Profile("stage2-auth")
@EnableScheduling //开启 Spring 的定时任务能力，让 `@Scheduled` 注解生效
@EnableConfigurationProperties(KnowledgeProcessingProperties.class)
public class KnowledgeProcessingConfiguration {
    @Bean(name = "knowledgeProcessingExecutor")
//    TaskExecutor 是 Spring 的异步任务接口，用来提交后台任务；
//    **TaskExecutor**：只定义能力：`execute(Runnable task)`，规定【可以提交异步任务】。它是抽象的，**不能直接 new**。
    TaskExecutor knowledgeProcessingExecutor(KnowledgeProcessingProperties properties) {
        //启动阶段校验线程池参数合法性
        properties.validate();
//        创建 Spring 封装的线程池实例，专门用于执行异步任务（文档解析）
//        **ThreadPoolTaskExecutor**：是具体实现，里面包含线程、队列等真实线程池逻辑，可以`new`出来
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.corePoolSize());//核心线程数
        executor.setMaxPoolSize(properties.maxPoolSize());//最大线程数
        executor.setQueueCapacity(properties.queueCapacity());//队列容量
        executor.setThreadNamePrefix("knowledge-processing-");//线程名称前缀
        executor.setWaitForTasksToCompleteOnShutdown(true);//等待任务完成后再关闭
        executor.setAwaitTerminationSeconds(30);//等待时间
        executor.initialize();
        return executor;
    }
}
