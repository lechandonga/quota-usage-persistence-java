-- V2: 结构演进示例 —— 新增字段。
-- quota_type 对历史数据必须有确定且稳定的默认语义：历史账户一律视为 GENERAL（通用配额）。
-- legacy_channel 为本次引入、并计划在 V3 删除的过渡字段（删除字段的演进路径）。

ALTER TABLE quota_account
    ADD COLUMN quota_type VARCHAR(32) NOT NULL DEFAULT 'GENERAL';

ALTER TABLE quota_account
    ADD COLUMN legacy_channel VARCHAR(32) NOT NULL DEFAULT 'DEFAULT';

-- 用量记录新增业务来源描述，历史记录确定为 NONE（无来源说明）。
ALTER TABLE usage_record
    ADD COLUMN description VARCHAR(256) NOT NULL DEFAULT 'NONE';

-- 回填历史行，使默认语义物化、稳定，不依赖读取时的隐式默认值。
UPDATE quota_account SET quota_type = 'GENERAL' WHERE quota_type IS NULL OR quota_type = '';
UPDATE quota_account SET legacy_channel = 'DEFAULT' WHERE legacy_channel IS NULL OR legacy_channel = '';
UPDATE usage_record SET description = 'NONE' WHERE description IS NULL OR description = '';
