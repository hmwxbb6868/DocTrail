package com.doctrail.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/** Configuration for the stage-4 file-processing profile. */
@ConfigurationProperties(prefix = "doctrail.storage")
public record StorageProperties(
        String backend,// 存储后端：local / oss
        Path uploadRoot,// 本地上传根目录路径
        long maxFileSizeMb,// 上传文件最大大小（MB）
        String ossBucket,//OSS bucket名称
        String ossEndpoint,// OSS接入地址
        String ossRegion,// OSS地域
        String ossAccessKeyId,// OSS访问密钥ID
        String ossAccessKeySecret,// OSS访问密钥密钥
        String ossKeyPrefix,// OSS键前缀
        Duration ossConnectTimeout,// OSS连接超时时间
        Duration ossReadTimeout,// OSS读取超时时间
        int ossMaxRetries// OSS最大重试次数
) {
//    配置校验逻辑
    public void validate() {
        if (!"local".equals(backend) && !"oss".equals(backend)) {
            throw new IllegalStateException("STORAGE_BACKEND must be explicitly set to local or oss");
        }
        if (uploadRoot == null) {
            throw new IllegalStateException("UPLOAD_ROOT must be configured");
        }
        if (maxFileSizeMb < 1 || maxFileSizeMb > 50) {
            throw new IllegalStateException("UPLOAD_MAX_FILE_SIZE_MB must be between 1 and 50");
        }
        if (ossKeyPrefix == null || ossKeyPrefix.isBlank()) {
            throw new IllegalStateException("OSS_KEY_PREFIX must not be blank");
        }
        // ⑤ 如果后端选 oss，则下面这几个OSS必填参数不能空
        if ("oss".equals(backend)) {
            requireNonBlank(ossBucket, "OSS_BUCKET");
            requireNonBlank(ossEndpoint, "OSS_ENDPOINT");
            requireNonBlank(ossAccessKeyId, "OSS_ACCESS_KEY_ID");
            requireNonBlank(ossAccessKeySecret, "OSS_ACCESS_KEY_SECRET");
        }
        if (ossConnectTimeout == null || ossReadTimeout == null || ossMaxRetries < 0) {
            throw new IllegalStateException("OSS timeout and retry settings are invalid");
        }
    }

    // 辅助工具方法：判断字符串非空，为空抛出异常
    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be configured when STORAGE_BACKEND=oss");
        }
    }
}
