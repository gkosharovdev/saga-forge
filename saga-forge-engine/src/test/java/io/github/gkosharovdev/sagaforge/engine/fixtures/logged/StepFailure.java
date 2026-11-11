package io.github.gkosharovdev.sagaforge.engine.fixtures.logged;


import io.github.gkosharovdev.sagaforge.core.failure.TransientFailure;

/** A transient failure of {@link BillingSaga}s start step. */
public final class StepFailure extends RuntimeException implements TransientFailure {

    private static final long serialVersionUID = 1L;

    public StepFailure(String message) {
        super(message);
    }
}
