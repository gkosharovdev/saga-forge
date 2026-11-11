package io.github.gkosharovdev.sagaforge.spring.modulith.app.notifications;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.github.gkosharovdev.sagaforge.spring.modulith.app.payments.PaymentEvents.PaymentTaken;

/** Another module's listener for a saga event: runs after commit, in its own transaction. */
@Component
public class PaymentNotifier {

    private final List<String> notified = new CopyOnWriteArrayList<>();
    private final List<Boolean> inTransaction = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();

    @ApplicationModuleListener
    public void on(PaymentTaken event) {
        inTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
        if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new IllegalStateException("notification failed for " + event.orderId());
        }
        notified.add(event.orderId());
    }

    public void failNext(int times) {
        failuresLeft.set(times);
    }

    public List<String> notified() {
        return List.copyOf(notified);
    }

    public List<Boolean> inTransaction() {
        return List.copyOf(inTransaction);
    }
}
