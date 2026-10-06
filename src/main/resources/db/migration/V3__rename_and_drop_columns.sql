-- V3: 结构演进示例（改名 + 删除）。
-- 规则：
--   改名通过显式 ALTER ... RENAME 完成，迁移记录可追溯；
--   所有读取均使用显式列名，绝不依赖 SELECT * 的列顺序，因此不会静默错列；
--   列删除前确认无代码引用，删除后旧列彻底不可见，启动不会失败。

-- 改名：business_ref -> biz_ref。
ALTER TABLE usage_record ALTER COLUMN business_ref RENAME TO biz_ref;

-- 删除 V2 引入后即被废弃的列 label（需求变更后改由 note 承载）。
-- 删除前的结构状态永久保存在 flyway_schema_history 中，可追溯。
ALTER TABLE quota_account DROP COLUMN label;
