package com.github.lechandonga.quotausagepersistencejava.tests;

import com.github.lechandonga.quotausagepersistencejava.domain.UsagePage;
import com.github.lechandonga.quotausagepersistencejava.domain.UsageRecord;
import com.github.lechandonga.quotausagepersistencejava.service.CursorException;
import com.github.lechandonga.quotausagepersistencejava.service.QuotaService;
import com.github.lechandonga.quotausagepersistencejava.tests.support.BusinessCoverage;
import com.github.lechandonga.quotausagepersistencejava.tests.support.CoverageTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 游标翻页场景：稳定快照语义、非法/失效游标明确报错、时间窗口边界正确。
 */
@CoverageTest
class CursorPagingTest {

    private final QuotaService quotaService;

    CursorPagingTest(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    private String seedAccount(int records) {
        String accountNo = "pg-" + UUID.randomUUID();
        quotaService.createAccount(accountNo, records * 10L, null);
        for (int i = 0; i < records; i++) {
            quotaService.debit(accountNo, 1, "seed-" + i);
        }
        return accountNo;
    }

    @Test
    @BusinessCoverage(module = "游标翻页", caseDescription = "连续翻页不重不漏：拼接全部页后记录ID唯一且数量等于总量")
    void pagesCoverAllWithoutDuplicationOrGap() {
        String accountNo = seedAccount(23);
        List<Long> collectedIds = new ArrayList<>();
        String cursor = null;
        int pageCount = 0;
        do {
            UsagePage page = quotaService.queryUsage(accountNo, null, null, cursor, 5);
            page.records().stream().map(UsageRecord::id).forEach(collectedIds::add);
            cursor = page.nextCursor();
            pageCount++;
        } while (cursor != null);

        assertEquals(23, collectedIds.size(), "全部记录必须恰好收集一次");
        assertEquals(23, new HashSet<>(collectedIds).size(), "翻页过程中不能出现重复记录");
        assertEquals(5, pageCount, "23 条按每页 5 条共 5 页（末页 3 条）");
    }

    @Test
    @BusinessCoverage(module = "游标翻页", caseDescription = "翻页进行中写入新记录：已遍历部分不重复、不漏（新记录落在尚未消费的尾部）")
    void writesDuringPagingDoNotCorruptVisitedPages() {
        String accountNo = seedAccount(10);
        UsagePage firstPage = quotaService.queryUsage(accountNo, null, null, null, 4);
        Set<Long> firstIds = new HashSet<>(firstPage.records().stream().map(UsageRecord::id).toList());
        assertEquals(4, firstIds.size());

        for (int i = 0; i < 6; i++) {
            quotaService.debit(accountNo, 1, "written-during-paging");
        }

        Set<Long> allIds = new HashSet<>(firstIds);
        String cursor = firstPage.nextCursor();
        assertNotNull(cursor);
        while (cursor != null) {
            UsagePage page = quotaService.queryUsage(accountNo, null, null, cursor, 4);
            for (UsageRecord record : page.records()) {
                assertTrue(allIds.add(record.id()), "已翻过的记录不能重复出现: " + record.id());
            }
            cursor = page.nextCursor();
        }
        assertEquals(16, allIds.size(), "原有10条 + 翻页中新增6条都必须可见，且无重复");
    }

    @Test
    @BusinessCoverage(module = "游标翻页", caseDescription = "非法游标：篡改签名/乱码一律报 INVALID_CURSOR，不会从头继续")
    void malformedCursorRejected() {
        String accountNo = seedAccount(2);
        UsagePage page = quotaService.queryUsage(accountNo, null, null, null, 1);
        String validCursor = page.nextCursor();
        assertNotNull(validCursor);

        assertThrows(CursorException.class, () ->
                quotaService.queryUsage(accountNo, null, null, "not-a-cursor", 1));
        assertThrows(CursorException.class, () ->
                quotaService.queryUsage(accountNo, null, null, validCursor + "x", 1));
        assertThrows(CursorException.class, () ->
                quotaService.queryUsage(accountNo, null, null, "AAA.bbb", 1));

        UsagePage afterReject = quotaService.queryUsage(accountNo, null, null, validCursor, 1);
        assertNotNull(afterReject, "使用合法游标仍可正常续页，拒绝非法游标不影响服务状态");
    }

    @Test
    @BusinessCoverage(module = "游标翻页", caseDescription = "游标对当前窗口不可见：跨账户使用、改变时间范围都明确报错，不从中间继续")
    void invisibleAnchorRejected() {
        String accountA = seedAccount(5);
        String accountB = seedAccount(5);
        String cursorA = quotaService.queryUsage(accountA, null, null, null, 2).nextCursor();

        CursorException crossAccount = assertThrows(CursorException.class, () ->
                quotaService.queryUsage(accountB, null, null, cursorA, 2));
        assertTrue(crossAccount.getMessage().contains("不属于请求的账户"));

        Instant now = Instant.now();
        CursorException changedWindow = assertThrows(CursorException.class, () ->
                quotaService.queryUsage(accountA, now.plus(1, ChronoUnit.HOURS),
                        now.plus(2, ChronoUnit.HOURS), cursorA, 2));
        assertTrue(changedWindow.getMessage().contains("不可见"));
    }

    @Test
    @BusinessCoverage(module = "游标翻页", caseDescription = "时间范围边界：from 含、to 不含；空窗口返回空且无游标；非法窗口被拒绝")
    void timeWindowBoundaries() {
        String accountNo = "pg-window-" + UUID.randomUUID();
        quotaService.createAccount(accountNo, 100, null);
        Instant before = Instant.now().minusSeconds(1);
        UsageRecord record = quotaService.debit(accountNo, 1, null);
        Instant after = Instant.now().plusSeconds(1);

        UsagePage inclusive = quotaService.queryUsage(accountNo, record.occurredAt(), after, null, 10);
        assertEquals(1, inclusive.records().size(), "from 为闭区间边界，锚点时刻的记录应包含");

        UsagePage exclusive = quotaService.queryUsage(accountNo, before, record.occurredAt(), null, 10);
        assertEquals(0, exclusive.records().size(), "to 为开区间边界，锚点时刻的记录不应包含");
        assertNull(exclusive.nextCursor());
        assertFalse(exclusive.hasMore());

        assertThrows(IllegalArgumentException.class, () ->
                quotaService.queryUsage(accountNo, after, before, null, 10));
    }
}
