package com.github.lechandonga.quotausagepersistencejava.tests;

import com.github.lechandonga.quotausagepersistencejava.Application;
import com.github.lechandonga.quotausagepersistencejava.domain.QuotaAccount;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务：表结构持续演进。
 * 用例（独立 H2 文件库，与主测试库隔离）：
 * 1) Flyway 只迁移到 V1，按旧结构写入历史账户与历史用量；
 * 2) 用“当前版本”的应用上下文直接启动（targetVersion=latest），从 V1 一路升级到 V3；
 * 3) 新增字段对历史行有稳定默认值（rollover_enabled=false、note=''）；
 * 4) 改名列 biz_ref 可读、历史数据不丢；删除列 label 不存在，应用启动不失败、读取不错列；
 * 5) flyway_schema_history 保留全部可追溯变更记录；空库可直接建出完整结构。
 */
class SchemaEvolutionTest {

    private static final Logger log = LoggerFactory.getLogger(SchemaEvolutionTest.class);

    @Test
    @DisplayName("V1 历史库 → 最新版本：默认语义稳定、改名/删列安全、变更全程可追溯")
    void upgradesFromV1WithStableDefaults() throws Exception {
        Path dir = Files.createTempDirectory("quota-evolution-");
        String jdbcUrl = "jdbc:h2:file:" + dir.resolve("evolve") + ";MODE=MySQL";

        // 第一步：空库只建出 V1 结构。
        Flyway flywayV1 = Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("1"))
                .load();
        flywayV1.migrate();

        // 第二步：按 V1 的旧结构/旧列名写入历史数据。
        try (var conn = java.sql.DriverManager.getConnection(jdbcUrl, "sa", "");
             var st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO quota_account "
                    + "(id, account_name, status, total_quota, remaining, version, "
                    + " created_at, updated_at) VALUES "
                    + "(10, 'legacy', 'ACTIVE', 100, 60, 0, 1000, 1000)");
            st.executeUpdate("INSERT INTO usage_record "
                    + "(id, account_id, amount, occurred_at, business_ref) VALUES "
                    + "(100, 10, 40, 1500, 'legacy-biz')");
        }
        log.info("[结构升级] 已在 V1 结构下写入历史账户与历史用量（旧列名 business_ref）");

        // 第三步：以当前应用版本启动，Flyway 自动从 V1 升级到最新版本。
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Application.class)
                .properties(
                        "spring.datasource.url=" + jdbcUrl,
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.flyway.locations=classpath:db/migration",
                        "spring.main.web-application-type=none",
                        "spring.task.scheduling.enabled=false")
                .run("--spring.datasource.url=" + jdbcUrl,
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=")) {

            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

            List<Map<String, Object>> history = jdbc.queryForList(
                    "SELECT id, account_id, amount, occurred_at, biz_ref, note "
                            + "FROM usage_record WHERE id = 100");
            assertThat(history).hasSize(1);
            Map<String, Object> legacyRow = history.get(0);
            // H2 对未加引号的标识符统一大写返回。
            assertThat(legacyRow.get("BIZ_REF")).isEqualTo("legacy-biz");
            assertThat(legacyRow.get("NOTE")).isEqualTo("");

            Map<String, Object> account = jdbc.queryForMap(
                    "SELECT account_name, total_quota, remaining, rollover_enabled "
                            + "FROM quota_account WHERE id = 10");
            assertThat(account.get("ROLLOVER_ENABLED")).isEqualTo(false);
            assertThat(((Number) account.get("REMAINING")).longValue()).isEqualTo(60L);

            var columns = jdbc.queryForList(
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                            + "WHERE TABLE_NAME = 'USAGE_RECORD'").stream()
                    .map(m -> m.get("COLUMN_NAME")).toList();
            assertThat(columns).contains("BIZ_REF", "NOTE").doesNotContain("BUSINESS_REF");
            var accountColumns = jdbc.queryForList(
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                            + "WHERE TABLE_NAME = 'QUOTA_ACCOUNT'").stream()
                    .map(m -> m.get("COLUMN_NAME")).toList();
            assertThat(accountColumns).doesNotContain("LABEL");

            String historyTable = jdbc.queryForObject(
                    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                            + "WHERE UPPER(TABLE_NAME) = 'FLYWAY_SCHEMA_HISTORY'",
                    String.class);
            List<Map<String, Object>> flywayHistory = jdbc.queryForList(
                    "SELECT \"version\", \"description\", \"success\" FROM \"" + historyTable + "\" "
                            + "ORDER BY \"installed_rank\"");
            log.info("[结构升级] flyway_schema_history: {}",
                    flywayHistory.stream().map(m -> m.get("version") + ":" + m.get("description"))
                            .toList());
            assertThat(flywayHistory).hasSizeGreaterThanOrEqualTo(3);
            assertThat(flywayHistory.stream().map(m -> m.get("VERSION")).toList())
                    .contains("1", "2", "3");
            assertThat(flywayHistory.stream().allMatch(m -> Boolean.TRUE.equals(m.get("SUCCESS"))))
                    .isTrue();

            // 第五步：升级后系统可正常服务——历史账户可查、可继续扣减并记账。
            com.github.lechandonga.quotausagepersistencejava.service.QuotaService service =
                    ctx.getBean(
                            com.github.lechandonga.quotausagepersistencejava.service.QuotaService.class);
            QuotaAccount legacy = service.getAccount(10L);
            assertThat(legacy.accountName()).isEqualTo("legacy");
            assertThat(legacy.rolloverEnabled()).isFalse();
            service.deduct(10L, 10, "post-upgrade", "");
            assertThat(service.getAccount(10L).remaining()).isEqualTo(50L);
            log.info("[结构升级] 升级后历史账户可继续扣减（60→50），新增字段默认值稳定，"
                    + "改名列 biz_ref 正常读取，删除列 label 不影响启动");
        }
    }

    @Test
    @DisplayName("空库直接迁移到最新版本：完整结构一次建出")
    void freshSchemaBuildsCompletely() throws Exception {
        Path dir = Files.createTempDirectory("quota-fresh-");
        String jdbcUrl = "jdbc:h2:file:" + dir.resolve("fresh") + ";MODE=MySQL";
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration")
                .load();
        int applied = flyway.migrate().migrationsExecuted;
        log.info("[空库建表] 全新空库一次性执行 {} 个迁移脚本", applied);

        try (var conn = java.sql.DriverManager.getConnection(jdbcUrl, "sa", "");
             var st = conn.createStatement()) {
            assertThat(st.executeQuery("SELECT COUNT(*) FROM quota_account").next()).isTrue();
            assertThat(st.executeQuery("SELECT COUNT(*) FROM usage_record").next()).isTrue();
            var historyTable = st.executeQuery(
                    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                            + "WHERE UPPER(TABLE_NAME) = 'FLYWAY_SCHEMA_HISTORY'");
            assertThat(historyTable.next()).isTrue();
            String historyTableName = historyTable.getString(1);
            var rs = st.executeQuery(
                    "SELECT COUNT(*) FROM \"" + historyTableName + "\" WHERE \"success\" = TRUE");
            rs.next();
            assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(3);
        }
    }
}
