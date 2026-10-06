# 配额账户与用量记录持久化服务

基于 Spring Boot 3 + Spring JDBC + H2（本地嵌入式数据库）+ Flyway 的配额扣减与用量记账服务。
无需任何外部数据库服务即可启动与测试。

## 1. 数据模型

| 表 | 关键字段 | 说明 |
| --- | --- | --- |
| `quota_account` | `id`、`account_name`(唯一)、`status`(ACTIVE/DISABLED)、`total_quota`、`remaining`、`rollover_enabled`、`version` | 配额账户。配额与余额均为非负 `BIGINT`（最小业务整数单位）；CHECK 约束保证 `0 <= remaining <= total_quota` |
| `usage_record` | `id`、`account_id`(外键)、`amount`(>0)、`occurred_at`(epoch millis)、`biz_ref`、`note` | 用量记账记录。`occurred_at` 一经写入不可变 |
| `flyway_schema_history` | Flyway 自动维护 | 结构变更的可追溯记录 |

索引：`idx_usage_account_time_id (account_id, occurred_at, id)`，既是翻页查询的过滤索引，
也是其排序索引。

## 2. 并发与事务策略

扣减在**单个事务**内完成：`SELECT ... FOR UPDATE` 锁账户行 → 状态/余额判定 →
条件更新余额 → 插入用量记录。

```sql
UPDATE quota_account
SET remaining = remaining - ?, version = version + 1, updated_at = ?
WHERE id = ? AND version = ? AND status = 'ACTIVE' AND remaining >= ?
```

- `remaining >= ?` 由数据库保证**绝不超额、绝不出现负余额**；
- `version = ?` 保证并发事务最多一个成功，另一个更新 0 行，明确报
  `CONCURRENT_CONFLICT`，调用方可整体重试；
- 失败原因通过错误码显式区分：`INSUFFICIENT_QUOTA`（配额不足）、
  `CONCURRENT_CONFLICT`（并发冲突）、`ACCOUNT_DISABLED`（账户停用）、
  `ACCOUNT_NOT_FOUND`、`INVALID_ARGUMENT`；
- 任一步失败事务整体回滚，**余额与用量记录同进同退**，不会只落一半；
- 停用只改变账户状态，不删除任何数据，因此历史用量在停用期间仍可查询，恢复后余额与记录都在。

## 3. 游标分页

按 `(occurred_at ASC, id ASC)` 做 keyset pagination（seek 方法），游标为不透明 token：
`Base64Url("v1|<accountId>|<occurredAt>|<id>")`。

- **不重不漏**：排序键不可变。每页以末行排序键为锚点，用
  `(occurred_at > ? OR (occurred_at = ? AND id > ?))` 做索引 seek；
  翻页期间写入的、排序键晚于锚点的新记录会在后续页出现，早于锚点的“迟到行”不会被回放
  （回放才会造成重复）——边界确定且可解释；
- **非法游标明确报错**：Base64 损坏、字段缺失/非数字、版本不支持、跨账户复用均返回
  `INVALID_CURSOR`（HTTP 400）；
- **锚点不可见明确报错**：游标指向的记录被删除或落在查询时间窗外，返回
  `CURSOR_TARGET_INVISIBLE`（HTTP 404），服务会先校验锚点仍存在且可见，
  **不会从头或从中间继续**；
- **性能有界**：每页固定一条 SQL，取 `pageSize+1` 行判断是否有下一页，走复合索引 seek，
  与历史总量无关。测试通过 `QueryCountingDataSource` 统计 JDBC 往返、通过 `EXPLAIN`
  断言执行计划命中 `idx_usage_account_time_id`。

## 4. 结构演进与兼容规则

迁移脚本位于 `src/main/resources/db/migration/`，空库启动时 Flyway 顺序执行并记入
`flyway_schema_history`：

- `V1__init.sql`：建账户表、用量表与复合索引；
- `V2__add_rollover_and_note.sql`：**新增字段**。规则——新列必须 `NOT NULL` 且带确定默认值
  （`rollover_enabled DEFAULT FALSE`、`note DEFAULT ''`），历史行被显式回填，默认语义稳定；
- `V3__rename_and_drop_columns.sql`：**改名 + 删除**。改名用 `ALTER ... RENAME`
  （`business_ref → biz_ref`），删除用 `ALTER ... DROP COLUMN`（废弃列 `label`）。
  所有读写 SQL **显式列出列名、按列名取值**，不使用 `SELECT *`，因此改名/删除不会静默错列；
  旧结构状态永久保存在迁移历史中，可追溯。

## 5. HTTP 接口

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/quota/accounts` | 创建账户 `{accountName, totalQuota}` |
| `GET  /api/quota/accounts/{id}` | 查询账户 |
| `POST /api/quota/accounts/{id}/deduct` | 扣减记账 `{amount, bizRef, note}` |
| `POST /api/quota/accounts/{id}/disable` / `.../enable` | 停用 / 恢复 |
| `GET  /api/quota/accounts/{id}/usage?from=&to=&pageSize=&cursor=` | 游标分页查用量 |

错误响应统一为 `{"code":"错误码","message":"..."}`。

## 6. 本地验证

要求 JDK 17+、Maven 3.6+（无需安装数据库）。

```bash
mvn test
```

测试使用 H2 内存库，共 12 个用例，按业务分为 5 组（类名上的 `@DisplayName` 即业务名）：

| 测试类 | 覆盖业务 |
| --- | --- |
| `ConcurrentDeductionTest` | 200 线程并发扣减不超额/不负余额、失败码分布为配额不足、余额与记账一致；边界竞态 30+30/50 |
| `CursorPaginationTest` | 237 条 5 页不重不漏、翻页中并发写入、非法游标三类、锚点不可见两类、每页 1 条索引 SQL 与 EXPLAIN 验证 |
| `AccountLifecycleTest` | 停用拒绝扣减但历史可查、恢复后数据不丢、非法参数/未知账户/重名、失败事务整体回滚 |
| `SchemaEvolutionTest` | 独立文件库：V1 历史数据升级到最新版本后默认值/改名/删列正确、迁移历史可追溯；空库一次建出完整结构 |
| `ApplicationTests` | 空库迁移后上下文装配成功 |

控制台日志按业务打印（如 `[并发扣减]`、`[游标翻页]`、`[停用恢复]`、`[结构升级]`），
可直接看出覆盖了哪些业务及对应用例结果；完整输出也在 `target/surefire-reports/`。

手工启动（H2 文件落在 `./data/quota-db`）：

```bash
mvn spring-boot:run
curl -X POST localhost:8080/api/quota/accounts \
  -H 'Content-Type: application/json' -d '{"accountName":"demo","totalQuota":100}'
```
