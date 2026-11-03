package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;

/**
 * Invalid: two {@code @AggregateSaver} methods breaking the saver shape (spec FR-013) — one takes a
 * non-{@code @Aggregate} parameter, the other takes two parameters.
 */
public class MalformedSaver {

    @AggregateSaver
    public void saveNonAggregate(String notAnAggregate) {
    }

    @AggregateSaver
    public void saveWithTwoParameters(ValidAggregate aggregate, String tenant) {
    }
}
