package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;


import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Sent by {@link FulfilmentSaga}; handled in the sending unit of work by {@link PaymentService}. */
@Command
public record RequestPayment(String orderId) {
}
