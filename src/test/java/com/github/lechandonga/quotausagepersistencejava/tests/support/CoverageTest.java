package com.github.lechandonga.quotausagepersistencejava.tests.support;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.test.context.TestConstructor;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 测试基注解：启动完整 Spring 上下文（内存 H2 + Flyway）并输出业务覆盖日志。
 */
@SpringBootTest
@ExtendWith(CoverageLoggingExtension.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CoverageTest {
}
