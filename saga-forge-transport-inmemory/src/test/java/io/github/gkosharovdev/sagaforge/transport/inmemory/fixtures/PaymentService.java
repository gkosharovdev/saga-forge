package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;


import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;

/**
 * Payment module handler: notes each request; the confirmation arrives later from outside. A request for an order id
 * starting with {@value #REFUSED_PREFIX} throws, failing the saga's start step (011 outcome events). Thread-safe.
 */
public final class PaymentService {

    /** Order ids with this prefix are refused. */
    public static final String REFUSED_PREFIX = "refused-";

    private final List<String> requested = new CopyOnWriteArrayList<>();

    @CommandHandler
    public void handle(RequestPayment command) {
        if (command.orderId().startsWith(REFUSED_PREFIX)) {
            throw new IllegalStateException("payment refused for " + command.orderId());
        }
        requested.add(command.orderId());
    }

    public List<String> requested() {
        return List.copyOf(requested);
    }
}
