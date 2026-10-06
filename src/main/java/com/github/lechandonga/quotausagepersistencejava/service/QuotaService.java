package com.github.lechandonga.quotausagepersistencejava.service;

import com.github.lechandonga.quotausagepersistencejava.domain.AccountStatus;
import com.github.lechandonga.quotausagepersistencejava.domain.QuotaAccount;
import com.github.lechandonga.quotausagepersistencejava.domain.UsagePage;
import com.github.lechandonga.quotausagepersistencejava.domain.UsageRecord;
import com.github.lechandonga.quotausagepersistencejava.repo.QuotaRepository;
import com.github.lechandonga.quotausagepersistencejava.service.CursorCodec.CursorAnchor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 配额与用量领域服务。
 *
 * 并发策略：读当前余额与版本 → 单条条件 UPDATE（状态 ACTIVE、版本未变、余额充足）完成原子 CAS；
 * 失败时在同一事务内重读，区分“停用 / 不足 / 并发冲突”。不做服务端静默重试，
 * CONCURRENT_CONFLICT 交给调用方决策，保证结果可解释。
 */
@Service
public class QuotaService {

    private static final int MAX_PAGE_SIZE = 500;

    private final QuotaRepository repository;
    private final CursorCodec cursorCodec;

    public QuotaService(QuotaRepository repository, CursorCodec cursorCodec) {
        this.repository = repository;
        this.cursorCodec = cursorCodec;
    }

    @Transactional
    public QuotaAccount createAccount(String accountNo, long initialBalance, String quotaType) {
        if (accountNo == null || accountNo.isBlank()) {
            throw new IllegalArgumentException("accountNo 不能为空");
        }
        if (initialBalance < 0) {
            throw new IllegalArgumentException("初始余额不能为负数");
        }
        Instant now = nowMicros();
        try {
            long id = repository.insertAccount(accountNo, initialBalance,
                    quotaType == null || quotaType.isBlank() ? "GENERAL" : quotaType, now);
            return repository.findAccountByNo(accountNo).orElseThrow();
        } catch (DuplicateKeyException e) {
            throw new AccountAlreadyExistsException("账户已存在: " + accountNo);
        }
    }

    public QuotaAccount getAccount(String accountNo) {
        return repository.findAccountByNo(accountNo)
                .orElseThrow(() -> new DeductionException(DeductionFailureKind.ACCOUNT_NOT_FOUND,
                        "账户不存在: " + accountNo));
    }

    /**
     * 扣减并记账：余额条件更新与用量插入在同一事务中，一起成功或一起回滚。
     */
    @Transactional
    public UsageRecord debit(String accountNo, long amount, String note) {
        if (amount <= 0) {
            throw new IllegalArgumentException("扣减数量必须为正数");
        }
        QuotaAccount account = getAccount(accountNo);
        if (account.status() == AccountStatus.SUSPENDED) {
            throw new DeductionException(DeductionFailureKind.ACCOUNT_SUSPENDED,
                    "账户已停用，拒绝扣减: " + accountNo);
        }
        if (account.balance() < amount) {
            throw new DeductionException(DeductionFailureKind.INSUFFICIENT_QUOTA,
                    "配额不足: 余额=" + account.balance() + ", 请求=" + amount);
        }

        Instant occurredAt = nowMicros();
        int updated = repository.conditionalDebit(account.id(), amount, account.version(), occurredAt);
        if (updated == 0) {
            throwClassifiedConflict(account, amount);
        }

        long usageId = repository.insertUsage(account, amount, occurredAt, note);
        return new UsageRecord(usageId, account.id(), account.accountNo(), amount,
                occurredAt, note == null ? "NONE" : note, occurredAt);
    }

    private void throwClassifiedConflict(QuotaAccount snapshot, long amount) {
        QuotaAccount latest = repository.findAccountByNo(snapshot.accountNo())
                .orElseThrow(() -> new DeductionException(DeductionFailureKind.ACCOUNT_NOT_FOUND,
                        "账户在扣减过程中消失: " + snapshot.accountNo()));
        if (latest.status() == AccountStatus.SUSPENDED) {
            throw new DeductionException(DeductionFailureKind.ACCOUNT_SUSPENDED,
                    "账户在并发扣减期间被停用: " + snapshot.accountNo());
        }
        if (latest.balance() < amount) {
            throw new DeductionException(DeductionFailureKind.INSUFFICIENT_QUOTA,
                    "并发扣减后配额不足: 余额=" + latest.balance() + ", 请求=" + amount);
        }
        throw new DeductionException(DeductionFailureKind.CONCURRENT_CONFLICT,
                "账户被并发事务抢先修改，请重试: " + snapshot.accountNo()
                        + "（读到版本=" + snapshot.version() + ", 当前版本=" + latest.version() + "）");
    }

    @Transactional
    public QuotaAccount suspend(String accountNo) {
        return changeStatus(accountNo, AccountStatus.SUSPENDED);
    }

    @Transactional
    public QuotaAccount resume(String accountNo) {
        return changeStatus(accountNo, AccountStatus.ACTIVE);
    }

    private QuotaAccount changeStatus(String accountNo, AccountStatus target) {
        QuotaAccount account = getAccount(accountNo);
        if (account.status() == target) {
            return account;
        }
        int updated = repository.updateStatus(account.id(), target, account.version(), nowMicros());
        if (updated == 0) {
            throw new DeductionException(DeductionFailureKind.CONCURRENT_CONFLICT,
                    "状态变更与并发事务冲突，请重试: " + accountNo);
        }
        return getAccount(accountNo);
    }

    /**
     游标翻页查询。首页 cursor 传 null；续页时先验签、再校验锚点对当前账户与时间窗口可见，
     * 任何不一致都明确报错，绝不静默地从头或从中间继续。
     */
    @Transactional(readOnly = true)
    public UsagePage queryUsage(String accountNo,
                                Instant fromInclusive,
                                Instant toExclusive,
                                String cursor,
                                int requestedLimit) {
        if (requestedLimit <= 0 || requestedLimit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit 必须在 1 到 " + MAX_PAGE_SIZE + " 之间");
        }
        if (fromInclusive != null && toExclusive != null && !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("时间范围非法：from 必须早于 to");
        }
        QuotaAccount account = getAccount(accountNo);

        Instant anchorTime = null;
        Long anchorId = null;
        if (cursor != null && !cursor.isBlank()) {
            CursorAnchor anchor = cursorCodec.decode(cursor);
            if (!account.accountNo().equals(anchor.accountNo())) {
                throw new CursorException("游标不属于请求的账户，不能跨账户续页");
            }
            boolean visible = repository.isAnchorVisible(account.accountNo(), anchor.recordId(),
                    anchor.occurredAt(), fromInclusive, toExclusive);
            if (!visible) {
                throw new CursorException("游标指向的记录在当前查询条件下不存在或不可见，"
                        + "请放弃该游标并从首页重新查询");
            }
            anchorTime = anchor.occurredAt();
            anchorId = anchor.recordId();
        }

        List<UsageRecord> rows = repository.seekUsage(account.id(), fromInclusive, toExclusive,
                anchorTime, anchorId, requestedLimit + 1);
        boolean hasMore = rows.size() > requestedLimit;
        if (hasMore) {
            rows = rows.subList(0, requestedLimit);
        }
        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            UsageRecord last = rows.get(rows.size() - 1);
            nextCursor = cursorCodec.encode(account.accountNo(), last.occurredAt(), last.id());
        }
        return new UsagePage(rows, nextCursor, hasMore);
    }

    static Instant nowMicros() {
        Instant now = Instant.now();
        return Instant.ofEpochSecond(now.getEpochSecond(), (now.getNano() / 1000) * 1000);
    }

    public static class AccountAlreadyExistsException extends RuntimeException {
        public AccountAlreadyExistsException(String message) {
            super(message);
        }
    }
}
