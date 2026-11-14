package com.doctrail.storage;

import java.util.Arrays;
import java.util.regex.Pattern;

/*
*专门用来校验 + 规范化 storageKey，保证传给本地存储 / OSS 的存储键是安全、统一格式的相对路径
* 统一 LocalObjectStorage 和 OssObjectStorage 两套存储的 key 规则。
* */
public final class StorageKeyNormalizer {
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:/.*");

    private StorageKeyNormalizer() {
    }

//    **校验并规范化业务传入的 `storageKey`，强制它必须是【相对存储键】，
//    禁止各种危险路径写法，清理多余斜杠，返回干净安全的 storageKey**。
    public static String normalizeRelative(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("storage_key must not be blank");
        }
        //去掉首尾空白（空格、换行）
        String trimmed = value.trim();
//        拦截绝对路径、反斜杠
        if (trimmed.startsWith("/") || trimmed.contains("\\") || WINDOWS_ABSOLUTE.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("storage_key must be a relative key");
        }
//        把连续多个斜杠替换成单个斜杠
        String collapsed = trimmed.replaceAll("/{2,}", "/");
//        按照斜杠切割成一段一段
        String[] segments = collapsed.split("/", -1);
//        遍历每一段，禁止空片段
        if (segments.length == 0 || Arrays.stream(segments).anyMatch(segment -> segment.isEmpty() || segment.equals(".") || segment.equals(".."))) {
            throw new IllegalArgumentException("storage_key contains an invalid path segment");
        }
//        重新拼接成干净字符串返回
        return String.join("/", segments);
    }

    public static String joinPrefix(String prefix, String storageKey) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("OSS_KEY_PREFIX must not be blank");
        }
        //清理prefix：去首尾空格，合并连续斜杠，去掉开头和末尾的斜杠
        String normalizedPrefix = prefix.trim().replaceAll("/{2,}", "/").replaceAll("^/+|/+$", "");
//        校验清理后的prefix，不能是空，不能是Windows绝对路径（C:/xxx）
        if (normalizedPrefix.isEmpty() || WINDOWS_ABSOLUTE.matcher(normalizedPrefix).matches()) {
            throw new IllegalArgumentException("OSS_KEY_PREFIX must be a relative prefix");
        }
//        把prefix按斜杠切分成多段，遍历每一段做合法性校验
        for (String segment : normalizedPrefix.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.contains("\\")) {
                throw new IllegalArgumentException("OSS_KEY_PREFIX contains an invalid path segment");
            }
        }
        //拼接：清理后的prefix + / + 经过normalizeRelative校验后的storageKey
        return normalizedPrefix + "/" + normalizeRelative(storageKey);
    }
}
