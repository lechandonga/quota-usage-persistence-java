package com.github.lechandonga.quotausagepersistencejava.tests.support;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统计翻页查询（SQL 带 quota.page.* 标记注释）实际触发的 JDBC 往返次数。
 * 用于验证 keyset 翻页不会逐行查库、每页只打一条 SQL。
 */
public class QueryCountingDataSource extends DelegatingDataSource {

    private final AtomicLong pageQueries = new AtomicLong();

    public QueryCountingDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    public long pageQueryCount() {
        return pageQueries.get();
    }

    public void reset() {
        pageQueries.set(0);
    }

    @Override
    public Connection getConnection() throws java.sql.SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws java.sql.SQLException {
        return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName()) && args != null
                            && args.length > 0 && args[0] instanceof String sql
                            && sql.contains("quota.page.")) {
                        pageQueries.incrementAndGet();
                    }
                    if ("createStatement".equals(method.getName())) {
                        Statement statement = (Statement) method.invoke(connection, args);
                        return Proxy.newProxyInstance(
                                Statement.class.getClassLoader(),
                                new Class<?>[]{Statement.class},
                                (stmtProxy, stmtMethod, stmtArgs) -> {
                                    if ("execute".equals(stmtMethod.getName())
                                            || "executeQuery".equals(stmtMethod.getName())) {
                                        if (stmtArgs != null && stmtArgs.length > 0
                                                && stmtArgs[0] instanceof String s
                                                && s.contains("quota.page.")) {
                                            pageQueries.incrementAndGet();
                                        }
                                    }
                                    return stmtMethod.invoke(statement, stmtArgs);
                                });
                    }
                    return method.invoke(connection, args);
                });
    }
}
