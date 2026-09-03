package com.overmind.adapter.out.persistence;

import com.overmind.application.port.TransactionBoundary;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Spring transaction adapter. Participating repository transactions use REQUIRED propagation. */
@Component
public class SpringTransactionBoundary implements TransactionBoundary {

    private final TransactionTemplate template;

    public SpringTransactionBoundary(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
