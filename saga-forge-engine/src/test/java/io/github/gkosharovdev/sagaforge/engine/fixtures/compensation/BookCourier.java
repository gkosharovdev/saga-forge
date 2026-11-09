package io.github.gkosharovdev.sagaforge.engine.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Books a courier through {@link CourierService}, which may fail on purpose. */
@Command
public record BookCourier(String orderId) {
}
