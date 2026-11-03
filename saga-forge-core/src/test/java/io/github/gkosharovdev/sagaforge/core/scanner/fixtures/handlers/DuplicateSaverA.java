package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;

/**
 * One of two savers for {@link ValidAggregate}; together with {@link DuplicateSaverB} it violates spec
 * FR-014 (model error). On its own it is valid.
 */
public class DuplicateSaverA {

    @AggregateSaver
    public void save(ValidAggregate aggregate) {
    }
}
