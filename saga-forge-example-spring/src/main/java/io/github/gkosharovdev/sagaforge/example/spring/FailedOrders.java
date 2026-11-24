package io.github.gkosharovdev.sagaforge.example.spring;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import io.github.gkosharovdev.sagaforge.core.outcome.SagaFailed;

/**
 * Notes the orders whose saga failed, from Saga Forge's {@link SagaFailed} outcome event. The listener runs after the
 * unit of work that recorded the failure committed, so it never sees a failure that was rolled back. A rejected order
 * is compensated, not failed, and does not show up here.
 */
@Component
public class FailedOrders {

    private final List<String> orderIds = new CopyOnWriteArrayList<>();

    @TransactionalEventListener
    public void on(SagaFailed failed) {
        if (failed.sagaType().equals(OrderProcessingSaga.class.getName())) {
            orderIds.add(failed.associations().get("orderId"));
        }
    }

    /** @return the failed orders, in the order their failure committed */
    public List<String> orderIds() {
        return List.copyOf(orderIds);
    }
}
