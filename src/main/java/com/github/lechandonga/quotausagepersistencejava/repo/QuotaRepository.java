package com.github.lechandonga.quotausagepersistencejava.repo;

import com.github.lechandonga.quotausagepersistencejava.domain.AccountStatus;
import com.github.lechandonga.quotausagepersistencejava.domain.QuotaAccount;
import com.github.lechandonga.quotausagepersistencejava.domain.UsageRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 数据访问层。所有 SQL 显式列出列名，不使用 SELECT *，
 * 因此字段改名/删除只会在迁移或显式 SQL 处暴露，不会发生静默错列。
 */
@Repository
public class QuotaRepository {

    private static final RowMapper<QuotaAccount> ACCOUNT_MAPPER = (rs, rowNum) -> new QuotaAccount(
            rs.getLong("id"),
            rs.getString("account_no"),
            rs.getLong("balance"),
            AccountStatus.valueOf(rs.getString("status")),
            rs.getString("quota_type"),
            rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private static final RowMapper<UsageRecord> USAGE_MAPPER = (rs, rowNum) -> new UsageRecord(
            rs.getLong("id"),
            rs.getLong("account_id"),
            rs.getString("account_no"),
            rs.getLong("amount"),
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getString("note"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final NamedParameterJdbcTemplate jdbc;

    public QuotaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertAccount(String accountNo, long initialBalance, String quotaType, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("accountNo", accountNo)
                .addValue("balance", initialBalance)
                .addValue("status", AccountStatus.ACTIVE.name())
                .addValue("quotaType", quotaType)
                .addValue("version", 0L)
                .addValue("now", Timestamp.from(now));
        jdbc.update("""
                INSERT INTO quota_account
                    (account_no, balance, status, quota_type, version, created_at, updated_at)
                VALUES
                    (:accountNo, :balance, :status, :quotaType, :version, :now, :now)
                """, params, keyHolder, new String[]{"id"});
        return keyHolder.getKey().longValue();
    }

    public Optional<QuotaAccount> findAccountByNo(String accountNo) {
        List<QuotaAccount> accounts = jdbc.query("""
                SELECT id, account_no, balance, status, quota_type, version, created_at, updated_at
                FROM quota_account
                WHERE account_no = :accountNo
                """, new MapSqlParameterSource("accountNo", accountNo), ACCOUNT_MAPPER);
        return accounts.stream().findFirst();
    }

    /**
     * 条件扣减（原子 CAS）：
     * 仅当账户仍为 ACTIVE、余额充足且版本号未被并发事务修改时才更新成功。
     * 返回受影响行数：0 表示条件不满足，由服务层结合重读结果区分具体失败原因。
     */
    public int conditionalDebit(long accountId, long amount, long expectedVersion, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", accountId)
                .addValue("amount", amount)
                .addValue("expectedVersion", expectedVersion)
                .addValue("now", Timestamp.from(now));
        return jdbc.update("""
                UPDATE quota_account
                SET balance = balance - :amount,
                    version = version + 1,
                    updated_at = :now
                WHERE id = :id
                  AND status = 'ACTIVE'
                  AND version = :expectedVersion
                  AND balance >= :amount
                """, params);
    }

    public long insertUsage(QuotaAccount account, long amount, Instant occurredAt, String note) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("accountId", account.id())
                .addValue("accountNo", account.accountNo())
                .addValue("amount", amount)
                .addValue("occurredAt", Timestamp.from(occurredAt))
                .addValue("note", note == null ? "NONE" : note)
                .addValue("createdAt", Timestamp.from(occurredAt));
        jdbc.update("""
                INSERT INTO usage_record
                    (account_id, account_no, amount, occurred_at, note, created_at)
                VALUES
                    (:accountId, :accountNo, :amount, :occurredAt, :note, :createdAt)
                """, params, keyHolder, new String[]{"id"});
        return keyHolder.getKey().longValue();
    }

    /**
     * 键集（seek）分页：仅按复合索引 (account_id, occurred_at, id) 做范围扫描，
     * 与数据总量和已翻过的页数无关，每页最多 limit + 1 行。
     */
    public List<UsageRecord> seekUsage(long accountId,
                                       Instant fromInclusive,
                                       Instant toExclusive,
                                       Instant anchorTime,
                                       Long anchorId,
                                       int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("from", fromInclusive == null ? null : Timestamp.from(fromInclusive))
                .addValue("to", toExclusive == null ? null : Timestamp.from(toExclusive))
                .addValue("anchorTime", anchorTime == null ? null : Timestamp.from(anchorTime))
                .addValue("anchorId", anchorId)
                .addValue("limit", limit);
        // USE INDEX：H2 对单列 FK 索引与复合索引可能误估；显式锁定游标复合索引，
        // 保证 (account_id, occurred_at, id) 的范围扫描计划稳定，不随统计信息波动。
        String sql = """
                SELECT id, account_id, account_no, amount, occurred_at, note, created_at
                FROM usage_record USE INDEX (idx_usage_account_time)
                WHERE account_id = :accountId
                  AND (:from IS NULL OR occurred_at >= :from)
                  AND (:to IS NULL OR occurred_at < :to)
                  AND (
                        :anchorTime IS NULL
                        OR occurred_at > :anchorTime
                        OR (occurred_at = :anchorTime AND id > :anchorId)
                      )
                ORDER BY occurred_at ASC, id ASC
                FETCH FIRST :limit ROWS ONLY
                """;
        return jdbc.query(sql, params, USAGE_MAPPER);
    }

    /**
     * 校验游标锚点：必须真实存在、属于同一账户、且落在本次查询的时间窗口内。
     * 任一条件不满足都说明游标对当前查询已不可见，调用方必须重新从首页开始。
     */
    public boolean isAnchorVisible(String accountNo, long recordId, Instant anchorTime,
                                   Instant fromInclusive, Instant toExclusive) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("accountNo", accountNo)
                .addValue("id", recordId)
                .addValue("anchorTime", Timestamp.from(anchorTime))
                .addValue("from", fromInclusive == null ? null : Timestamp.from(fromInclusive))
                .addValue("to", toExclusive == null ? null : Timestamp.from(toExclusive));
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(1)
                FROM usage_record
                WHERE account_no = :accountNo
                  AND id = :id
                  AND occurred_at = :anchorTime
                  AND (:from IS NULL OR occurred_at >= :from)
                  AND (:to IS NULL OR occurred_at < :to)
                """, params, Integer.class);
        return count != null && count > 0;
    }

    public int updateStatus(long accountId, AccountStatus newStatus, long expectedVersion, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", accountId)
                .addValue("status", newStatus.name())
                .addValue("expectedVersion", expectedVersion)
                .addValue("now", Timestamp.from(now));
        return jdbc.update("""
                UPDATE quota_account
                SET status = :status,
                    version = version + 1,
                    updated_at = :now
                WHERE id = :id AND version = :expectedVersion
                """, params);
    }

    public long countUsageInWindow(String accountNo, Instant fromInclusive, Instant toExclusive) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("accountNo", accountNo)
                .addValue("from", fromInclusive == null ? null : Timestamp.from(fromInclusive))
                .addValue("to", toExclusive == null ? null : Timestamp.from(toExclusive));
        Long count = jdbc.queryForObject("""
                SELECT COUNT(1)
                FROM usage_record
                WHERE account_no = :accountNo
                  AND (:from IS NULL OR occurred_at >= :from)
                  AND (:to IS NULL OR occurred_at < :to)
                """, params, Long.class);
        return count == null ? 0L : count;
    }
}
