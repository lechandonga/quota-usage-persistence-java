package com.github.lechandonga.quotausagepersistencejava.domain;

import java.time.Instant;

/**
 * 用量记录，与账户余额在同一事务内落库。
 * occurredAt 由应用层生成（微秒精度），与自增 id 共同构成游标翻页的稳定键集。
 */
public record UsageRecord(
        Long id,
        Long accountId,
        String accountNo,
        long amount,
        Instant occurredAt,
        String note,
        Instant createdAt
) {
}
