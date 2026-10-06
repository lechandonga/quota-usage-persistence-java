-- V1: 初始结构。从空库即可建出完整的配额账户与用量记录表。
-- 所有读写 SQL 都显式列出列名，不依赖物理列顺序，后续改名/删列不会导致静默错列。

CREATE TABLE quota_account (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    account_no      VARCHAR(64)  NOT NULL,
    balance         BIGINT       NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    version         BIGINT       NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    updated_at      TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_quota_account PRIMARY KEY (id),
    CONSTRAINT uq_quota_account_no UNIQUE (account_no),
    CONSTRAINT ck_quota_account_balance_nonneg CHECK (balance >= 0),
    CONSTRAINT ck_quota_account_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE TABLE usage_record (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    account_id      BIGINT       NOT NULL,
    account_no      VARCHAR(64)  NOT NULL,
    amount          BIGINT       NOT NULL,
    occurred_at     TIMESTAMP(6) NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_usage_record PRIMARY KEY (id),
    CONSTRAINT fk_usage_account FOREIGN KEY (account_id) REFERENCES quota_account (id),
    CONSTRAINT ck_usage_amount_positive CHECK (amount > 0)
);

-- 游标翻页的键集查询：WHERE account_id = ? AND occurred_at >= ? AND (...)
-- ORDER BY occurred_at, id，全部走此复合索引，不随数据量退化为按条查库。
CREATE INDEX idx_usage_account_time
    ON usage_record (account_id, occurred_at, id);

-- 按账户号查询用量（对外以 account_no 为稳定标识）。
CREATE INDEX idx_usage_account_no_time
    ON usage_record (account_no, occurred_at, id);
