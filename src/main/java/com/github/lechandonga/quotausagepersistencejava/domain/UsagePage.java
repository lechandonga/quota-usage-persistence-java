package com.github.lechandonga.quotausagepersistencejava.domain;

import java.util.List;

/**
 * 游标分页结果。nextCursor 为 null 表示已到末页。
 */
public record UsagePage(
        List<UsageRecord> records,
        String nextCursor,
        boolean hasMore
) {
}
