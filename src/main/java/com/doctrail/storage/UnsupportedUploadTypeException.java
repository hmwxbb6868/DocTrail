package com.doctrail.storage;

/*
 * 不支持的上传文件类型异常*/
public class UnsupportedUploadTypeException extends RuntimeException {
    public UnsupportedUploadTypeException(String message) {
        super(message);
    }
}
