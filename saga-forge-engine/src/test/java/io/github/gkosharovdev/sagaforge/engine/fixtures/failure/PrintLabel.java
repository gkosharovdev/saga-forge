package io.github.gkosharovdev.sagaforge.engine.fixtures.failure;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Sent by the {@code @EndSaga} step; handled by the {@link Label} it names. */
@Command
public record PrintLabel(@AggregateIdentifier String labelId, String parcelId) {
}
