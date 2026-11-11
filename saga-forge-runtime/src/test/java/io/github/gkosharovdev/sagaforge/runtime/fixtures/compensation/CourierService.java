package io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;

/** Books couriers; {@link #failWith} makes every booking fail with a given exception. */
public final class CourierService {

    private RuntimeException failure;
    private int calls;

    @CommandHandler
    public void handle(BookCourier command) {
        calls++;
        if (failure != null) {
            throw failure;
        }
    }

    public void failWith(RuntimeException bookingFailure) {
        this.failure = bookingFailure;
    }

    public int calls() {
        return calls;
    }
}
