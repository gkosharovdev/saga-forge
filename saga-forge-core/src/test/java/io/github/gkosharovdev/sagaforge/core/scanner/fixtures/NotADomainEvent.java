package io.github.gkosharovdev.sagaforge.core.scanner.fixtures;

/**
 * A plain class NOT annotated with @DomainEvent, used to test
 * that the scanner rejects @SagaStep methods with non-@DomainEvent parameters.
 */
public record NotADomainEvent(String orderId) {
}
