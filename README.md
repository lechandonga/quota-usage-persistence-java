# 配额账户与用量记录持久化服务

基于 Spring Boot 3.4 / Java 21 的配额账户（创建、扣减、停用/恢复）与用量记录
（游标分页查询）持久化服务。使用 **H2 嵌入式数据库**，本地零外部依赖即可运行；
表结构由 **Flyway** 管理，从空库自动建表并逐版本演进。

## 快速开始

```bash
# 运行全部测试（内存 H2，不依赖任何外部服务）
mvn test

# 启动应用（文件 H2，数据落在 ./data/quota-db）
mvn spring-boot:run

# 打包后运行
mvn package -DskipTests
java -jar target/quota-usage-persistence-java-1.0-SNAPSHOT.jar
```

启动后：
- REST 基址：`http://localhost:8080/api/quota`
- H2 控制台：`http://localhost:8080/h2-console`
  （JDBC URL 填 `jdbc:h2:file:./data/quota-db`，用户名 `sa`，密码空）

### HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/quota/accounts` | 创建账户：`{"accountNo":"a1","initialBalance":1000,"quotaType":"GENERAL"}` |
| GET  | `/api/quota/accounts/{accountNo}` | 查询账户（余额/状态/版本） |
| POST | `/api/quota/accounts/{accountNo}/debit` | 扣减并记账：`{"amount":300,"note":"..."}` |
| POST | `/api/quota/accounts/{accountNo}/suspend` | 停用账户 |
| POST | `/api/quota/accounts/{accountNo}/resume` | 恢复账户 |
| GET  | `/api/quota/accounts/{accountNo}/usage?from=&to=&cursor=&limit=` | 游标分页查询用量 |

查询参数：
- `from` / `to`：ISO-8601 时间，均可空；语义为 **左闭右开** `[from, to)`。
- `limit`：每页条数，1–500，默认 50。
- `cursor`：首页不传；续页传上一页响应中的 `nextCursor`。

扣减失败响应体包含可机读的 `errorCode`，HTTP 状态与之对应：

| errorCode | HTTP | 含义 |
| --- | --- | --- |
| `ACCOUNT_NOT_FOUND` | 404 | 账户不存在 |
| `INSUFFICIENT_QUOTA` | 409 | 配额不足（扣减会导致负余额） |
| `CONCURRENT_CONFLICT` | 409 | 乐观锁版本已被并发事务抢先修改，调用方可重试 |
| `ACCOUNT_SUSPENDED` | 423 | 账户停用，状态不允许扣减 |
| `INVALID_CURSOR` | 400 | 游标伪造/损坏，或锚点在当前账户与时间窗口下不可见 |

## 数据模型

```text
quota_account
  id            BIGINT 主键自增
  account_no    VARCHAR(64) 唯一            -- 对外稳定标识
  balance       BIGINT 非负（CHECK 约束）    -- 配额余额
  status        ACTIVE | SUSPENDED（CHECK） -- 账户状态
  quota_type    VARCHAR(32) 非空默认 GENERAL -- V2 新增，历史行确定为 GENERAL
  version       BIGINT                      -- 乐观锁版本，每次修改 +1
  created_at / updated_at  TIMESTAMP(6)

usage_record
  id            BIGINT 主键自增
  account_id    BIGINT -> quota_account.id
  account_no    VARCHAR(64)                 -- 冗余账户号，查询无需回连账户表
  amount        BIGINT 正数（CHECK 约束）
  occurred_at   TIMESTAMP(6)                -- 业务发生时间（微秒），游标键之一
  note          VARCHAR(256) 非空默认 NONE   -- V2 引入 description，V3 改名为 note
  created_at    TIMESTAMP(6)

索引
  idx_usage_account_time      (account_id, occurred_at, id)   -- 游标键集扫描
  idx_usage_account_no_time   (account_no, occurred_at, id)   -- 锚点校验
```

不变量（数据库层强约束，不仅靠应用自觉）：
- `balance >= 0`：不可能出现负余额；
- `amount > 0`：用量必须为正，非法输入在服务端即被拒绝；
- 账户号唯一；状态只能取枚举值。

## 并发与事务策略

### 扣减：单条条件 UPDATE 完成原子 CAS

扣减不使用“先查后改”的两步非原子操作，而是把所有前置条件压进一条 SQL：

```sql
UPDATE quota_account
SET balance = balance - :amount, version = version + 1, updated_at = :now
WHERE id = :id
  AND status = 'ACTIVE'        -- 状态必须允许
  AND version = :expected      -- 未被并发事务修改
  AND balance >= :amount       -- 余额必须充足
```

- 数据库行锁保证同一账户并发扣减串行生效，配合 `balance >= :amount` 条件，
  **超额与负余额在数据库层就不可能发生**；
- 受影响行数为 0 时，在同一事务内重读账户并给出**可区分**的结论：
  停用 → `ACCOUNT_SUSPENDED`；余额不足 → `INSUFFICIENT_QUOTA`；
  版本被抢改且仍 ACTIVE/充足 → `CONCURRENT_CONFLICT`；
- 服务端**不做静默重试**：冲突是显式、可解释的，由调用方决定重试/放弃
  （`ConcurrentDebitTest` 演示了调用方的标准重试写法）。

### 事务：余额与用量同生共死

`debit()` 标注 `@Transactional`：条件扣减成功后立即插入 `usage_record`，
两者在同一事务内提交。记账后任何步骤抛异常都会整体回滚——
`TransactionAtomicityTest` 通过故障注入 Bean 验证了失败时
**余额、乐观锁版本、用量记录三者都不留痕**，不会只落一半。

### 用量查询：键集（seek）游标分页

- 排序键为 `(occurred_at ASC, id ASC)`。时间戳微秒精度，自增 `id` 作为
  同刻并列（tie）时的确定性第二排序键；
- 续页谓词为 `occurred_at > :t OR (occurred_at = :t AND id > :id)`，
  完全走复合索引 `(account_id, occurred_at, id)` 的范围扫描，
  每页固定 `limit + 1` 行（多取 1 行判断是否有下一页），
  **查询代价与已翻过的页数无关，不是 OFFSET，也不按条查库**；
- 翻页过程中写入的新记录必然排在游标之后（时间不回退、id 单调递增），
  因此已翻过的页既不会重复也不会漏；新记录会在尚未消费的尾部出现；
- 游标是**不透明令牌** `base64url(accountNo|epochMicros|id).HMAC-SHA256`：
  客户端无法伪造；服务端续页时除验签外，还要校验锚点记录真实存在、
  属于同一账户、且落在本次查询的时间窗口内。任何不一致都返回
  `INVALID_CURSOR`，**绝不静默地从头或从中间继续**。

性能的可验证依据见 `QueryPerformanceTest`：全表 2 万行（10 账户 × 2000 行），
用 `EXPLAIN ANALYZE` 断言首页与尾页执行计划都命中复合索引、无全表扫描、
`scanCount` 按目标账户有界（≤2001，与全表行数无关）；实测首页约 1ms、
深翻页约 5ms。

## 停用与恢复

- 停用只把 `status` 改为 `SUSPENDED`：扣减条件更新天然被拒
  （返回 `ACCOUNT_SUSPENDED`，HTTP 423），**余额不动、用量记录不删**，
  历史用量照常查询；
- 恢复后余额、历史记录、版本全部保留，可继续扣减；
- 重复停用/恢复是幂等的（状态未变则直接返回当前账户，不推进版本）；
  状态变更同样走版本 CAS，与并发扣减冲突时返回 `CONCURRENT_CONFLICT`。

## 结构变更与兼容规则

迁移脚本位于 `src/main/resources/db/migration/`，`flyway_schema_history`
表全程记录每个版本，变更可追溯；从空库启动会依次执行到最新版本。

- `V1__init_quota_schema.sql`：建立两张表、约束与索引；
- `V2__add_quota_type_and_channel.sql`：**新增字段**示例。
  新列一律带非空默认值并立即回填历史行，使默认语义物化、稳定：
  历史账户 `quota_type = 'GENERAL'`，历史用量 `note（当时名 description）= 'NONE'`；
- `V3__rename_description_and_drop_channel.sql`：**改名与删除**示例。
  改名通过显式 `ALTER COLUMN ... RENAME TO ...` 完成；过渡列
  `legacy_channel` 在 V3 删除。

兼容规则（防止“静默错列”和启动失败）：
1. 所有 SQL **显式列出列名**，禁止 `SELECT *`，读取不依赖物理列顺序；
   列被改名/删除后，任何残留的旧列引用都会在迁移或执行期直接报错，
   而不是把数据读进错误的字段；
2. 新字段必须提供确定、稳定的默认值并回填，代码不依赖数据库隐式默认；
3. 删列前先经历“停止写入 → 应用层忽略 → 删除”的过渡版本（如 V2→V3 的
   `legacy_channel`）；
4. 迁移只增不改已发布版本，演进通过新版本脚本追加。

`SchemaEvolutionTest` 在独立临时文件库上逐版本迁移并插入 V1 时代的历史行，
断言：空库可建出完整结构、新字段默认语义正确、改名/删列后按显式列名
读取历史数据仍正确、迁移幂等、`flyway_schema_history` 中 3 个版本全部成功。

## 测试与业务覆盖

`mvn test` 运行 18 个用例，全部使用内存 H2，无外部依赖：

| 测试类 | 覆盖业务 |
| --- | --- |
| `ConcurrentDebitTest` | 32 线程并发扣减的守恒/非负/边界；三类失败原因可区分；非法扣减量 |
| `TransactionAtomicityTest` | 故障注入下余额、版本、用量记录整笔回滚 |
| `CursorPagingTest` | 翻页不重不漏；翻页中写入；非法游标；跨账户/改窗口锚点不可见；时间边界 |
| `AccountLifecycleTest` | 停用拒绝扣减但历史可查；恢复不丢数据；状态变更幂等；重复建号 |
| `QueryPerformanceTest` | 2 万行下首页/尾页索引范围扫描、扫描量有界、毫秒级耗时 |
| `SchemaEvolutionTest` | 空库建结构、V1→V3 演进、默认值、改名删列、迁移历史可追溯 |

每个用例用 `@BusinessCoverage(module=..., caseDescription=...)` 标注业务归属，
`CoverageLoggingExtension` 会在控制台逐条输出
`[业务覆盖] 模块=... | 用例=... | 结果=... | 测试=...`，并在每个测试类结束时
按业务模块汇总用例数与通过情况，例如：

```text
[业务覆盖汇总] 测试类=CursorPagingTest
  - 业务=游标翻页：用例数=5，通过=5，结果=全部通过
      * 非法游标：篡改签名/乱码一律报 INVALID_CURSOR，不会从头继续 [PASS]
      ...
[查询性能] 全表=20000 行（10 个账户各 2000 行），首页耗时=1ms，第20页(尾页)耗时=5ms
```

## 项目结构

```text
src/main/java/com/github/lechandonga/quotausagepersistencejava/
  domain/      账户/用量/分页领域模型与状态枚举
  repo/        QuotaRepository：显式列名 SQL、CAS 扣减、键集分页
  service/     QuotaService（事务边界）、CursorCodec、业务异常
  web/         REST 控制器与统一错误码映射
src/main/resources/db/migration/  Flyway V1/V2/V3
src/test/.../tests/               六类业务测试 + 覆盖日志扩展
```

## 本地验证建议

```bash
# 1) 全量测试并观察业务覆盖与性能日志
mvn test
grep -E "业务覆盖|查询性能|并发扣减" target/surefire-reports/TEST-*.xml

# 2) 起服务手工验证
mvn spring-boot:run
curl -s -XPOST localhost:8080/api/quota/accounts \
  -H 'Content-Type: application/json' \
  -d '{"accountNo":"a1","initialBalance":1000}'
curl -s -XPOST localhost:8080/api/quota/accounts/a1/debit \
  -H 'Content-Type: application/json' -d '{"amount":300}'
curl -s "localhost:8080/api/quota/accounts/a1/usage?limit=10"
```
