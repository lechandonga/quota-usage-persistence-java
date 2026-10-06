package com.github.lechandonga.quotausagepersistencejava.domain;

/**
 * 用量记账记录。occurredAt 为事件时间（epoch millis），作为游标排序键的一部分，一经写入不可变。
 */
public record UsageRecord(
        Long id,
        Long accountId,
        long amount,
        long occurredAt,
        String bizRef,
        String note
) {
}
