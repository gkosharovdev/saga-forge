package io.github.gkosharovdev.sagaforge.engine.fixtures.retry;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;

/** Books a courier as scripted by its {@link CourierScript}, then records the booking. */
public final class CourierService {

    private final CourierScript script;

    public CourierService(CourierScript script) {
        this.script = script;
    }

    @CommandHandler
    public void handle(BookCourier command) {
        script.next();
        DomainEvents.record(new CourierBooked(command.bookingId()));
    }
}
