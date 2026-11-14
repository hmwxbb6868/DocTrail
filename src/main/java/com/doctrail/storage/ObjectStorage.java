package com.doctrail.storage;

public interface ObjectStorage extends AutoCloseable {
    // 上传文件
    void put(String storageKey, byte[] bytes, String contentType);

    // 删除文件
    void delete(String storageKey);

    // 判断文件是否存在
    boolean exists(String storageKey);

    // 关闭资源
    @Override
    default void close() {
    }
}
