package io.github.gkosharovdev.sagaforge.engine.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import java.util.ArrayList;
import java.util.List;

/** Records delivery requests; the partner's answer is published by the test. */
public final class DeliveryService {

    private final List<String> requested = new ArrayList<>();

    @CommandHandler
    public void handle(RequestDelivery command) {
        requested.add(command.orderId());
    }

    public List<String> requested() {
        return List.copyOf(requested);
    }
}
