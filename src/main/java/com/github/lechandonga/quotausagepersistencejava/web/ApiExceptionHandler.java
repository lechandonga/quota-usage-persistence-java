package com.github.lechandonga.quotausagepersistencejava.web;

import com.github.lechandonga.quotausagepersistencejava.service.CursorException;
import com.github.lechandonga.quotausagepersistencejava.service.DeductionException;
import com.github.lechandonga.quotausagepersistencejava.service.DeductionFailureKind;
import com.github.lechandonga.quotausagepersistencejava.service.QuotaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DeductionException.class)
    public ResponseEntity<Map<String, String>> handleDeduction(DeductionException e) {
        HttpStatus status = switch (e.getKind()) {
            case ACCOUNT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INSUFFICIENT_QUOTA, CONCURRENT_CONFLICT -> HttpStatus.CONFLICT;
            case ACCOUNT_SUSPENDED -> HttpStatus.LOCKED;
        };
        return ResponseEntity.status(status).body(Map.of(
                "errorCode", e.getKind().name(),
                "message", e.getMessage()
        ));
    }

    @ExceptionHandler(CursorException.class)
    public ResponseEntity<Map<String, String>> handleCursor(CursorException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "errorCode", "INVALID_CURSOR",
                "message", e.getMessage()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleInvalidArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "errorCode", "INVALID_REQUEST",
                "message", e.getMessage()
        ));
    }

    @ExceptionHandler(QuotaService.AccountAlreadyExistsException.class)
    public ResponseEntity<Map<String, String>> handleDuplicate(
            QuotaService.AccountAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "errorCode", "ACCOUNT_ALREADY_EXISTS",
                "message", e.getMessage()
        ));
    }
}
