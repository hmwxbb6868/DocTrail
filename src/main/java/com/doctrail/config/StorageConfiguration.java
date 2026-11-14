package com.doctrail.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.doctrail.storage.LocalObjectStorage;
import com.doctrail.storage.ObjectStorage;
import com.doctrail.storage.OssObjectStorage;
import com.doctrail.storage.UploadValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/*
* 集中创建上传校验 Bean 和 文件存储 Bean；
* 根据 yml 配置自动选择本地磁盘 / 阿里云 OSS 存储
* */
@Configuration(proxyBeanMethods = false)
@Profile("stage2-auth")
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {
//    从配置文件读取存储配置，校验配置合法性，把 MB 转成字节，创建 UploadValidator 对象，
//    交给 Spring 容器管理，全局复用这一个校验器实例。
    @Bean
    UploadValidator uploadValidator(StorageProperties properties) {
        //调用前面`StorageProperties`的校验方法，检查存储配置是否合法
        properties.validate();
//        `Math.multiplyExact(a,b)`：安全乘法，把 MB 换算成字节，变成long maxBytes类型
        return new UploadValidator(Math.multiplyExact(properties.maxFileSizeMb(), 1024L * 1024L));
    }

//    Spring 容器销毁的时候，自动调用这个 Bean 的 `close()` 方法，释放连接资源。
    @Bean(destroyMethod = "close")
    ObjectStorage objectStorage(StorageProperties properties) {
        //启动阶段校验 `StorageProperties` 的配置合法性
        properties.validate();
//        判断：配置的存储后端是不是 `local`（本地磁盘模式）
        if ("local".equals(properties.backend())) {
//            **`properties.uploadRoot()`：本地存储**根目录**，从 yml 配置读取
//            **`properties.ossKeyPrefix()`：文件 key 前缀，示例值：`pending/`
            return new LocalObjectStorage(properties.uploadRoot(), properties.ossKeyPrefix()); // 本地磁盘模式
        }
//     用来存放 OSS 客户端的网络、重试相关配置
        ClientBuilderConfiguration clientConfiguration = new ClientBuilderConfiguration();
//        设置连接超时时间为 properties.ossConnectTimeout 的毫秒值
        clientConfiguration.setConnectionTimeout(Math.toIntExact(properties.ossConnectTimeout().toMillis()));
//        设置读取超时时间为 properties.ossReadTimeout 的毫秒值
        clientConfiguration.setSocketTimeout(Math.toIntExact(properties.ossReadTimeout().toMillis()));
//        设置最大重试次数为 properties.ossMaxRetries
        clientConfiguration.setMaxErrorRetry(properties.ossMaxRetries());
        // 创建阿里云原生OSS客户端
        OSS client = new OSSClientBuilder()
                .build(properties.ossEndpoint(), properties.ossAccessKeyId(), properties.ossAccessKeySecret(), clientConfiguration);
        // 包装成项目自己的存储实现对象
        return new OssObjectStorage(client, properties.ossBucket(), properties.ossKeyPrefix());
    }
}
