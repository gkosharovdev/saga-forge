package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;


import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;

/** Payment module handler: notes each request; the confirmation arrives later from outside. Thread-safe. */
public final class PaymentService {

    private final List<String> requested = new CopyOnWriteArrayList<>();

    @CommandHandler
    public void handle(RequestPayment command) {
        requested.add(command.orderId());
    }

    public List<String> requested() {
        return List.copyOf(requested);
    }
}
