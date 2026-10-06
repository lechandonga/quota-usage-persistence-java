package com.github.lechandonga.quotausagepersistencejava.service;

import com.github.lechandonga.quotausagepersistencejava.domain.AccountStatus;
import com.github.lechandonga.quotausagepersistencejava.domain.QuotaAccount;
import com.github.lechandonga.quotausagepersistencejava.domain.UsageRecord;
import com.github.lechandonga.quotausagepersistencejava.repository.QuotaAccountRepository;
import com.github.lechandonga.quotausagepersistencejava.repository.UsageRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 配额与用量领域服务。
 *
 * <p>并发扣减策略：行锁 ({@code SELECT ... FOR UPDATE}) + 版本号乐观条件，
 * 在一个事务内完成“读账户 → 判定 → 扣余额 → 写用量”。
 * 事务回滚时账户余额与用量记录同时回滚，不可能只落一半。</p>
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    static final int MAX_PAGE_SIZE = 200;

    private final QuotaAccountRepository accountRepository;
    private final UsageRecordRepository usageRecordRepository;

    public QuotaService(QuotaAccountRepository accountRepository,
                        UsageRecordRepository usageRecordRepository) {
        this.accountRepository = accountRepository;
        this.usageRecordRepository = usageRecordRepository;
    }

    public record CreateAccountResult(long accountId, String accountName, long totalQuota) {
    }

    public record DeductResult(long accountId, long remainingAfter, long usageRecordId) {
    }

    @Transactional
    public CreateAccountResult createAccount(String accountName, long totalQuota) {
        if (accountName == null || accountName.isBlank()) {
            throw new QuotaServiceException(QuotaErrorCode.INVALID_ARGUMENT,
                    "账户名不能为空");
        }
        if (totalQuota < 0) {
            throw new QuotaServiceException(QuotaErrorCode.INVALID_ARGUMENT,
                    "总配额必须为非负整数");
        }
        long now = System.currentTimeMillis();
        try {
            long id = accountRepository.insert(accountName.trim(), totalQuota, now);
            log.info("创建配额账户成功 accountId={} name={} totalQuota={}", id, accountName, totalQuota);
            return new CreateAccountResult(id, accountName.trim(), totalQuota);
        } catch (DuplicateKeyException e) {
            throw new QuotaServiceException(QuotaErrorCode.ACCOUNT_NAME_DUPLICATED,
                    "账户名已存在: " + accountName);
        }
    }

    public QuotaAccount getAccount(long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new QuotaServiceException(QuotaErrorCode.ACCOUNT_NOT_FOUND,
                        "账户不存在: " + accountId));
    }

    /**
     * 扣减并记账。失败通过错误码明确区分：
     * <ul>
     *   <li>{@code INVALID_ARGUMENT}：amount 非正数；</li>
     *   <li>{@code ACCOUNT_NOT_FOUND}：账户不存在；</li>
     *   <li>{@code ACCOUNT_DISABLED}：账户已停用；</li>
     *   <li>{@code INSUFFICIENT_QUOTA}：余额不足；</li>
     *   <li>{@code CONCURRENT_CONFLICT}：并发事务已先行扣减，调用方可重试整个事务。</li>
     * </ul>
     */
    @Transactional
    public DeductResult deduct(long accountId, long amount, String bizRef, String note) {
        if (amount <= 0) {
            throw new QuotaServiceException(QuotaErrorCode.INVALID_ARGUMENT,
                    "扣减数量必须为正整数");
        }
        if (bizRef == null || bizRef.isBlank()) {
            throw new QuotaServiceException(QuotaErrorCode.INVALID_ARGUMENT,
                    "业务单号 bizRef 不能为空");
        }

        QuotaAccount account;
        try {
            account = accountRepository.lockById(accountId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new QuotaServiceException(QuotaErrorCode.ACCOUNT_NOT_FOUND,
                    "账户不存在: " + accountId);
        }

        if (account.status() == AccountStatus.DISABLED) {
            throw new QuotaServiceException(QuotaErrorCode.ACCOUNT_DISABLED,
                    "账户已停用，禁止扣减: " + accountId);
        }
        if (account.remaining() < amount) {
            throw new QuotaServiceException(QuotaErrorCode.INSUFFICIENT_QUOTA,
                    String.format("配额不足: requested=%d, remaining=%d", amount, account.remaining()));
        }

        long now = System.currentTimeMillis();
        int updated = accountRepository.deduct(account, amount, now);
        if (updated == 0) {
            // 行锁内版本号仍变化，只可能来自并发的状态/版本变更，明确归为并发冲突而非静默重试。
            throw new QuotaServiceException(QuotaErrorCode.CONCURRENT_CONFLICT,
                    "并发扣减冲突，请重试: accountId=" + accountId);
        }

        // 事件时间取事务当前时刻；与余额更新在同一事务中写入。
        long recordId = usageRecordRepository.insert(accountId, amount, now, bizRef, note);
        long remainingAfter = account.remaining() - amount;
        log.info("扣减记账成功 accountId={} amount={} remainingAfter={} bizRef={}",
                accountId, amount, remainingAfter, bizRef);

        return new DeductResult(accountId, remainingAfter, recordId);
    }

    @Transactional
    public void disableAccount(long accountId) {
        ensureExists(accountId);
        accountRepository.updateStatus(accountId, AccountStatus.DISABLED, System.currentTimeMillis());
        log.info("账户已停用 accountId={}", accountId);
    }

    @Transactional
    public void enableAccount(long accountId) {
        ensureExists(accountId);
        accountRepository.updateStatus(accountId, AccountStatus.ACTIVE, System.currentTimeMillis());
        log.info("账户已恢复 accountId={}", accountId);
    }

    private void ensureExists(long accountId) {
        if (accountRepository.findById(accountId).isEmpty()) {
            throw new QuotaServiceException(QuotaErrorCode.ACCOUNT_NOT_FOUND,
                    "账户不存在: " + accountId);
        }
    }

    /**
     * 游标分页查询用量记录。
     *
     * @param pageSize 每页条数，1..{@link #MAX_PAGE_SIZE}
     * @param cursor   上一页返回的不透明游标；{@code null} 表示首页
     */
    public Page queryUsage(long accountId, long fromInclusive, long toExclusive,
                           Integer pageSize, String cursor) {
        if (fromInclusive < 0 || toExclusive <= fromInclusive) {
            throw new QuotaServiceException(QuotaErrorCode.INVALID_ARGUMENT,
                    "时间范围非法：要求 0 <= from < to");
        }
        int limit = pageSize == null ? 50 : pageSize;
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new QuotaServiceException(QuotaErrorCode.INVALID_ARGUMENT,
                    "pageSize 必须在 1 与 " + MAX_PAGE_SIZE + " 之间");
        }

        List<UsageRecord> rows;
        if (cursor == null || cursor.isBlank()) {
            rows = usageRecordRepository.findPage(
                    accountId, fromInclusive, toExclusive, limit + 1);
        } else {
            CursorCodec.Cursor decoded = CursorCodec.decode(cursor);
            if (decoded.accountId() != accountId) {
                throw new QuotaServiceException(QuotaErrorCode.INVALID_CURSOR,
                        "非法游标：游标不属于该账户");
            }
            if (decoded.occurredAt() < fromInclusive || decoded.occurredAt() >= toExclusive) {
                throw new QuotaServiceException(QuotaErrorCode.CURSOR_TARGET_INVISIBLE,
                        "游标指向的记录不在当前查询时间窗内");
            }
            // 不允许从头或从中间继续：必须验证游标锚点仍然存在且可见。
            boolean visible = usageRecordRepository.isVisible(
                    accountId, decoded.id(), decoded.occurredAt(), fromInclusive, toExclusive);
            if (!visible) {
                throw new QuotaServiceException(QuotaErrorCode.CURSOR_TARGET_INVISIBLE,
                        "游标指向的记录已不可见（被删除或不属于当前查询范围）");
            }
            rows = usageRecordRepository.findPageAfter(
                    accountId, fromInclusive, toExclusive,
                    decoded.occurredAt(), decoded.id(), limit + 1);
        }

        boolean hasMore = rows.size() > limit;
        List<UsageRecord> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            UsageRecord last = page.get(page.size() - 1);
            nextCursor = CursorCodec.encode(accountId, last.occurredAt(), last.id());
        }
        return new Page(List.copyOf(page), nextCursor, hasMore);
    }
}
