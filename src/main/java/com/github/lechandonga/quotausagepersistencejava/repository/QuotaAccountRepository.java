package com.github.lechandonga.quotausagepersistencejava.repository;

import com.github.lechandonga.quotausagepersistencejava.domain.AccountStatus;
import com.github.lechandonga.quotausagepersistencejava.domain.QuotaAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 配额账户持久化。所有 SQL 均显式列出列名，列改名/删除时编译期或迁移期即可发现问题，
 * 不会因结果集列顺序变化而静默错列。
 */
@Repository
public class QuotaAccountRepository {

    private static final String COLUMNS =
            "id, account_name, status, total_quota, remaining, rollover_enabled, "
                    + "version, created_at, updated_at";

    private static final RowMapper<QuotaAccount> MAPPER = (rs, rowNum) -> new QuotaAccount(
            rs.getLong("id"),
            rs.getString("account_name"),
            AccountStatus.valueOf(rs.getString("status")),
            rs.getLong("total_quota"),
            rs.getLong("remaining"),
            rs.getBoolean("rollover_enabled"),
            rs.getLong("version"),
            rs.getLong("created_at"),
            rs.getLong("updated_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public QuotaAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(String accountName, long totalQuota, long nowMillis) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO quota_account "
                            + "(account_name, status, total_quota, remaining, rollover_enabled, "
                            + " version, created_at, updated_at) "
                            + "VALUES (?, 'ACTIVE', ?, ?, FALSE, 0, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, accountName);
            ps.setLong(2, totalQuota);
            ps.setLong(3, totalQuota);
            ps.setLong(4, nowMillis);
            ps.setLong(5, nowMillis);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<QuotaAccount> findById(long accountId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM quota_account WHERE id = ?", MAPPER, accountId)
                .stream().findFirst();
    }

    public Optional<QuotaAccount> findByName(String accountName) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM quota_account WHERE account_name = ?",
                        MAPPER, accountName)
                .stream().findFirst();
    }

    /**
     * 行锁读取：在同一事务内先锁住账户行，再基于版本号做乐观扣减，
     * 使并发冲突一定表现为更新行数 0，而不会覆盖彼此的余额。
     */
    public QuotaAccount lockById(long accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT " + COLUMNS + " FROM quota_account WHERE id = ? FOR UPDATE",
                MAPPER, accountId);
    }

    /**
     * 乐观扣减：
     * <ul>
     *   <li>WHERE remaining >= amount 保证绝不超额、绝不负余额；</li>
     *   <li>WHERE version = ? 保证并发的两个扣减最多只有一个成功；</li>
     *   <li>返回 0 行时由服务层区分“不足 / 停用 / 并发冲突”。</li>
     * </ul>
     */
    public int deduct(QuotaAccount account, long amount, long nowMillis) {
        return jdbcTemplate.update(
                "UPDATE quota_account "
                        + "SET remaining = remaining - ?, version = version + 1, updated_at = ? "
                        + "WHERE id = ? AND version = ? AND status = 'ACTIVE' AND remaining >= ?",
                amount, nowMillis, account.id(), account.version(), amount);
    }

    public int updateStatus(long accountId, AccountStatus status, long nowMillis) {
        return jdbcTemplate.update(
                "UPDATE quota_account SET status = ?, version = version + 1, updated_at = ? "
                        + "WHERE id = ?",
                status.name(), nowMillis, accountId);
    }
}
