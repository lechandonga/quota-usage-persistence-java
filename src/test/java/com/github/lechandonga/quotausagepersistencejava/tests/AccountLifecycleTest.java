package com.github.lechandonga.quotausagepersistencejava.tests;

import com.github.lechandonga.quotausagepersistencejava.domain.AccountStatus;
import com.github.lechandonga.quotausagepersistencejava.service.QuotaErrorCode;
import com.github.lechandonga.quotausagepersistencejava.service.QuotaService;
import com.github.lechandonga.quotausagepersistencejava.service.QuotaServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 业务：账户停用/恢复与记账原子性。
 * 用例：
 * 1) 停用后扣减被拒绝（ACCOUNT_DISABLED），历史用量仍可查询；
 * 2) 恢复后扣减成功，停用前后数据（余额、用量）都不丢；
 * 3) 非法参数（0/负数扣减、空业务单）报 INVALID_ARGUMENT；
 * 4) 不存在账户报 ACCOUNT_NOT_FOUND，重名创建报 ACCOUNT_NAME_DUPLICATED；
 * 5) 扣减失败时余额与用量记录数量都不变（同事务回滚）。
 */
@SpringBootTest
@DisplayName("业务-停用恢复与记账原子性")
class AccountLifecycleTest {

    private static final Logger log = LoggerFactory.getLogger(AccountLifecycleTest.class);

    @Autowired
    private QuotaService quotaService;

    @Test
    @DisplayName("停用拒绝扣减但历史可查；恢复后继续扣减，数据不丢")
    void disableRejectsDeductionButKeepsHistory() {
        long accountId = quotaService.createAccount("lifecycle", 100).accountId();
        quotaService.deduct(accountId, 30, "biz-1", "first");

        quotaService.disableAccount(accountId);
        assertThat(quotaService.getAccount(accountId).status()).isEqualTo(AccountStatus.DISABLED);

        assertThatThrownBy(() -> quotaService.deduct(accountId, 10, "biz-2", ""))
                .isInstanceOf(QuotaServiceException.class)
                .extracting(e -> ((QuotaServiceException) e).getErrorCode())
                .isEqualTo(QuotaErrorCode.ACCOUNT_DISABLED);

        var historyWhileDisabled =
                quotaService.queryUsage(accountId, 0, Long.MAX_VALUE, 50, null);
        assertThat(historyWhileDisabled.records()).hasSize(1);
        assertThat(quotaService.getAccount(accountId).remaining()).isEqualTo(70L);
        log.info("[停用恢复] 停用期间扣减被拒，历史用量 {} 条仍可查，余额保持 70",
                historyWhileDisabled.records().size());

        quotaService.enableAccount(accountId);
        assertThat(quotaService.getAccount(accountId).status()).isEqualTo(AccountStatus.ACTIVE);
        quotaService.deduct(accountId, 20, "biz-3", "after-enable");
        assertThat(quotaService.getAccount(accountId).remaining()).isEqualTo(50L);
        assertThat(quotaService.queryUsage(accountId, 0, Long.MAX_VALUE, 50, null).records())
                .hasSize(2);
        log.info("[停用恢复] 恢复后扣减成功，余额 50，累计用量 2 条，数据未丢失");
    }

    @Test
    @DisplayName("非法扣减参数与不存在/重名账户：错误码明确")
    void invalidArgumentsAndMissingAccounts() {
        long accountId = quotaService.createAccount("uniq-name", 10).accountId();

        assertThatThrownBy(() -> quotaService.deduct(accountId, 0, "biz", ""))
                .extracting(e -> ((QuotaServiceException) e).getErrorCode())
                .isEqualTo(QuotaErrorCode.INVALID_ARGUMENT);
        assertThatThrownBy(() -> quotaService.deduct(accountId, -5, "biz", ""))
                .extracting(e -> ((QuotaServiceException) e).getErrorCode())
                .isEqualTo(QuotaErrorCode.INVALID_ARGUMENT);
        assertThatThrownBy(() -> quotaService.deduct(accountId, 1, " ", ""))
                .extracting(e -> ((QuotaServiceException) e).getErrorCode())
                .isEqualTo(QuotaErrorCode.INVALID_ARGUMENT);
        assertThatThrownBy(() -> quotaService.deduct(987_654_321L, 1, "biz", ""))
                .extracting(e -> ((QuotaServiceException) e).getErrorCode())
                .isEqualTo(QuotaErrorCode.ACCOUNT_NOT_FOUND);
        assertThatThrownBy(() -> quotaService.createAccount("uniq-name", 1))
                .extracting(e -> ((QuotaServiceException) e).getErrorCode())
                .isEqualTo(QuotaErrorCode.ACCOUNT_NAME_DUPLICATED);
        assertThatThrownBy(() -> quotaService.createAccount("neg", -1))
                .extracting(e -> ((QuotaServiceException) e).getErrorCode())
                .isEqualTo(QuotaErrorCode.INVALID_ARGUMENT);
        log.info("[参数校验] 非法扣减、空业务单、未知账户、重名账户均返回明确错误码");
    }

    @Test
    @DisplayName("配额不足时事务整体回滚：余额与用量记录数都不变")
    void insufficientQuotaRollsBackBalanceAndUsage() {
        long accountId = quotaService.createAccount("atomic-fail", 10).accountId();
        quotaService.deduct(accountId, 4, "ok-1", "");

        assertThatThrownBy(() -> quotaService.deduct(accountId, 20, "fail-1", ""))
                .extracting(e -> ((QuotaServiceException) e).getErrorCode())
                .isEqualTo(QuotaErrorCode.INSUFFICIENT_QUOTA);

        assertThat(quotaService.getAccount(accountId).remaining()).isEqualTo(6L);
        assertThat(quotaService.queryUsage(accountId, 0, Long.MAX_VALUE, 50, null).records())
                .singleElement()
                .extracting("bizRef").isEqualTo("ok-1");
        log.info("[事务原子性] 超额扣减失败后余额仍为 6，用量仍只有 1 条，未落半成品数据");
    }
}
