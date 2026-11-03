package io.github.gkosharovdev.sagaforge.core.scanner.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/**
 * A minimal aggregate fixture that the scanner test events refer to via
 * {@code @DomainEvent(aggregate = TestAggregate.class)}.
 */
@Aggregate
public class TestAggregate {

    @AggregateIdentifier
    private String orderId;
}
