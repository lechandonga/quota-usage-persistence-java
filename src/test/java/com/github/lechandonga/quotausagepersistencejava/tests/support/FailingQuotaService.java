package com.github.lechandonga.quotausagepersistencejava.tests.support;

import com.github.lechandonga.quotausagepersistencejava.repo.QuotaRepository;
import com.github.lechandonga.quotausagepersistencejava.service.CursorCodec;
import com.github.lechandonga.quotausagepersistencejava.service.QuotaService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 测试专用：在“余额已扣 + 用量已写”之后人为抛出异常，
 * 验证二者处于同一事务——异常时整笔回滚，不会只落一半。
 */
@Component
public class FailingQuotaService extends QuotaService {

    public FailingQuotaService(QuotaRepository repository, CursorCodec cursorCodec) {
        super(repository, cursorCodec);
    }

    @Transactional
    public void debitThenFail(String accountNo, long amount) {
        debit(accountNo, amount, "will-rollback");
        throw new IllegalStateException("模拟记账后步骤失败，事务必须整体回滚");
    }
}
