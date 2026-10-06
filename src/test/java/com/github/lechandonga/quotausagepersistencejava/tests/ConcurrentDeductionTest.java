package com.github.lechandonga.quotausagepersistencejava.tests;

import com.github.lechandonga.quotausagepersistencejava.service.QuotaErrorCode;
import com.github.lechandonga.quotausagepersistencejava.service.QuotaService;
import com.github.lechandonga.quotausagepersistencejava.service.QuotaServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务：并发扣减。
 * 用例：
 * 1) 总配额 500、200 个线程各扣 5 —— 成功恰好 100 个，其余全部明确为“配额不足”，
 *    余额最终为 0，不出现负余额/超额；
 * 2) 成功笔数与用量记录笔数一致，证明余额与记账同事务；
 * 3) 两笔各 30 对总配额 50 的账户并发扣减 —— 一个成功、另一个必须报“配额不足”。
 */
@SpringBootTest
@DisplayName("业务-并发扣减：不超额、不负余额、失败原因可区分")
class ConcurrentDeductionTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentDeductionTest.class);

    @Autowired
    private QuotaService quotaService;

    @Test
    @DisplayName("200 并发各扣 5 / 总配额 500：恰好 100 笔成功，其余为配额不足，余额为 0")
    void concurrentDeductionNeverOverdraws() throws Exception {
        long accountId = quotaService.createAccount("concurrency-account", 500).accountId();
        int threads = 200;
        long amountEach = 5L;

        // 每个任务一个线程：所有线程必须能同时在起跑栅栏前阻塞，池太小会自我死锁。
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        ConcurrentLinkedQueue<QuotaErrorCode> failureCodes = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    quotaService.deduct(accountId, amountEach, "biz-" + idx, "concurrent");
                    success.incrementAndGet();
                } catch (QuotaServiceException e) {
                    failureCodes.add(e.getErrorCode());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        log.info("[并发扣减] {} 个线程同时发起扣减，每笔 {}", threads, amountEach);
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        long remaining = quotaService.getAccount(accountId).remaining();
        long records = quotaService.queryUsage(accountId, 0, Long.MAX_VALUE, 200, null)
                .records().size();

        log.info("[并发扣减] 成功={} 失败={} 失败原因分布={} 最终余额={} 当页用量记录={}",
                success.get(), failureCodes.size(), failureCodes.stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                java.util.function.Function.identity(),
                                java.util.stream.Collectors.counting())),
                remaining, records);

        assertThat(success.get()).isEqualTo(100);
        assertThat(success.get() + failureCodes.size()).isEqualTo(threads);
        assertThat(remaining).isZero();
        assertThat(failureCodes).containsOnly(QuotaErrorCode.INSUFFICIENT_QUOTA);

        long totalRecords = countAllUsage(accountId);
        assertThat(totalRecords).isEqualTo(100L);
        log.info("[并发扣减] 用量记录总数={}，与成功扣减笔数一致，余额与记账同进同退", totalRecords);
    }

    @Test
    @DisplayName("两笔并发各扣 30 / 总配额 50：一笔成功，另一笔明确报配额不足")
    void oneSucceedsOneReportsInsufficient() throws Exception {
        long accountId = quotaService.createAccount("race-edge", 50).accountId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<QuotaErrorCode> failures = new ConcurrentLinkedQueue<>();
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < 2; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    quotaService.deduct(accountId, 30, "edge-" + idx, "");
                    success.incrementAndGet();
                } catch (QuotaServiceException e) {
                    failures.add(e.getErrorCode());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        log.info("[边界竞态] 成功={} 失败={} 最终余额={}",
                success.get(), failures, quotaService.getAccount(accountId).remaining());
        assertThat(success.get()).isEqualTo(1);
        assertThat(failures).containsOnly(QuotaErrorCode.INSUFFICIENT_QUOTA);
        assertThat(quotaService.getAccount(accountId).remaining()).isEqualTo(20L);
    }

    private long countAllUsage(long accountId) {
        long total = 0;
        String cursor = null;
        do {
            var page = quotaService.queryUsage(accountId, 0, Long.MAX_VALUE, 200, cursor);
            total += page.records().size();
            cursor = page.nextCursor();
        } while (cursor != null);
        return total;
    }
}
