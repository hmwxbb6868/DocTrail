package com.doctrail.storage;

import com.aliyun.oss.OSS;

import java.io.ByteArrayInputStream;

/** OSS adapter. Credentials are supplied only through stage-4 environment configuration. */
public final class OssObjectStorage implements ObjectStorage {
    private final OSS client;
    private final String bucket;
    private final String prefix;

    public OssObjectStorage(OSS client, String bucket, String prefix) {
        //OSS 客户端对象，用来调用阿里云 OSS 的 API**，上传、删除、判断文件是否存在，所有和阿里云交互都靠这个 client
        this.client = client;
        this.bucket = bucket;
        this.prefix = prefix;
    }

    @Override
    public void put(String storageKey, byte[] bytes, String contentType) {
        //`ObjectMetadata`：**OSS 对象的元数据**，就是这个文件的附加信息。
        // 可以存：文件大小、MIME 类型 (contentType)、缓存头、编码等信息，保存到阿里云 OSS 上。
        var metadata = new com.aliyun.oss.model.ObjectMetadata();
        metadata.setContentLength(bytes.length);
        metadata.setContentType(contentType);
//        **调用阿里云 OSS SDK 提供的上传方法**，把文件上传到阿里云 OSS
//        字节数组 `bytes` 包装成一个输入流（InputStream）
        client.putObject(bucket, StorageKeyNormalizer.joinPrefix(prefix, storageKey), new ByteArrayInputStream(bytes), metadata);
    }

    @Override
    public void delete(String storageKey) {
        client.deleteObject(bucket, StorageKeyNormalizer.joinPrefix(prefix, storageKey));
    }

    //判断在阿里云 OSS 里面，对应的文件是否存在
    @Override
    public boolean exists(String storageKey) {
        return client.doesObjectExist(bucket, StorageKeyNormalizer.joinPrefix(prefix, storageKey));
    }

    @Override
    public void close() {
        client.shutdown();
    }
}
