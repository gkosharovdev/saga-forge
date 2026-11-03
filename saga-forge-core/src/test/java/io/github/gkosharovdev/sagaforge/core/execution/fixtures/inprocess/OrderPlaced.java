package io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * Start event of {@link ThreeStepSaga}, published from outside any saga unit of work. The order id is both
 * the {@link Order} aggregate's identity and the saga's correlation value.
 *
 * @param orderId  the order's id and the saga's association value
 * @param amount   the order amount, lifted into {@code SagaContext} by the start step
 * @param quantity the number of items to reserve
 */
@DomainEvent(aggregate = Order.class)
public record OrderPlaced(@AggregateIdentifier String orderId, long amount, int quantity) {
}
