package io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * Sent by the start step of {@link ThreeStepSaga}; handled by {@link ReservationService}.
 *
 * @param orderId  the saga's correlation value
 * @param quantity the number of items to reserve
 */
@Command
public record ReserveItems(String orderId, int quantity) {
}
