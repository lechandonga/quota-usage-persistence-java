package com.github.lechandonga.quotausagepersistencejava.domain;

/**
 * 配额账户状态。停用后拒绝扣减，历史用量仍可查询；恢复后余额与历史记录保持不变。
 */
public enum AccountStatus {
    ACTIVE,
    SUSPENDED
}
