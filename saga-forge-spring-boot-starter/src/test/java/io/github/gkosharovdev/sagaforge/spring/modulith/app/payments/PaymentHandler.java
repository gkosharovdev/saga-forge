package io.github.gkosharovdev.sagaforge.spring.modulith.app.payments;

import org.springframework.stereotype.Component;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.spring.modulith.app.payments.PaymentEvents.PaymentTaken;
import io.github.gkosharovdev.sagaforge.spring.modulith.app.payments.PaymentEvents.TakePayment;

/** Takes a payment and records {@link PaymentTaken}. */
@Component
public class PaymentHandler {

    @CommandHandler
    public void handle(TakePayment command, CommandId commandId) {
        DomainEvents.record(new PaymentTaken("payment-" + commandId.value(), command.orderId(), command.amount()));
    }
}
