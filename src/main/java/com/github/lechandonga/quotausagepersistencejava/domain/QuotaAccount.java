package com.github.lechandonga.quotausagepersistencejava.domain;

/**
 * 配额账户。配额、余额均为非负 long 整数（最小业务单位）。
 */
public record QuotaAccount(
        Long id,
        String accountName,
        AccountStatus status,
        long totalQuota,
        long remaining,
        boolean rolloverEnabled,
        long version,
        long createdAt,
        long updatedAt
) {
}
