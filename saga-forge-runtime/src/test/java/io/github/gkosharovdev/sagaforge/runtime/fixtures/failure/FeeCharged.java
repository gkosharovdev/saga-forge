package io.github.gkosharovdev.sagaforge.runtime.fixtures.failure;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Recorded by {@link FeeService}; runs {@code onFeeCharged} in-process. */
@DomainEvent(aggregate = Fee.class)
public record FeeCharged(@AggregateIdentifier String feeId, String parcelId) {
}
