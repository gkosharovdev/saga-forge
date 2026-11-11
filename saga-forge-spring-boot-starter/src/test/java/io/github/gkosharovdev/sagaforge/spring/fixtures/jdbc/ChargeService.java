package io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc;

import org.springframework.beans.factory.annotation.Qualifier;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc.JdbcOrderEvents.ChargeOrder;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc.JdbcOrderEvents.OrderCharged;

/** A handler bean whose provider parameter is resolved from the context by its {@code @Qualifier} (US2 scenario 3). */
public class ChargeService {

    @CommandHandler
    public void handle(ChargeOrder command, CommandId commandId, @Qualifier("primaryProvider") PaymentProvider provider) {
        String chargeId = provider.charge(commandId.value());
        DomainEvents.record(new OrderCharged(chargeId, command.orderId(), provider.name()));
    }
}
