package com.github.lechandonga.quotausagepersistencejava.service;

/**
 * 业务错误码。所有可预期的失败都通过明确的错误码区分，便于调用方解释与重试决策。
 */
public enum QuotaErrorCode {
    INVALID_ARGUMENT,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_NAME_DUPLICATED,
    INSUFFICIENT_QUOTA,
    ACCOUNT_DISABLED,
    CONCURRENT_CONFLICT,
    INVALID_CURSOR,
    CURSOR_TARGET_INVISIBLE
}
