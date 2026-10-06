package com.github.lechandonga.quotausagepersistencejava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 业务：应用装配。用例：空内存库 + Flyway 迁移后上下文可以正常启动。
 */
@SpringBootTest
@DisplayName("业务-应用装配：空库迁移后上下文启动成功")
class ApplicationTests {

    @Test
    @DisplayName("Spring 上下文加载成功，数据源/Flyway/服务 Bean 装配完整")
    void contextLoads() {
    }
}
