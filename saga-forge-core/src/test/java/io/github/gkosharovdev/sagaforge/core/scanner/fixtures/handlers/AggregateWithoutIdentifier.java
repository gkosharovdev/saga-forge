package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;

/**
 * Invalid: an {@code @Aggregate} with no {@code @AggregateIdentifier} field (spec FR-014).
 */
@Aggregate
public class AggregateWithoutIdentifier {

    private String id;
}
