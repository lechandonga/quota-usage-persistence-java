package com.github.lechandonga.quotausagepersistencejava.service;

/**
 * 扣减失败的三类可区分原因。
 */
public enum DeductionFailureKind {
    /** 账户不存在。 */
    ACCOUNT_NOT_FOUND,
    /** 账户已停用，状态不允许扣减。 */
    ACCOUNT_SUSPENDED,
    /** 余额不足，若执行会导致负余额。 */
    INSUFFICIENT_QUOTA,
    /** 与并发事务冲突（余额或版本已被其他事务抢先修改）。 */
    CONCURRENT_CONFLICT
}
