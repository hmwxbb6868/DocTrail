package com.doctrail.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Local development substitute; the persisted key still includes the configured prefix. */
//本地磁盘存储实现类
public final class LocalObjectStorage implements ObjectStorage {
    private final Path root;
    // prefix：key前缀，一般是 pending/
    private final String prefix;

//    构造器：接收根目录和前缀，转成标准化绝对路径
    public LocalObjectStorage(Path root, String prefix) {
        this.root = root.toAbsolutePath().normalize();
        this.prefix = prefix;
        try {
//            创建目录，如果目录已存在则不会报错
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            throw new IllegalStateException("local object storage root is unavailable", exception);
        }
    }

//上传文件
    @Override
//    `storageKey`：文件的唯一标识 key
    public void put(String storageKey, byte[] bytes, String contentType) {
        Path target = resolve(storageKey);
        try {
            //`target.getParent()`：拿到**文件所在的父目录**
            //`Files.createDirectories()`：创建目录，如果目录已存在则不会报错
            Files.createDirectories(target.getParent());
            //`Files.write()`：写入文件
            Files.write(target, bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("local object storage write failed", exception);
        }
    }

//删除文件
    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException exception) {
            throw new IllegalStateException("local object storage delete failed", exception);
        }
    }
//判断文件是否存在

    @Override
//    **直接调用操作系统（Windows/Linux）的底层 API，去硬盘这个位置查询：这个地址有没有文件 / 文件夹**。
    public boolean exists(String storageKey) {
        return Files.exists(resolve(storageKey));
    }

//解析存储键
    private Path resolve(String storageKey) {
        // 拼接前缀和原始storageKey
        String objectKey = StorageKeyNormalizer.joinPrefix(prefix, storageKey);
//         `resolve()` 的作用：**把后面的字符串 `objectKey` 拼接到 root 路径后面**。
//        `ormalize()`：**标准化 / 化简路径，消除 `../` 和 `./` 这种相对跳转**
        Path target = root.resolve(objectKey).normalize();
        // 检查路径是否在根目录下
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("storage_key escapes UPLOAD_ROOT");
        }
        assertRealPathWithinRoot(target);
        return target;
    }

    /**
     * 这是文件上传服务的**安全校验函数**，专门解决软链接导致路径逃逸。
     * 新建文件时向上找到最近存在父目录校验真实路径；文件已存在时校验文件本身真实路径；
     * 保证所有读写删操作的真实文件位置，永远不能跑出 UPLOAD_ROOT。
     */
    private void assertRealPathWithinRoot(Path target) {
        try {
            // 1. 获取上传根目录的真实物理路径
            Path realRoot = root.toRealPath();
            // probe：探测指针，初始指向待操作的目标路径 target
            Path probe = target;
            while (!Files.exists(probe, LinkOption.NOFOLLOW_LINKS)
                    && probe.getParent() != null
                    && !probe.equals(root)) {
                probe = probe.getParent();
            }
            if (!probe.toRealPath().startsWith(realRoot)) {
                throw new IllegalArgumentException("storage_key escapes UPLOAD_ROOT");
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !target.toRealPath().startsWith(realRoot)) {
                throw new IllegalArgumentException("storage_key escapes UPLOAD_ROOT");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("local object storage path cannot be verified", exception);
        }
    }
}
