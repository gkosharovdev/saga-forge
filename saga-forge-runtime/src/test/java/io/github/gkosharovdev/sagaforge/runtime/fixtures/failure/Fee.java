package io.github.gkosharovdev.sagaforge.runtime.fixtures.failure;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/** Marker aggregate named by {@link FeeCharged}; {@link FeeService} keeps the fees. */
@Aggregate
public final class Fee {

    @AggregateIdentifier
    private String feeId;
}
