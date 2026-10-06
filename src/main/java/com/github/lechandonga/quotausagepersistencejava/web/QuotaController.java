package com.github.lechandonga.quotausagepersistencejava.web;

import com.github.lechandonga.quotausagepersistencejava.domain.QuotaAccount;
import com.github.lechandonga.quotausagepersistencejava.domain.UsagePage;
import com.github.lechandonga.quotausagepersistencejava.domain.UsageRecord;
import com.github.lechandonga.quotausagepersistencejava.service.QuotaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/quota")
public class QuotaController {

    private final QuotaService quotaService;

    public QuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @PostMapping("/accounts")
    public QuotaAccount create(@RequestBody CreateAccountRequest request) {
        long initialBalance = request.initialBalance() == null ? 0L : request.initialBalance();
        return quotaService.createAccount(request.accountNo(), initialBalance, request.quotaType());
    }

    @GetMapping("/accounts/{accountNo}")
    public QuotaAccount get(@PathVariable String accountNo) {
        return quotaService.getAccount(accountNo);
    }

    @PostMapping("/accounts/{accountNo}/debit")
    public UsageRecord debit(@PathVariable String accountNo, @RequestBody DebitRequest request) {
        if (request.amount() == null) {
            throw new IllegalArgumentException("amount 不能为空");
        }
        return quotaService.debit(accountNo, request.amount(), request.note());
    }

    @PostMapping("/accounts/{accountNo}/suspend")
    public QuotaAccount suspend(@PathVariable String accountNo) {
        return quotaService.suspend(accountNo);
    }

    @PostMapping("/accounts/{accountNo}/resume")
    public QuotaAccount resume(@PathVariable String accountNo) {
        return quotaService.resume(accountNo);
    }

    /**
     * 游标用量查询：from/to 为 ISO-8601（可空），cursor 为上一页返回的不透明令牌。
     */
    @GetMapping("/accounts/{accountNo}/usage")
    public UsagePage usage(@PathVariable String accountNo,
                           @RequestParam(required = false) Instant from,
                           @RequestParam(required = false) Instant to,
                           @RequestParam(required = false) String cursor,
                           @RequestParam(defaultValue = "50") int limit) {
        return quotaService.queryUsage(accountNo, from, to, cursor, limit);
    }

    public record CreateAccountRequest(String accountNo, Long initialBalance, String quotaType) {
    }

    public record DebitRequest(Long amount, String note) {
    }
}
