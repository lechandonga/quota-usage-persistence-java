package com.github.lechandonga.quotausagepersistencejava.service;

/**
 * 扣减业务失败。不同 {@link DeductionFailureKind} 对应不同的 HTTP 语义与调用方处理策略。
 */
public class DeductionException extends RuntimeException {

    private final DeductionFailureKind kind;

    public DeductionException(DeductionFailureKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public DeductionFailureKind getKind() {
        return kind;
    }
}
