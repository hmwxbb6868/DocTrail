package com.doctrail.storage;

public record UploadMetadata(
        String originalFilename,// 用户上传时的原始文件名，例如：笔记.pdf
        String storageKey,// 文件存到存储服务（本地/OSS）的唯一key/path，pending/xxxsha256.pdf
        String contentType,// 标准化后的MIME文件类型，例如application/pdf
        long size,// 文件大小，单位字节
        String contentHash// 文件内容的哈希值，用于校验文件完整性
) {
}
