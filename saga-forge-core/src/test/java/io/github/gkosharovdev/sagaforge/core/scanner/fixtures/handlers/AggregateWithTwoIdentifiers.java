package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/**
 * Invalid: an {@code @Aggregate} with two {@code @AggregateIdentifier} fields (spec FR-014).
 */
@Aggregate
public class AggregateWithTwoIdentifiers {

    @AggregateIdentifier
    private String id;

    @AggregateIdentifier
    private String otherId;
}
