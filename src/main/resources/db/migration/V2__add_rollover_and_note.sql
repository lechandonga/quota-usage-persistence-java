-- V2: 结构演进示例（新增字段）。
-- 规则：新增列必须 NOT NULL 且给出确定、稳定的默认值，历史行由此被显式回填。

-- 是否允许跨周期滚动（V2 引入），历史数据统一语义为 false。
ALTER TABLE quota_account ADD COLUMN rollover_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- 账户标签（V2 引入），历史数据统一语义为空字符串，避免 NULL 带来的二义性。
ALTER TABLE quota_account ADD COLUMN label VARCHAR(256) NOT NULL DEFAULT '';

-- 用量记录的备注（V2 引入），历史数据统一语义为空字符串。
ALTER TABLE usage_record ADD COLUMN note VARCHAR(512) NOT NULL DEFAULT '';
