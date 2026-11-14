package com.doctrail.storage;

//**上传文件过大异常**。
public class UploadTooLargeException extends RuntimeException {
    public UploadTooLargeException(String message) {
        super(message);
    }
}
