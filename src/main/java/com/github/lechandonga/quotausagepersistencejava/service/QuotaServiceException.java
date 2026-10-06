package com.github.lechandonga.quotausagepersistencejava.service;

/**
 * 携带 {@link QuotaErrorCode} 的业务异常。
 */
public class QuotaServiceException extends RuntimeException {

    private final QuotaErrorCode errorCode;

    public QuotaServiceException(QuotaErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public QuotaErrorCode getErrorCode() {
        return errorCode;
    }
}
