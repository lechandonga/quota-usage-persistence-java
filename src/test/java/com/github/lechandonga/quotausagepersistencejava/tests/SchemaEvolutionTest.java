package com.github.lechandonga.quotausagepersistencejava.tests;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.lechandonga.quotausagepersistencejava.tests.support.BusinessCoverage;

/**
 * 结构演进场景（独立的临时文件库，按版本逐段迁移，避免共享内存库）：
 * 空库 -> V1 建表；V2 新字段对历史行有确定默认；V3 改名/删列后不出现静默错列；
 * flyway_schema_history 全程保留可追溯变更记录。
 */
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class SchemaEvolutionTest {

    private final DataSource dataSource;

    SchemaEvolutionTest(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private Flyway flyway(Path dir, String target) {
        return Flyway.configure()
                .dataSource("jdbc:h2:file:" + dir.resolve("evolve-db")
                        + ";DATABASE_TO_LOWER=TRUE", "sa", "")
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    @BusinessCoverage(module = "结构升级", caseDescription = "从空库直接建出完整结构并逐版本演进，flyway_schema_history 保留全部变更")
    void emptyDatabaseMigratesToLatestWithHistory(@TempDir Path dir) throws Exception {
        MigrateResult v1 = flyway(dir, "1").migrate();
        assertEquals(1, v1.migrationsExecuted);
        assertColumnExists(dir, "quota_account", "balance");
        assertColumnExists(dir, "usage_record", "id");

        try (Connection connection = dataSource(dir);
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO quota_account (account_no, balance, status, version, created_at, updated_at) "
                    + "VALUES ('legacy-acct', 50, 'ACTIVE', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            statement.execute("INSERT INTO usage_record (account_id, account_no, amount, occurred_at, created_at) "
                    + "VALUES (1, 'legacy-acct', 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        MigrateResult v2 = flyway(dir, "2").migrate();
        assertEquals(1, v2.migrationsExecuted);
        try (Connection connection = dataSource(dir);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT quota_type, legacy_channel FROM quota_account WHERE account_no = 'legacy-acct'")) {
            assertTrue(rs.next());
            assertEquals("GENERAL", rs.getString("quota_type"), "新增字段对历史数据必须有确定默认语义");
            assertEquals("DEFAULT", rs.getString("legacy_channel"));
        }
        try (Connection connection = dataSource(dir);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT description FROM usage_record WHERE account_no = 'legacy-acct'")) {
            assertTrue(rs.next());
            assertEquals("NONE", rs.getString("description"));
        }

        MigrateResult v3 = flyway(dir, "3").migrate();
        assertEquals(1, v3.migrationsExecuted);
        assertColumnExists(dir, "usage_record", "note");
        assertColumnAbsent(dir, "usage_record", "description");
        assertColumnAbsent(dir, "quota_account", "legacy_channel");

        try (Connection connection = dataSource(dir);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT balance, quota_type, note FROM quota_account a "
                             + "JOIN usage_record u ON u.account_id = a.id WHERE a.account_no = 'legacy-acct'")) {
            assertTrue(rs.next(), "改名/删列后按显式列名读取历史数据仍然正确，无静默错列");
            assertEquals(50L, rs.getLong("balance"));
            assertEquals("GENERAL", rs.getString("quota_type"));
            assertEquals("NONE", rs.getString("note"));
        }

        try (Connection connection = dataSource(dir);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT version, success FROM \"flyway_schema_history\" "
                             + "WHERE type = 'SQL' ORDER BY installed_rank")) {
            int versions = 0;
            while (rs.next()) {
                versions++;
                assertTrue(rs.getString("version").matches("[123]"));
                assertTrue(rs.getBoolean("success"), "所有迁移必须成功");
            }
            assertEquals(3, versions, "变更记录完整可追溯");
        }
    }

    @Test
    @BusinessCoverage(module = "结构升级", caseDescription = "重复迁移幂等：再次 migrate 不执行任何脚本")
    void migrateIsIdempotent(@TempDir Path dir) {
        Flyway flyway = flyway(dir, "latest");
        assertEquals(3, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted, "结构已到最新版本时迁移为空操作");
    }

    private Connection dataSource(Path dir) throws java.sql.SQLException {
        return org.h2.Driver.load().connect(
                "jdbc:h2:file:" + dir.resolve("evolve-db") + ";DATABASE_TO_LOWER=TRUE",
                new java.util.Properties() {{
                    setProperty("user", "sa");
                    setProperty("password", "");
                }});
    }

    private void assertColumnExists(Path dir, String table, String column) throws Exception {
        try (Connection connection = dataSource(dir);
             ResultSet rs = connection.getMetaData().getColumns(null, null, table, column)) {
            assertTrue(rs.next(), "期望列存在: " + table + "." + column);
        }
    }

    private void assertColumnAbsent(Path dir, String table, String column) throws Exception {
        try (Connection connection = dataSource(dir);
             ResultSet rs = connection.getMetaData().getColumns(null, null, table, column)) {
            assertFalse(rs.next(), "期望列已删除: " + table + "." + column);
        }
    }
}
