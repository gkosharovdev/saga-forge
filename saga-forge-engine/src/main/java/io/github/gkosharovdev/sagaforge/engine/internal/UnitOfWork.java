package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.spi.DomainEventRecorder;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Optional;

/**
 * An engine unit of work, saga-scoped ({@link SagaUnitOfWork}) or not ({@link NonSagaUnitOfWork}), as bound to its
 * thread through {@link DomainEventScope#runWith(DomainEventRecorder, Runnable)}.
 *
 * <p>Besides recording domain events it accepts failure reports: the command gateway reports every handler,
 * loader, saver, resolver or provider failure before the exception leaves {@code send}, so the unit of work fails
 * even if the step or handler that called {@code send} catches the exception (002 FR-012).</p>
 */
interface UnitOfWork extends DomainEventRecorder {

    /** Reports a failure inside this unit of work; the first report is kept and fails it. */
    void failed(Throwable failure);

    /** The engine unit of work bound to the calling thread, if any; another recorder is not one. */
    static Optional<UnitOfWork> bound() {
        return DomainEventScope.current().filter(UnitOfWork.class::isInstance).map(UnitOfWork.class::cast);
    }

    /**
     * Rethrows {@code failure} unchanged if it is an {@link Error}; returns it for the caller to throw if it is a
     * {@link RuntimeException}; wraps any other {@code Throwable} once in an {@link UndeclaredThrowableException}.
     */
    static RuntimeException propagate(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        return new UndeclaredThrowableException(failure);
    }
}
