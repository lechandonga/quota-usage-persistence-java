package com.github.lechandonga.quotausagepersistencejava.tests.support;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 逐用例输出业务覆盖日志，并在每个测试类结束时按业务模块汇总用例数与通过情况，
 * 使“运行测试的日志能看出覆盖了哪些业务，以及各业务的用例与测试情况”。
 */
public class CoverageLoggingExtension implements TestWatcher, AfterAllCallback {

    private static final Map<String, List<CaseResult>> RESULTS_BY_CLASS = new ConcurrentHashMap<>();

    private record CaseResult(String module, String description, String method, String result) {
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        record(context, "PASS");
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        record(context, "FAIL: " + cause.getClass().getSimpleName());
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        record(context, "ABORTED");
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional reason) {
        record(context, "DISABLED");
    }

    @SuppressWarnings("unchecked")
    private void record(ExtensionContext context, String result) {
        BusinessCoverage coverage = context.getRequiredTestMethod().getAnnotation(BusinessCoverage.class);
        String module = coverage == null ? "未分类" : coverage.module();
        String description = coverage == null ? context.getDisplayName() : coverage.caseDescription();
        String method = context.getRequiredTestClass().getSimpleName() + "#" + context.getRequiredTestMethod().getName();
        System.out.printf("[业务覆盖] 模块=%s | 用例=%s | 结果=%s | 测试=%s%n",
                module, description, result, method);

        List<CaseResult> results = RESULTS_BY_CLASS
                .computeIfAbsent(context.getRequiredTestClass().getName(),
                        key -> new CopyOnWriteArrayList<>());
        results.add(new CaseResult(module, description, method, result));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void afterAll(ExtensionContext context) {
        List<CaseResult> results = RESULTS_BY_CLASS.get(context.getRequiredTestClass().getName());
        if (results.isEmpty()) {
            return;
        }
        Map<String, List<CaseResult>> byModule = new ConcurrentHashMap<>();
        results.forEach(r -> byModule.computeIfAbsent(r.module(), key -> new CopyOnWriteArrayList<>()).add(r));
        System.out.println("[业务覆盖汇总] 测试类=" + context.getRequiredTestClass().getSimpleName());
        byModule.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    long passed = entry.getValue().stream().filter(r -> r.result().equals("PASS")).count();
                    System.out.printf("  - 业务=%s：用例数=%d，通过=%d，结果=%s%n",
                            entry.getKey(), entry.getValue().size(), passed,
                            passed == entry.getValue().size() ? "全部通过" : "存在失败");
                    entry.getValue().forEach(r -> System.out.printf("      * %s [%s]%n",
                            r.description(), r.result()));
                });
        RESULTS_BY_CLASS.remove(context.getRequiredTestClass().getName());
    }
}
