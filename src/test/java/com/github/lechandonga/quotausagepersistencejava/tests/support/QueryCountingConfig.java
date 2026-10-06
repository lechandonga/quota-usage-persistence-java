package com.github.lechandonga.quotausagepersistencejava.tests.support;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * 用 {@link QueryCountingDataSource} 包装容器中的 DataSource，对业务代码透明。
 */
@TestConfiguration
public class QueryCountingConfig {

    @Bean
    public BeanPostProcessor countingDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName)
                    throws BeansException {
                if (bean instanceof DataSource ds
                        && !(bean instanceof QueryCountingDataSource)
                        && "dataSource".equals(beanName)) {
                    return new QueryCountingDataSource(ds);
                }
                return bean;
            }
        };
    }
}
