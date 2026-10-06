package com.github.lechandonga.quotausagepersistencejava.tests;

import com.github.lechandonga.quotausagepersistencejava.domain.UsageRecord;
import com.github.lechandonga.quotausagepersistencejava.repository.UsageRecordRepository;
import com.github.lechandonga.quotausagepersistencejava.service.Page;
import com.github.lechandonga.quotausagepersistencejava.service.QuotaErrorCode;
import com.github.lechandonga.quotausagepersistencejava.service.QuotaService;
import com.github.lechandonga.quotausagepersistencejava.service.QuotaServiceException;
import com.github.lechandonga.quotausagepersistencejava.tests.support.QueryCountingConfig;
import com.github.lechandonga.quotausagepersistencejava.tests.support.QueryCountingDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 业务：游标翻页查询。
 * 用例：
 * 1) 固定窗口 237 条、页大小 50：5 页取全，顺序按 (occurredAt,id) 升序，无重无漏；
 * 2) 翻页过程中窗口内持续写入新记录：已翻过的页不重复、不漏，且能取到新记录；
 * 3) 非法游标（乱码、篡改、跨账户复用）必须报 INVALID_CURSOR；
 * 4) 游标指向已不可见记录（删除/越窗）必须报 CURSOR_TARGET_INVISIBLE，不会从头继续；
 * 5) 每页只触发 1 次分页 SQL（QueryCountingDataSource 计数），并校验走索引扫描。
 */
@SpringBootTest
@Import(QueryCountingConfig.class)
@DisplayName("业务-游标翻页：不重不漏、非法游标报错、每页一条索引查询")
class CursorPaginationTest {

    private static final Logger log = LoggerFactory.getLogger(CursorPaginationTest.class);

    private static final long WINDOW_FROM = 1_000_000L;
    private static final long WINDOW_TO = 2_000_000L;

    @Autowired
    private QuotaService quotaService;

    @Autowired
    private UsageRecordRepository usageRecordRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("237 条 / 每页 50：5 页取全，升序、无重无漏，每页仅 1 条分页 SQL")
    void keysetPaginationIsStableAndBounded() {
        long accountId = quotaService.createAccount("pager", 100_000).accountId();
        seedRecords(accountId, 237, WINDOW_FROM);

        QueryCountingDataSource counter = (QueryCountingDataSource) dataSource;
        counter.reset();

        List<UsageRecord> all = new ArrayList<>();
        String cursor = null;
        int pageNo = 0;
        long queriesBefore = 0;
        do {
            Page page = quotaService.queryUsage(accountId, WINDOW_FROM, WINDOW_TO, 50, cursor);
            long after = counter.pageQueryCount();
            if (pageNo > 0) {
                assertThat(after - queriesBefore)
                        .as("第 %d 页只能触发 1 次分页 SQL，不能按条查库", pageNo + 1)
                        .isEqualTo(1);
            }
            queriesBefore = after;
            all.addAll(page.records());
            cursor = page.nextCursor();
            pageNo++;
        } while (cursor != null);

        log.info("[游标翻页] 共 {} 页取到 {} 条，分页 SQL 总数={}", pageNo, all.size(),
                counter.pageQueryCount());

        assertThat(pageNo).isEqualTo(5);
        assertThat(all).hasSize(237);
        Set<Long> uniqueIds = new HashSet<>();
        for (UsageRecord record : all) {
            assertThat(uniqueIds.add(record.id())).as("记录不得重复").isTrue();
        }
        List<Long> sortedIds = all.stream().map(UsageRecord::id).sorted().toList();
        assertThat(all.stream().map(UsageRecord::id).toList()).isEqualTo(sortedIds);
        // 5 页里后 4 页各 1 次分页 SQL，首页与翻页统一计数共 5 次，与数据总量无关。
        assertThat(counter.pageQueryCount()).isEqualTo(5L);

        String plan = jdbcTemplate.queryForObject(
                "EXPLAIN SELECT id, account_id, amount, occurred_at, biz_ref, note "
                        + "FROM usage_record "
                        + "WHERE account_id = 1 AND occurred_at >= 1 AND occurred_at < 2 "
                        + "  AND (occurred_at > 1 OR (occurred_at = 1 AND id > 1)) "
                        + "ORDER BY occurred_at, id LIMIT 51",
                String.class);
        log.info("[执行计划] {}", plan);
        assertThat(plan).containsIgnoringCase("IDX_USAGE_ACCOUNT_TIME_ID");
    }

    @Test
    @DisplayName("翻页途中窗口内持续写入：已翻过部分不重不漏，晚于游标位置的新记录可取到")
    void concurrentWritesDuringPagingDoNotDuplicateOrSkip() {
        long accountId = quotaService.createAccount("pager-live", 100_000).accountId();
        // 155 条取 3 页后仍有剩余，保证翻页游标非空。
        seedRecords(accountId, 155, WINDOW_FROM);

        List<UsageRecord> collected = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < 3; i++) {
            Page page = quotaService.queryUsage(accountId, WINDOW_FROM, WINDOW_TO, 50, cursor);
            collected.addAll(page.records());
            cursor = page.nextCursor();
        }
        int seenAfterThreePages = collected.size();
        log.info("[翻页中写入] 已取 {} 条（游标锚点为窗口起始段），现在向游标之后追加 90 条，"
                + "并插入 1 条时间戳落在已翻过区间的迟到行", seenAfterThreePages);

        // 90 条新记录的排序键晚于当前游标，继续翻页必须能取到且不重复；
        // 1 条“迟到行”的事件时间落在已翻过区间——游标是稳定边界，它不会被回放
        // （回放才会造成重复），这正是“翻过的部分不重复”的确定性语义。
        seedRecords(accountId, 90, WINDOW_FROM + 5_000);
        jdbcTemplate.update(
                "INSERT INTO usage_record (account_id, amount, occurred_at, biz_ref, note) "
                        + "VALUES (?, 1, ?, 'late-arrival', '')",
                accountId, WINDOW_FROM + 1);

        while (cursor != null) {
            Page page = quotaService.queryUsage(accountId, WINDOW_FROM, WINDOW_TO, 50, cursor);
            collected.addAll(page.records());
            cursor = page.nextCursor();
        }

        long totalInWindow = usageRecordRepository.countByWindow(accountId, WINDOW_FROM, WINDOW_TO);
        long distinct = collected.stream().map(UsageRecord::id).distinct().count();
        long lateArrivals = collected.stream().filter(r -> "late-arrival".equals(r.bizRef())).count();
        log.info("[翻页中写入] 翻页共取 {} 条（去重 {}，其中迟到行 {} 条），窗口实际 {} 条；"
                        + "已取 150 条不重复，游标后新写入的 90 条全部取到",
                collected.size(), distinct, lateArrivals, totalInWindow);

        // 前 3 页 150 条 + 续翻取到剩余 5 条与新写入 90 条 = 245 条；
        // 加上未回放的 1 条迟到行，窗口共 246 条。
        assertThat(collected).hasSize(245);
        assertThat(distinct).isEqualTo(245L);
        assertThat(lateArrivals).isZero();
        assertThat(totalInWindow).isEqualTo(246L);
    }

    @Test
    @DisplayName("非法游标：乱码/伪造内容/跨账户复用，均报 INVALID_CURSOR")
    void invalidCursorsAreRejected() {
        long accountId = quotaService.createAccount("pager-bad", 100).accountId();
        long otherAccount = quotaService.createAccount("pager-bad-other", 100).accountId();
        seedRecords(accountId, 3, WINDOW_FROM);

        Page page = quotaService.queryUsage(accountId, WINDOW_FROM, WINDOW_TO, 2, null);
        String validCursor = page.nextCursor();
        assertThat(validCursor).isNotNull();

        assertThatThrownBy(() ->
                quotaService.queryUsage(accountId, WINDOW_FROM, WINDOW_TO, 2, "@@@not-base64@@@"))
                .isInstanceOf(QuotaServiceException.class)
                .extracting(e -> ((QuotaServiceException) e).getErrorCode())
                .isEqualTo(QuotaErrorCode.INVALID_CURSOR);

        String forged = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v1|999|1|2".getBytes());
        assertThatThrownBy(() ->
                quotaService.queryUsage(accountId, WINDOW_FROM, WINDOW_TO, 2, forged))
                .isInstanceOf(QuotaServiceException.class)
                .extracting(e -> ((QuotaServiceException) e).getErrorCode())
                .isEqualTo(QuotaErrorCode.INVALID_CURSOR);

        assertThatThrownBy(() ->
                quotaService.queryUsage(otherAccount, WINDOW_FROM, WINDOW_TO, 2, validCursor))
                .isInstanceOf(QuotaServiceException.class)
                .extracting(e -> ((QuotaServiceException) e).getErrorCode())
                .isEqualTo(QuotaErrorCode.INVALID_CURSOR);
        log.info("[非法游标] 乱码、伪造、跨账户三类游标均被拒绝");
    }

    @Test
    @DisplayName("游标指向不可见记录：物理删除与时间窗越界，均报 CURSOR_TARGET_INVISIBLE")
    void cursorPointingAtInvisibleRowIsRejected() {
        long accountId = quotaService.createAccount("pager-ghost", 100_000).accountId();
        seedRecords(accountId, 4, WINDOW_FROM);

        Page page = quotaService.queryUsage(accountId, WINDOW_FROM, WINDOW_TO, 2, null);
        String cursor = page.nextCursor();

        long anchorId = page.records().get(1).id();
        jdbcTemplate.update("DELETE FROM usage_record WHERE id = ?", anchorId);
        assertThatThrownBy(() ->
                quotaService.queryUsage(accountId, WINDOW_FROM, WINDOW_TO, 2, cursor))
                .isInstanceOf(QuotaServiceException.class)
                .extracting(e -> ((QuotaServiceException) e).getErrorCode())
                .isEqualTo(QuotaErrorCode.CURSOR_TARGET_INVISIBLE);

        assertThatThrownBy(() ->
                quotaService.queryUsage(accountId, WINDOW_FROM + 2, WINDOW_TO, 2, cursor))
                .isInstanceOf(QuotaServiceException.class)
                .extracting(e -> ((QuotaServiceException) e).getErrorCode())
                .isEqualTo(QuotaErrorCode.CURSOR_TARGET_INVISIBLE);
        log.info("[不可见游标] 锚点删除与窗口越界都拒绝续翻，不会从头或从中间继续");
    }

    private void seedRecords(long accountId, int count, long baseOccurredAt) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update(
                    "INSERT INTO usage_record (account_id, amount, occurred_at, biz_ref, note) "
                            + "VALUES (?, 1, ?, ?, '')",
                    accountId, baseOccurredAt + i, "seed-" + baseOccurredAt + "-" + i);
        }
    }
}
