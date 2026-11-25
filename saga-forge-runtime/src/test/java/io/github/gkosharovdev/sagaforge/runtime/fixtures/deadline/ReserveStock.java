package io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** A command of {@link PaymentWindowSaga}, recorded by {@link StockService}. */
@Command
public record ReserveStock(String orderId) {}
