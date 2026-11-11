package io.github.gkosharovdev.sagaforge.runtime.fixtures.retry;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Books a courier through {@link CourierService}. */
@Command
public record BookCourier(String bookingId) {
}
