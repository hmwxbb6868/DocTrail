package com.doctrail.storage;

import org.apache.tika.Tika;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/*
***文件上传的安全校验器**，
* 一次性做：**文件大小校验 + 文件后缀白名单 + Tika 真实文件内容嗅探校验 + 生成 SHA256 哈希 + 生成存储 key**，
* 最后返回上传元数据，**防止超大文件、禁止后缀、伪装文件（改后缀骗人）上传** */
public final class UploadValidator {
//    负责存放规则
    private static final Map<String, Set<String>> ALLOWED_TYPES = Map.of(
        // 规则：后缀是.pdf时，Tika检测出来的真实MIME必须是 application/pdf
            ".pdf", Set.of("application/pdf"),
        // 后缀是.docx（Office2007及之后新版Word）
        // application/vnd.openxmlformats-officedocument.wordprocessingml.document 是docx标准MIME标识
            ".docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        // 后缀是.txt纯文本文件，真实MIME必须是 text/plain
            ".txt", Set.of("text/plain"),
            ".md", Set.of("text/plain", "text/markdown", "text/x-markdown", "text/x-web-markdown")
    );

    private final long maxBytes;
    private final Tika tika;

    public UploadValidator(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
//        **Tika 可以解析文件，自动识别文件真实类型（MIME），还能提取文本、元信息**，你现在这段代码只用了它的**文件类型探测能力**
        this.tika = new Tika();
    }
//    文件上传校验核心方法
//    originalFilename 前端传过来的原始文件名（如 报告.pdf）
//    clientContentType：前端浏览器告诉服务端的MIME类型（不可信，这里代码里没用到！）
    public UploadMetadata validate(String originalFilename, String clientContentType, byte[] bytes) {
        if (bytes == null) {
            throw new UnsupportedUploadTypeException("file bytes are required");
        }
        if (bytes.length > maxBytes) {
            throw new UploadTooLargeException("uploaded file exceeds the configured size limit");
        }
//        处理原始文件名：如果前端传null，就赋值为空字符串；否则去掉首尾空格
        String filename = originalFilename == null ? "" : originalFilename.trim();
//        调用工具方法extensionOf，提取文件后缀（.pdf / .md）
        String extension = extensionOf(filename);
//        去ALLOWED_TYPES规则表，根据后缀拿到该后缀允许的MIME集合
        Set<String> allowedTypes = ALLOWED_TYPES.get(extension);
        if (allowedTypes == null) {
            throw new UnsupportedUploadTypeException("file extension is not allowed");
        }
//        Apache Tika读取文件二进制，探测【真实文件MIME类型】，全部转小写
        String detected = tika.detect(bytes, filename).toLowerCase(Locale.ROOT);
        if (!allowedTypes.contains(detected)) {
            throw new UnsupportedUploadTypeException("server-side file type detection rejected the upload");
        }
//        根据后缀和真实MIME，生成规范的MIME
        String normalizedType = normalizeType(extension, detected);
//        文件存储路径（存储 key）
        String storageKey = "pending/" + sha256(bytes) + extension;
        return new UploadMetadata(filename, storageKey, normalizedType, bytes.length, sha256(bytes));
    }

    //提取文件后缀
    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    //根据后缀和真实MIME，生成规范的MIME
//    ：**对 MIME 类型做标准化兜底**，优先根据文件后缀 `.md`、`.txt` 强制指定 MIME；
//    其他文件直接使用 Tika 识别出来的 `detected`
    private static String normalizeType(String extension, String detected) {
        if (extension.equals(".md")) {
            return "text/markdown";
        }
        if (extension.equals(".txt")) {
            return "text/plain";
        }
        return detected;
    }

    public static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
