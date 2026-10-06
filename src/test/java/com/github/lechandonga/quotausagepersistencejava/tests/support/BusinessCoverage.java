package com.github.lechandonga.quotausagepersistencejava.tests.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注测试方法覆盖的业务模块与用例描述，供日志汇总输出。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface BusinessCoverage {

    /** 业务模块，如“并发扣减”。 */
    String module();

    /** 用例描述，如“余额恰好用尽时最后一笔成功”。 */
    String caseDescription();
}
