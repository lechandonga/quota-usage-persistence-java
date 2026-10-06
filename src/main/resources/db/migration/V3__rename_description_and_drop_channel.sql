-- V3: 结构演进示例 —— 字段改名与删除。
-- description 改名为 note：通过 Flyway 记录的显式 RENAME 完成；
-- 代码全部使用列名访问，改名后旧列名不再存在，误用会在启动/执行期直接报错，而不是静默错列。
-- legacy_channel 完成过渡后删除：历史数据由应用层忽略该维度，语义明确（渠道维度废弃）。

ALTER TABLE usage_record ALTER COLUMN description RENAME TO note;

ALTER TABLE quota_account DROP COLUMN legacy_channel;
