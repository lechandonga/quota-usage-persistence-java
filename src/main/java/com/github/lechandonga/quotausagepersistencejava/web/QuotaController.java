package com.github.lechandonga.quotausagepersistencejava.web;

import com.github.lechandonga.quotausagepersistencejava.domain.QuotaAccount;
import com.github.lechandonga.quotausagepersistencejava.service.Page;
import com.github.lechandonga.quotausagepersistencejava.service.QuotaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 配额与用量 HTTP 接口，仅作为持久化能力的薄封装，便于本地手工验证。
 */
@RestController
@RequestMapping("/api/quota")
public class QuotaController {

    private final QuotaService quotaService;

    public QuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @PostMapping("/accounts")
    public Map<String, Object> createAccount(@RequestBody CreateAccountRequest request) {
        var result = quotaService.createAccount(request.accountName(), request.totalQuota());
        return Map.of("accountId", result.accountId(),
                "accountName", result.accountName(),
                "totalQuota", result.totalQuota());
    }

    @GetMapping("/accounts/{accountId}")
    public QuotaAccount getAccount(@PathVariable long accountId) {
        return quotaService.getAccount(accountId);
    }

    @PostMapping("/accounts/{accountId}/deduct")
    public Map<String, Object> deduct(@PathVariable long accountId,
                                      @RequestBody DeductRequest request) {
        var result = quotaService.deduct(accountId, request.amount(),
                request.bizRef(), request.note());
        return Map.of("accountId", result.accountId(),
                "remainingAfter", result.remainingAfter(),
                "usageRecordId", result.usageRecordId());
    }

    @PostMapping("/accounts/{accountId}/disable")
    public Map<String, Object> disable(@PathVariable long accountId) {
        quotaService.disableAccount(accountId);
        return Map.of("accountId", accountId, "status", "DISABLED");
    }

    @PostMapping("/accounts/{accountId}/enable")
    public Map<String, Object> enable(@PathVariable long accountId) {
        quotaService.enableAccount(accountId);
        return Map.of("accountId", accountId, "status", "ACTIVE");
    }

    @GetMapping("/accounts/{accountId}/usage")
    public Map<String, Object> queryUsage(@PathVariable long accountId,
                                          @RequestParam long from,
                                          @RequestParam long to,
                                          @RequestParam(required = false) Integer pageSize,
                                          @RequestParam(required = false) String cursor) {
        Page page = quotaService.queryUsage(accountId, from, to, pageSize, cursor);
        return Map.of(
                "records", page.records(),
                "nextCursor", page.nextCursor() == null ? "" : page.nextCursor(),
                "hasMore", page.hasMore());
    }

    public record CreateAccountRequest(String accountName, Long totalQuota) {
        @Override
        public Long totalQuota() {
            return totalQuota == null ? 0L : totalQuota;
        }
    }

    public record DeductRequest(Long amount, String bizRef, String note) {
        @Override
        public Long amount() {
            return amount == null ? 0L : amount;
        }
    }

}
