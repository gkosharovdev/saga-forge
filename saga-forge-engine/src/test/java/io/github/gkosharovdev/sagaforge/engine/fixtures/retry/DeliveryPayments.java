package io.github.gkosharovdev.sagaforge.engine.fixtures.retry;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import java.util.ArrayList;
import java.util.List;

/**
 * Charges through the {@link PaymentProvider} keyed by the command identity, then records the charge. An amount of
 * {@link #CHARGED_ELSEWHERE} records nothing: the test then publishes {@link DeliveryCharged} itself, so
 * {@code onCharged} runs in a unit of work triggered by a non-start event.
 */
public final class DeliveryPayments {

    /** The amount for which no {@link DeliveryCharged} is recorded. */
    public static final long CHARGED_ELSEWHERE = 0;

    private final PaymentProvider provider;
    private final List<CommandId> seen = new ArrayList<>();

    public DeliveryPayments(PaymentProvider provider) {
        this.provider = provider;
    }

    @CommandHandler
    public void handle(ChargeDelivery command, CommandId commandId) {
        seen.add(commandId);
        provider.charge(commandId.value(), command.amount());
        if (command.amount() != CHARGED_ELSEWHERE) {
            DomainEvents.record(new DeliveryCharged(command.bookingId()));
        }
    }

    public List<CommandId> seen() {
        return List.copyOf(seen);
    }
}
