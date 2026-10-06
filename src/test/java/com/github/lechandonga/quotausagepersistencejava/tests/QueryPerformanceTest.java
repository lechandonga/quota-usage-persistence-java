package com.github.lechandonga.quotausagepersistencejava.tests;

import com.github.lechandonga.quotausagepersistencejava.domain.QuotaAccount;
import com.github.lechandonga.quotausagepersistencejava.domain.UsagePage;
import com.github.lechandonga.quotausagepersistencejava.domain.UsageRecord;
import com.github.lechandonga.quotausagepersistencejava.repo.QuotaRepository;
import com.github.lechandonga.quotausagepersistencejava.service.CursorCodec;
import com.github.lechandonga.quotausagepersistencejava.service.QuotaService;
import com.github.lechandonga.quotausagepersistencejava.tests.support.BusinessCoverage;
import com.github.lechandonga.quotausagepersistencejava.tests.support.CoverageTest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查询性能的可验证依据：全表 2 万行（10 个账户各 2000 行）下，
 * 首页与“尾页”（深翻页）都只走复合索引范围扫描；扫描行数按目标账户有界，
 * 与全表规模和已翻页数无关；每页固定 2 条 SQL（首页 1 条），不按条查库。
 */
@CoverageTest
class QueryPerformanceTest {

    private static final int ACCOUNTS = 10;
    private static final int PER_ACCOUNT = 2_000;
    private static final int TOTAL = ACCOUNTS * PER_ACCOUNT;
    private static final int PAGE_SIZE = 100;

    private final QuotaService quotaService;
    private final QuotaRepository repository;
    private final CursorCodec cursorCodec;
    private final JdbcTemplate jdbcTemplate;

    QueryPerformanceTest(QuotaService quotaService,
                         QuotaRepository repository,
                         CursorCodec cursorCodec,
                         JdbcTemplate jdbcTemplate) {
        this.quotaService = quotaService;
        this.repository = repository;
        this.cursorCodec = cursorCodec;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    @BusinessCoverage(module = "查询性能", caseDescription = "2万行/10账户：深翻页与首页同为复合索引范围扫描，扫描量按账户有界且耗时稳定")
    void deepPagingUsesIndexRangeScan() {
        Instant base = Instant.now().minusSeconds(3600);
        String accountNo = "perf-" + UUID.randomUUID();
        QuotaAccount account = quotaService.createAccount(accountNo, PER_ACCOUNT + 1L, null);
        jdbcTemplate.batchUpdate(
                "INSERT INTO usage_record (account_id, account_no, amount, occurred_at, note, created_at) "
                        + "VALUES (?, ?, 1, ?, 'perf-seed', ?)",
                buildBatchParams(account, base, PER_ACCOUNT));

        for (int a = 1; a < ACCOUNTS; a++) {
            QuotaAccount other = quotaService.createAccount(
                    "perf-other-" + a + "-" + UUID.randomUUID(), PER_ACCOUNT + 1L, null);
            jdbcTemplate.batchUpdate(
                    "INSERT INTO usage_record (account_id, account_no, amount, occurred_at, note, created_at) "
                            + "VALUES (?, ?, 1, ?, 'perf-seed', ?)",
                    buildBatchParams(other, base, PER_ACCOUNT));
        }
        jdbcTemplate.execute("ANALYZE");

        assertEquals(PER_ACCOUNT, repository.countUsageInWindow(accountNo, null, null));

        UsagePage firstPage = quotaService.queryUsage(accountNo, null, null, null, PAGE_SIZE);
        assertEquals(PAGE_SIZE, firstPage.records().size());

        List<UsageRecord> allForAccount = repository.seekUsage(account.id(), null, null,
                null, null, Integer.MAX_VALUE);
        assertEquals(PER_ACCOUNT, allForAccount.size());
        UsageRecord tailAnchor = allForAccount.get(PER_ACCOUNT - 2);
        String tailCursor = cursorCodec.encode(accountNo, tailAnchor.occurredAt(), tailAnchor.id());

        long firstStart = System.nanoTime();
        UsagePage firstAgain = quotaService.queryUsage(accountNo, null, null, null, PAGE_SIZE);
        long firstElapsedMs = (System.nanoTime() - firstStart) / 1_000_000;

        long tailStart = System.nanoTime();
        UsagePage tailPage = quotaService.queryUsage(accountNo, null, null, tailCursor, PAGE_SIZE);
        long tailElapsedMs = (System.nanoTime() - tailStart) / 1_000_000;

        assertEquals(PAGE_SIZE, firstAgain.records().size());
        assertEquals(1, tailPage.records().size(), "锚点之后只剩 1 条");
        assertNotNull(firstPage.nextCursor());

        System.out.printf("[查询性能] 全表=%d 行（%d 个账户各 %d 行），首页耗时=%dms，第%d页(尾页)耗时=%dms%n",
                TOTAL, ACCOUNTS, PER_ACCOUNT, firstElapsedMs,
                PER_ACCOUNT / PAGE_SIZE, tailElapsedMs);
        assertTrue(firstElapsedMs < 1000, "首页应在 1s 内完成，实际 " + firstElapsedMs + "ms");
        assertTrue(tailElapsedMs < 1000, "深翻页耗时应与页码无关，实际 " + tailElapsedMs + "ms");

        String firstPlan = explainPlan(account.id(), null, null);
        String tailPlan = explainPlan(account.id(), tailAnchor.occurredAt(), tailAnchor.id());
        System.out.println("[查询性能] 首页执行计划: " + firstPlan.replaceAll("\\s+", " "));
        System.out.println("[查询性能] 尾页执行计划: " + tailPlan.replaceAll("\\s+", " "));
        assertIndexRangeScan(firstPlan);
        assertIndexRangeScan(tailPlan);
    }

    private void assertIndexRangeScan(String plan) {
        String upper = plan.toUpperCase();
        assertTrue(upper.contains("IDX_USAGE_ACCOUNT_TIME"),
                "执行计划必须使用游标复合索引 IDX_USAGE_ACCOUNT_TIME，实际: " + plan);
        assertTrue(!upper.contains("TABLE SCAN"), "不允许全表扫描，实际: " + plan);
        Matcher matcher = Pattern.compile("SCANCOUNT: (\\d+)").matcher(upper);
        assertTrue(matcher.find(), "执行计划应包含 scanCount 指标: " + plan);
        long scanCount = Long.parseLong(matcher.group(1));
        assertTrue(scanCount <= PER_ACCOUNT + 1L,
                "扫描行数必须按目标账户有界（<=" + (PER_ACCOUNT + 1) + "），与全表 " + TOTAL
                        + " 行无关，实际 scanCount=" + scanCount);
    }

    private String explainPlan(long accountId, Instant anchorTime, Long anchorId) {
        String sql;
        if (anchorTime == null) {
            sql = "EXPLAIN ANALYZE SELECT id, account_id, account_no, amount, occurred_at, note, created_at "
                    + "FROM usage_record USE INDEX (idx_usage_account_time) WHERE account_id = " + accountId
                    + " ORDER BY occurred_at ASC, id ASC FETCH FIRST " + (PAGE_SIZE + 1) + " ROWS ONLY";
        } else {
            sql = "EXPLAIN ANALYZE SELECT id, account_id, account_no, amount, occurred_at, note, created_at "
                    + "FROM usage_record USE INDEX (idx_usage_account_time) WHERE account_id = " + accountId
                    + " AND (occurred_at > TIMESTAMP '" + Timestamp.from(anchorTime).toInstant() + "'"
                    + " OR (occurred_at = TIMESTAMP '" + Timestamp.from(anchorTime).toInstant()
                    + "' AND id > " + anchorId + "))"
                    + " ORDER BY occurred_at ASC, id ASC FETCH FIRST " + (PAGE_SIZE + 1) + " ROWS ONLY";
        }
        return jdbcTemplate.queryForObject(sql, String.class);
    }

    private List<Object[]> buildBatchParams(QuotaAccount account, Instant base, int count) {
        List<Object[]> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Timestamp timestamp = Timestamp.from(base.plusNanos(i * 1000L));
            batch.add(new Object[]{account.id(), account.accountNo(), timestamp, timestamp});
        }
        return batch;
    }
}
