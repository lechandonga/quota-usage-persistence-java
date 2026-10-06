package com.github.lechandonga.quotausagepersistencejava.web;

import com.github.lechandonga.quotausagepersistencejava.service.QuotaErrorCode;
import com.github.lechandonga.quotausagepersistencejava.service.QuotaServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 业务错误统一映射：每种错误码对应确定的 HTTP 状态，响应体带 code/message，结论可解释。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(QuotaServiceException.class)
    public ResponseEntity<Map<String, String>> handle(QuotaServiceException ex) {
        return ResponseEntity.status(statusOf(ex.getErrorCode()))
                .body(Map.of("code", ex.getErrorCode().name(), "message", ex.getMessage()));
    }

    private HttpStatus statusOf(QuotaErrorCode code) {
        return switch (code) {
            case INVALID_ARGUMENT, INVALID_CURSOR -> HttpStatus.BAD_REQUEST;
            case ACCOUNT_NOT_FOUND, CURSOR_TARGET_INVISIBLE -> HttpStatus.NOT_FOUND;
            case ACCOUNT_NAME_DUPLICATED, ACCOUNT_DISABLED -> HttpStatus.CONFLICT;
            case INSUFFICIENT_QUOTA -> HttpStatus.UNPROCESSABLE_ENTITY;
            case CONCURRENT_CONFLICT -> HttpStatus.CONFLICT;
        };
    }
}
