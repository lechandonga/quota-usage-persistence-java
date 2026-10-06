package com.github.lechandonga.quotausagepersistencejava.tests;

import com.github.lechandonga.quotausagepersistencejava.service.QuotaService;
import com.github.lechandonga.quotausagepersistencejava.tests.support.BusinessCoverage;
import com.github.lechandonga.quotausagepersistencejava.tests.support.CoverageTest;
import com.github.lechandonga.quotausagepersistencejava.tests.support.FailingQuotaService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 事务原子性：余额更新与用量插入同生共死。
 */
@CoverageTest
class TransactionAtomicityTest {

    private final QuotaService quotaService;
    private final FailingQuotaService failingQuotaService;

    TransactionAtomicityTest(QuotaService quotaService, FailingQuotaService failingQuotaService) {
        this.quotaService = quotaService;
        this.failingQuotaService = failingQuotaService;
    }

    @Test
    @BusinessCoverage(module = "事务原子性", caseDescription = "扣减记账后步骤抛异常：余额不被扣掉，用量记录不落库，整笔回滚")
    void debitAndUsageRollBackTogether() {
        String accountNo = "tx-" + UUID.randomUUID();
        quotaService.createAccount(accountNo, 100, null);

        assertThrows(IllegalStateException.class,
                () -> failingQuotaService.debitThenFail(accountNo, 30));

        assertEquals(100L, quotaService.getAccount(accountNo).balance(), "失败后余额必须回滚");
        assertEquals(0L, quotaService.getAccount(accountNo).version(), "失败后乐观锁版本必须回滚");
        assertEquals(0, quotaService.queryUsage(accountNo, null, null, null, 50).records().size(),
                "失败后不能留下只写了一半的用量记录");

        quotaService.debit(accountNo, 30, "after-rollback");
        assertEquals(70L, quotaService.getAccount(accountNo).balance(), "回滚后账户仍可正常扣减");
        assertEquals(1, quotaService.queryUsage(accountNo, null, null, null, 50).records().size());
    }
}
