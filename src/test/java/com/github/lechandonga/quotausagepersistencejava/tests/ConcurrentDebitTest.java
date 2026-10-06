package com.github.lechandonga.quotausagepersistencejava.tests;

import com.github.lechandonga.quotausagepersistencejava.domain.QuotaAccount;
import com.github.lechandonga.quotausagepersistencejava.service.DeductionException;
import com.github.lechandonga.quotausagepersistencejava.service.DeductionFailureKind;
import com.github.lechandonga.quotausagepersistencejava.service.QuotaService;
import com.github.lechandonga.quotausagepersistencejava.tests.support.BusinessCoverage;
import com.github.lechandonga.quotausagepersistencejava.tests.support.CoverageTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发扣减场景：无超额、无负余额，失败原因可区分，余额与用量总数对得上。
 */
@CoverageTest
class ConcurrentDebitTest {

    private final QuotaService quotaService;

    ConcurrentDebitTest(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @Test
    @BusinessCoverage(module = "并发扣减", caseDescription = "32 线程竞争扣减同一账户：成功总额严格等于初始配额，无超额、无负余额")
    void concurrentDebitsNeverOvershoot() throws Exception {
        String accountNo = "cc-" + UUID.randomUUID();
        // 故意取不能被单笔整除的总量，验证最终停在取整边界且无超额。
        long totalQuota = 10_100L;
        quotaService.createAccount(accountNo, totalQuota, null);

        int threads = 32;
        long amountEach = 400L;
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // 调用方对 CONCURRENT_CONFLICT 负责重试（服务端不静默重试，冲突显式可解释）；
                    // 观察到“配额不足”时，其他竞争事务可能随后回滚释放配额，
                    // 因此在宽限期内复查，直到余额确定低于单笔，不足才成为终态。
                    while (true) {
                        try {
                            quotaService.debit(accountNo, amountEach, "concurrent");
                            success.incrementAndGet();
                            break;
                        } catch (DeductionException e) {
                            if (e.getKind() == DeductionFailureKind.CONCURRENT_CONFLICT) {
                                conflicts.incrementAndGet();
                                continue;
                            }
                            if (e.getKind() == DeductionFailureKind.INSUFFICIENT_QUOTA) {
                                if (System.nanoTime() < deadlineNanos
                                        && quotaService.getAccount(accountNo).balance() >= amountEach) {
                                    continue;
                                }
                                insufficient.incrementAndGet();
                                break;
                            }
                            throw e;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发扣减应在超时前全部结束");
        pool.shutdown();

        QuotaAccount account = quotaService.getAccount(accountNo);
        assertEquals(threads, success.get() + insufficient.get(),
                "全部重试结束后，每笔归为成功或最终配额不足");
        long consumedBySuccess = success.get() * amountEach;
        assertTrue(consumedBySuccess <= totalQuota, "成功扣减总量绝不能超过总配额");
        long finalBalance = totalQuota - consumedBySuccess;
        assertEquals(finalBalance, account.balance(),
                "余额精确等于总配额减去成功扣减总量（守恒），实际余额=" + account.balance());
        assertTrue(account.balance() >= 0, "余额绝不能为负");
        assertTrue(finalBalance < amountEach,
                "最终必须停在不足以再扣一笔的边界，余额=" + finalBalance);
        System.out.printf("[并发扣减] 成功=%d 笔，配额不足拒绝=%d 笔，期间并发冲突=%d 次，剩余余额=%d%n",
                success.get(), insufficient.get(), conflicts.get(), account.balance());

        DeductionException finalEx = org.junit.jupiter.api.Assertions.assertThrows(DeductionException.class,
                () -> quotaService.debit(accountNo, amountEach, "after-settle"));
        assertEquals(DeductionFailureKind.INSUFFICIENT_QUOTA, finalEx.getKind(),
                "并发结算完成后再扣必须明确返回配额不足");
    }

    @Test
    @BusinessCoverage(module = "并发扣减", caseDescription = "失败原因可区分：配额不足与账户停用分别返回 INSUFFICIENT_QUOTA / ACCOUNT_SUSPENDED")
    void failureKindsAreDistinguishable() {
        String poor = "cc-poor-" + UUID.randomUUID();
        quotaService.createAccount(poor, 5, null);
        DeductionException poorEx = org.junit.jupiter.api.Assertions.assertThrows(DeductionException.class,
                () -> quotaService.debit(poor, 10, null));
        assertEquals(DeductionFailureKind.INSUFFICIENT_QUOTA, poorEx.getKind());

        String suspended = "cc-suspended-" + UUID.randomUUID();
        quotaService.createAccount(suspended, 100, null);
        quotaService.suspend(suspended);
        DeductionException suspendedEx = org.junit.jupiter.api.Assertions.assertThrows(DeductionException.class,
                () -> quotaService.debit(suspended, 1, null));
        assertEquals(DeductionFailureKind.ACCOUNT_SUSPENDED, suspendedEx.getKind());

        String missing = "cc-missing-" + UUID.randomUUID();
        DeductionException missingEx = org.junit.jupiter.api.Assertions.assertThrows(DeductionException.class,
                () -> quotaService.debit(missing, 1, null));
        assertEquals(DeductionFailureKind.ACCOUNT_NOT_FOUND, missingEx.getKind());

        assertEquals(5L, quotaService.getAccount(poor).balance(), "失败扣减不得改变余额");
        assertEquals(100L, quotaService.getAccount(suspended).balance(), "停用账户余额不受影响");
    }

    @Test
    @BusinessCoverage(module = "并发扣减", caseDescription = "边界：余额恰好等于扣减量时成功且余额归零；再扣一笔被拒")
    void exactBoundaryDebitSucceedsThenRejected() {
        String accountNo = "cc-boundary-" + UUID.randomUUID();
        quotaService.createAccount(accountNo, 10, null);
        quotaService.debit(accountNo, 10, null);
        assertEquals(0L, quotaService.getAccount(accountNo).balance(), "恰好用尽时余额为 0");
        DeductionException ex = org.junit.jupiter.api.Assertions.assertThrows(DeductionException.class,
                () -> quotaService.debit(accountNo, 1, null));
        assertEquals(DeductionFailureKind.INSUFFICIENT_QUOTA, ex.getKind(), "零余额再扣必须被拒绝");
    }

    @Test
    @BusinessCoverage(module = "并发扣减", caseDescription = "非法输入：非正扣减量直接拒绝，且不产生任何用量记录")
    void nonPositiveAmountRejected() {
        String accountNo = "cc-nonpos-" + UUID.randomUUID();
        quotaService.createAccount(accountNo, 100, null);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> quotaService.debit(accountNo, 0, null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> quotaService.debit(accountNo, -5, null));
        assertEquals(100L, quotaService.getAccount(accountNo).balance());
        org.junit.jupiter.api.Assertions.assertEquals(0,
                quotaService.queryUsage(accountNo, null, null, null, 10).records().size());
    }
}
