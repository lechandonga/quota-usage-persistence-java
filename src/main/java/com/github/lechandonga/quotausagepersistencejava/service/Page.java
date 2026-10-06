package com.github.lechandonga.quotausagepersistencejava.service;

import com.github.lechandonga.quotausagepersistencejava.domain.UsageRecord;

import java.util.List;

/**
 * 用量分页结果。nextCursor 为 null 表示已到末页。
 */
public record Page(List<UsageRecord> records, String nextCursor, boolean hasMore) {
}
