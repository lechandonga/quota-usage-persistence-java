package com.github.lechandonga.quotausagepersistencejava.tests;

import com.github.lechandonga.quotausagepersistencejava.domain.AccountStatus;
import com.github.lechandonga.quotausagepersistencejava.domain.QuotaAccount;
import com.github.lechandonga.quotausagepersistencejava.service.DeductionException;
import com.github.lechandonga.quotausagepersistencejava.service.DeductionFailureKind;
import com.github.lechandonga.quotausagepersistencejava.service.QuotaService;
import com.github.lechandonga.quotausagepersistencejava.tests.support.BusinessCoverage;
import com.github.lechandonga.quotausagepersistencejava.tests.support.CoverageTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 账户停用/恢复场景：停用拒绝扣减但历史可查，恢复后余额与历史不丢。
 */
@CoverageTest
class AccountLifecycleTest {

    private final QuotaService quotaService;

    AccountLifecycleTest(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @Test
    @BusinessCoverage(module = "停用与恢复", caseDescription = "停用后扣减被拒（ACCOUNT_SUSPENDED），历史用量仍可查询")
    void suspendedAccountRejectsDebitButKeepsHistory() {
        String accountNo = "lc-" + UUID.randomUUID();
        quotaService.createAccount(accountNo, 100, null);
        quotaService.debit(accountNo, 30, "before-suspend");

        QuotaAccount suspended = quotaService.suspend(accountNo);
        assertEquals(AccountStatus.SUSPENDED, suspended.status());

        DeductionException ex = assertThrows(DeductionException.class,
                () -> quotaService.debit(accountNo, 10, null));
        assertEquals(DeductionFailureKind.ACCOUNT_SUSPENDED, ex.getKind());

        assertEquals(70L, quotaService.getAccount(accountNo).balance(), "停用期间余额不变");
        assertEquals(1, quotaService.queryUsage(accountNo, null, null, null, 50).records().size(),
                "停用后历史用量仍可查询");
    }

    @Test
    @BusinessCoverage(module = "停用与恢复", caseDescription = "恢复后余额与历史记录完整保留，可继续扣减，版本持续递增")
    void resumeRestoresDataAndAllowsDebit() {
        String accountNo = "lc-resume-" + UUID.randomUUID();
        quotaService.createAccount(accountNo, 100, null);
        quotaService.debit(accountNo, 40, "history-1");
        quotaService.suspend(accountNo);

        QuotaAccount resumed = quotaService.resume(accountNo);
        assertEquals(AccountStatus.ACTIVE, resumed.status());
        assertEquals(60L, resumed.balance(), "恢复后余额不丢");

        quotaService.debit(accountNo, 20, "after-resume");
        QuotaAccount finalAccount = quotaService.getAccount(accountNo);
        assertEquals(40L, finalAccount.balance(), "恢复后扣减正常生效");
        assertEquals(2, quotaService.queryUsage(accountNo, null, null, null, 50).records().size(),
                "停用前后的用量记录都保留");

        long versionAfterResumeDebit = finalAccount.version();
        quotaService.suspend(accountNo);
        quotaService.resume(accountNo);
        assertEquals(versionAfterResumeDebit + 2, quotaService.getAccount(accountNo).version(),
                "每次状态变更都推进乐观锁版本");
    }

    @Test
    @BusinessCoverage(module = "停用与恢复", caseDescription = "重复停用/恢复幂等：状态不变时直接返回当前账户")
    void idempotentStatusChange() {
        String accountNo = "lc-idem-" + UUID.randomUUID();
        quotaService.createAccount(accountNo, 10, null);
        long version = quotaService.getAccount(accountNo).version();
        quotaService.resume(accountNo);
        assertEquals(version, quotaService.getAccount(accountNo).version(), "重复恢复不产生新版本");
        quotaService.suspend(accountNo);
        long suspendedVersion = quotaService.getAccount(accountNo).version();
        quotaService.suspend(accountNo);
        assertEquals(suspendedVersion, quotaService.getAccount(accountNo).version(), "重复停用不产生新版本");
    }

    @Test
    @BusinessCoverage(module = "停用与恢复", caseDescription = "重复创建账户号被拒（ACCOUNT_ALREADY_EXISTS），且不影响既有账户")
    void duplicateAccountRejected() {
        String accountNo = "lc-dup-" + UUID.randomUUID();
        quotaService.createAccount(accountNo, 100, null);
        assertThrows(QuotaService.AccountAlreadyExistsException.class,
                () -> quotaService.createAccount(accountNo, 5, null));
        assertEquals(100L, quotaService.getAccount(accountNo).balance(), "重复创建不得覆盖已有账户");
    }
}
