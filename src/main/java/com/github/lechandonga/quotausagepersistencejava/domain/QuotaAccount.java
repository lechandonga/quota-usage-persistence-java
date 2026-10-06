package com.github.lechandonga.quotausagepersistencejava.domain;

import java.time.Instant;

/**
 * 配额账户。balance 永远非负（由数据库 CHECK 约束与条件更新双重保证）。
 * version 为乐观锁版本号，每次扣减/状态变更递增，用于区分并发冲突。
 */
public record QuotaAccount(
        Long id,
        String accountNo,
        long balance,
        AccountStatus status,
        String quotaType,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
