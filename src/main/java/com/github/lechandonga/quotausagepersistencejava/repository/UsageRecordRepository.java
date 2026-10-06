package com.github.lechandonga.quotausagepersistencejava.repository;

import com.github.lechandonga.quotausagepersistencejava.domain.UsageRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 用量记录持久化。查询走 (account_id, occurred_at, id) 复合索引上的 keyset seek，
 * 每页一次 SQL，与历史总量无关。
 */
@Repository
public class UsageRecordRepository {

    private static final String COLUMNS =
            "id, account_id, amount, occurred_at, biz_ref, note";

    private static final RowMapper<UsageRecord> MAPPER = (rs, rowNum) -> new UsageRecord(
            rs.getLong("id"),
            rs.getLong("account_id"),
            rs.getLong("amount"),
            rs.getLong("occurred_at"),
            rs.getString("biz_ref"),
            rs.getString("note")
    );

    private final JdbcTemplate jdbcTemplate;

    public UsageRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(long accountId, long amount, long occurredAt, String bizRef, String note) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO usage_record (account_id, amount, occurred_at, biz_ref, note) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, accountId);
            ps.setLong(2, amount);
            ps.setLong(3, occurredAt);
            ps.setString(4, bizRef);
            ps.setString(5, note == null ? "" : note);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 首页：时间窗 [fromInclusive, toExclusive) 内，按 (occurred_at, id) 升序取 limit+1 行。
     * SQL 中的标记注释用于测试中精确统计本类查询触发的数据库往返次数。
     */
    public List<UsageRecord> findPage(long accountId, long fromInclusive, long toExclusive,
                                      int limitPlusOne) {
        return jdbcTemplate.query(
                "/* quota.page.first */ SELECT " + COLUMNS
                        + " FROM usage_record"
                        + " WHERE account_id = ? AND occurred_at >= ? AND occurred_at < ?"
                        + " ORDER BY occurred_at ASC, id ASC"
                        + " LIMIT ?",
                MAPPER, accountId, fromInclusive, toExclusive, limitPlusOne);
    }

    /**
     * 翻页：从游标 (afterOccurredAt, afterId) 之后做 keyset seek。
     * 新写入的行只要 occurred_at 落在游标之前，排序上永远在游标之前，因此不重不漏。
     */
    public List<UsageRecord> findPageAfter(long accountId, long fromInclusive, long toExclusive,
                                           long afterOccurredAt, long afterId, int limitPlusOne) {
        return jdbcTemplate.query(
                "/* quota.page.after */ SELECT " + COLUMNS
                        + " FROM usage_record"
                        + " WHERE account_id = ? AND occurred_at >= ? AND occurred_at < ?"
                        + "   AND (occurred_at > ? OR (occurred_at = ? AND id > ?))"
                        + " ORDER BY occurred_at ASC, id ASC"
                        + " LIMIT ?",
                MAPPER, accountId, fromInclusive, toExclusive,
                afterOccurredAt, afterOccurredAt, afterId, limitPlusOne);
    }

    /**
     * 游标指向的记录是否仍然可见（属于同一账户且仍在查询时间窗内）。
     */
    public boolean isVisible(long accountId, long id, long occurredAt,
                             long fromInclusive, long toExclusive) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM usage_record "
                        + "WHERE id = ? AND account_id = ? AND occurred_at = ? "
                        + "  AND occurred_at >= ? AND occurred_at < ?",
                Integer.class, id, accountId, occurredAt, fromInclusive, toExclusive);
        return count != null && count == 1;
    }

    public long countByWindow(long accountId, long fromInclusive, long toExclusive) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM usage_record "
                        + "WHERE account_id = ? AND occurred_at >= ? AND occurred_at < ?",
                Long.class, accountId, fromInclusive, toExclusive);
        return count == null ? 0L : count;
    }
}
